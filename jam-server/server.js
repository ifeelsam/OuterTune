/**
 * OuterTune Jam Server
 * Compatible with Node.js 10+ — no optional chaining or nullish coalescing.
 */

var WebSocket = require("ws").WebSocket;
var WebSocketServer = require("ws").WebSocketServer;
var http = require("http");
var crypto = require("crypto");

var HOST_GRACE_PERIOD_MS = 15 * 1000;

// ─── State ───────────────────────────────────────────────────────────────────

var sessions = new Map();
var codeToSessionId = new Map();
var wsData = new WeakMap();

// ─── Helpers ─────────────────────────────────────────────────────────────────

function generateCode() {
  var chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  var code;
  do {
    code = Array.from({ length: 6 }, function() {
      return chars[Math.floor(Math.random() * chars.length)];
    }).join("");
  } while (codeToSessionId.has(code));
  return code;
}

function generateId() {
  return crypto.randomBytes(16).toString("hex");
}

function trim(val) {
  return val && typeof val === "string" ? val.trim() : "";
}

function send(ws, message) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
  }
}

function setSocketState(ws, participantId, sessionId) {
  var data = wsData.get(ws);
  if (!data) return;
  data.participantId = participantId;
  data.sessionId = sessionId;
}

function broadcast(session, message, excludeId) {
  session.participants.forEach(function(participant, id) {
    if (id !== excludeId) {
      send(participant.ws, message);
    }
  });
}

function getParticipantList(session) {
  var list = [];
  session.participants.forEach(function(participant) {
    list.push({
      id: participant.id,
      displayName: participant.displayName,
      isHost: participant.isHost,
    });
  });
  return list;
}

function clearParticipantResume(session, participant) {
  if (!participant) return;
  if (participant.resumeToken) {
    session.resumeIndex.delete(participant.resumeToken);
  }
  if (participant.clientId) {
    session.clientIndex.delete(participant.clientId);
  }
}

function attachSocketToParticipant(ws, session, participant) {
  var oldWs = participant.ws;
  if (oldWs && oldWs !== ws) {
    var oldData = wsData.get(oldWs);
    if (oldData) {
      oldData.sessionId = null;
    }
    try {
      oldWs.close(4001, "Replaced by resume");
    } catch (e) {
      // Best effort cleanup for stale sockets.
    }
  }

  participant.ws = ws;
  participant.connected = true;
  participant.disconnectedAt = null;

  session.participants.set(participant.id, participant);
  session.disconnectedParticipants.delete(participant.id);
  session.resumeIndex.set(participant.resumeToken, participant.id);
  session.clientIndex.set(participant.clientId, participant.id);

  setSocketState(ws, participant.id, session.id);
}

function buildSessionPayload(session, participant) {
  return {
    type: "SESSION_JOINED",
    sessionId: session.id,
    code: session.code,
    isHost: participant.isHost,
    participants: getParticipantList(session),
    playbackState: session.playbackState,
    queue: session.queue,
    guestControlEnabled: session.guestControlEnabled,
    selfParticipantId: participant.id,
    resumeToken: participant.resumeToken,
  };
}

function cancelHostGraceTimeout(session) {
  if (session.hostGraceTimeout) {
    clearTimeout(session.hostGraceTimeout);
    session.hostGraceTimeout = null;
  }
}

function endSession(session, reason) {
  cancelHostGraceTimeout(session);
  broadcast(session, { type: "SESSION_ENDED", reason: reason });
  codeToSessionId.delete(session.code);
  sessions.delete(session.id);
  console.log("Session " + session.code + " ended (" + reason + ")");
}

function scheduleHostGraceTimeout(session) {
  if (session.hostGraceTimeout) return;

  session.hostGraceTimeout = setTimeout(function() {
    session.hostGraceTimeout = null;
    var host = session.host;
    if (host && !host.connected) {
      endSession(session, "host_disconnected_timeout");
    }
  }, HOST_GRACE_PERIOD_MS);

  console.log("Session " + session.code + ": waiting " + HOST_GRACE_PERIOD_MS + "ms for host resume");
}

function removeParticipant(session, participantId, options) {
  options = options || {};

  var participant = session.participants.get(participantId) || session.disconnectedParticipants.get(participantId);
  if (!participant) return;

  if (participant.isHost && !options.intentional) {
    participant.connected = false;
    participant.disconnectedAt = Date.now();
    participant.ws = null;
    session.participants.set(participant.id, participant);
    scheduleHostGraceTimeout(session);
    console.log("Host temporarily disconnected from session " + session.code);
    return;
  }

  if (participant.isHost) {
    endSession(session, "host_left");
    return;
  }

  session.participants.delete(participant.id);
  participant.connected = false;
  participant.disconnectedAt = Date.now();
  participant.ws = null;

  if (options.allowResume) {
    session.disconnectedParticipants.set(participant.id, participant);
  } else {
    clearParticipantResume(session, participant);
    session.disconnectedParticipants.delete(participant.id);
  }

  if (session.participants.size === 0) {
    endSession(session, "empty");
    return;
  }

  broadcast(session, {
    type: "PARTICIPANT_LEFT",
    participantId: participant.id,
    participants: getParticipantList(session),
  });
}

function findResumableParticipant(session, clientId, resumeToken) {
  if (!resumeToken) return null;

  var participantId = session.resumeIndex.get(resumeToken);
  if (!participantId) return null;

  var participant = session.participants.get(participantId) || session.disconnectedParticipants.get(participantId);
  if (!participant) return null;
  if (participant.clientId !== clientId) return null;

  return participant;
}

// ─── Cleanup stale sessions (every 5 minutes) ────────────────────────────────

setInterval(function() {
  var now = Date.now();
  var maxAge = 12 * 60 * 60 * 1000;

  sessions.forEach(function(session, id) {
    if (now - session.createdAt > maxAge) {
      endSession(session, "expired");
    }
  });
}, 5 * 60 * 1000);

// ─── Message Handlers ────────────────────────────────────────────────────────

function handleMessage(ws, raw) {
  var msg;
  try {
    msg = JSON.parse(raw);
  } catch (e) {
    send(ws, { type: "ERROR", message: "Invalid JSON" });
    return;
  }

  var data = wsData.get(ws);
  if (!data) return;

  switch (msg.type) {
    case "CREATE": {
      var displayName = trim(msg.displayName) || "Host";
      var clientId = trim(msg.clientId) || generateId();
      var participantId = generateId();
      var resumeToken = generateId();
      var sessionId = generateId();
      var code = generateCode();

      var participant = {
        id: participantId,
        clientId: clientId,
        resumeToken: resumeToken,
        displayName: displayName,
        isHost: true,
        ws: ws,
        connected: true,
        disconnectedAt: null,
      };

      var session = {
        id: sessionId,
        code: code,
        host: participant,
        participants: new Map(),
        disconnectedParticipants: new Map(),
        resumeIndex: new Map(),
        clientIndex: new Map(),
        playbackState: null,
        queue: [],
        createdAt: Date.now(),
        guestControlEnabled: false,
        hostGraceTimeout: null,
      };

      attachSocketToParticipant(ws, session, participant);
      sessions.set(sessionId, session);
      codeToSessionId.set(code, sessionId);

      send(ws, {
        type: "SESSION_CREATED",
        sessionId: sessionId,
        code: code,
        participants: getParticipantList(session),
        selfParticipantId: participant.id,
        resumeToken: participant.resumeToken,
        isHost: true,
      });

      console.log("Session created: " + code + " by " + displayName);
      break;
    }

    case "JOIN": {
      var code = trim(msg.code).toUpperCase();
      var displayName = trim(msg.displayName) || "Guest";
      var clientId = trim(msg.clientId);
      var resumeToken = trim(msg.resumeToken);

      if (!code) {
        send(ws, { type: "ERROR", message: "Code is required" });
        return;
      }

      var sessionId = codeToSessionId.get(code);
      if (!sessionId) {
        send(ws, { type: "ERROR", message: "Session not found" });
        return;
      }

      var session = sessions.get(sessionId);
      if (!session) {
        send(ws, { type: "ERROR", message: "Session not found" });
        return;
      }

      if (resumeToken) {
        var resumableParticipant = findResumableParticipant(session, clientId, resumeToken);
        if (!resumableParticipant) {
          send(ws, { type: "ERROR", message: "Resume rejected" });
          return;
        }

        var wasDisconnected = session.disconnectedParticipants.has(resumableParticipant.id);
        if (displayName) {
          resumableParticipant.displayName = displayName;
        }

        attachSocketToParticipant(ws, session, resumableParticipant);

        if (resumableParticipant.isHost) {
          cancelHostGraceTimeout(session);
          session.host = resumableParticipant;
        }

        send(ws, buildSessionPayload(session, resumableParticipant));

        if (wasDisconnected) {
          broadcast(session, {
            type: "PARTICIPANTS_UPDATED",
            participants: getParticipantList(session),
          }, resumableParticipant.id);
        }

        console.log(resumableParticipant.displayName + " resumed session " + code);
        return;
      }

      var participantId = generateId();
      var newResumeToken = generateId();
      var participant = {
        id: participantId,
        clientId: clientId || generateId(),
        resumeToken: newResumeToken,
        displayName: displayName,
        isHost: false,
        ws: ws,
        connected: true,
        disconnectedAt: null,
      };

      attachSocketToParticipant(ws, session, participant);

      send(ws, buildSessionPayload(session, participant));

      broadcast(session, {
        type: "PARTICIPANT_JOINED",
        participant: { id: participant.id, displayName: participant.displayName, isHost: false },
        participants: getParticipantList(session),
      }, participant.id);

      console.log(displayName + " joined session " + code);
      break;
    }

    case "LEAVE": {
      if (!data.sessionId || !data.participantId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      removeParticipant(session, data.participantId, {
        intentional: true,
        allowResume: false,
      });
      data.sessionId = null;
      send(ws, { type: "LEFT" });
      break;
    }

    case "UPDATE_SETTINGS": {
      if (!data.sessionId || !data.participantId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== data.participantId) {
        send(ws, { type: "ERROR", message: "Only the host can update settings" });
        return;
      }

      if (typeof msg.guestControlEnabled === "boolean") {
        session.guestControlEnabled = msg.guestControlEnabled;
        broadcast(session, {
          type: "SETTINGS_UPDATED",
          guestControlEnabled: session.guestControlEnabled,
        });
      }
      break;
    }

    case "PLAYBACK": {
      if (!data.sessionId || !data.participantId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== data.participantId && !session.guestControlEnabled) {
        send(ws, { type: "ERROR", message: "Only the host can control playback" });
        return;
      }

      session.playbackState = {
        songId: msg.songId,
        title: msg.title,
        artists: msg.artists,
        thumbnailUrl: msg.thumbnailUrl || null,
        duration: msg.duration || 0,
        position: msg.position || 0,
        isPlaying: msg.isPlaying !== undefined ? msg.isPlaying : true,
        timestamp: Date.now(),
      };

      var playbackMessage = Object.assign({ type: "PLAYBACK" }, session.playbackState);
      broadcast(session, playbackMessage, data.participantId);
      break;
    }

    case "QUEUE_ADD": {
      if (!data.sessionId || !data.participantId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      var participant = session.participants.get(data.participantId);
      if (!participant) return;

      var song = msg.song || {};
      var queueItem = {
        id: generateId(),
        songId: song.id || msg.songId,
        title: song.title || msg.title,
        artists: song.artists || msg.artists,
        thumbnailUrl: song.thumbnailUrl || msg.thumbnailUrl || null,
        duration: song.duration || msg.duration || 0,
        addedBy: participant.displayName,
      };

      session.queue.push(queueItem);
      broadcast(session, {
        type: "QUEUE_UPDATED",
        queue: session.queue,
        action: "added",
        item: queueItem,
      });
      break;
    }

    case "QUEUE_REMOVE": {
      if (!data.sessionId || !data.participantId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== data.participantId && !session.guestControlEnabled) {
        send(ws, { type: "ERROR", message: "Only the host can remove queue items" });
        return;
      }

      var index = msg.index;
      if (typeof index === "number" && index >= 0 && index < session.queue.length) {
        session.queue.splice(index, 1);
        broadcast(session, { type: "QUEUE_UPDATED", queue: session.queue, action: "removed" });
      }
      break;
    }

    case "QUEUE_REORDER": {
      if (!data.sessionId || !data.participantId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== data.participantId && !session.guestControlEnabled) {
        send(ws, { type: "ERROR", message: "Only the host can reorder the queue" });
        return;
      }

      var from = msg.from;
      var to = msg.to;
      if (typeof from === "number" && typeof to === "number" &&
          from >= 0 && from < session.queue.length &&
          to >= 0 && to < session.queue.length) {
        var item = session.queue.splice(from, 1)[0];
        session.queue.splice(to, 0, item);
        broadcast(session, { type: "QUEUE_UPDATED", queue: session.queue, action: "reordered" });
      }
      break;
    }

    case "PING":
      send(ws, { type: "PONG" });
      break;

    default:
      send(ws, { type: "ERROR", message: "Unknown message type: " + msg.type });
  }
}

// ─── HTTP + WebSocket Server ──────────────────────────────────────────────────

var PORT = parseInt(process.env.PORT || "8080", 10);

var httpServer = http.createServer(function(req, res) {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "ok", sessions: sessions.size, uptime: process.uptime() }));
    return;
  }

  res.writeHead(200);
  res.end("OuterTune Jam Server");
});

var wss = new WebSocketServer({ server: httpServer, path: "/ws" });

wss.on("connection", function(ws) {
  var connectionId = generateId();
  wsData.set(ws, {
    connectionId: connectionId,
    participantId: null,
    sessionId: null,
  });

  console.log("Client connected: " + connectionId);

  ws.on("message", function(data) {
    handleMessage(ws, data.toString());
  });

  ws.on("close", function(code, reason) {
    var data = wsData.get(ws) || {};
    var reasonText = reason && reason.length ? reason.toString() : "";
    var id = data.participantId || data.connectionId || connectionId;
    console.log("Client disconnected: " + id + " (" + code + (reasonText ? ", " + reasonText : "") + ")");

    if (data.sessionId && data.participantId) {
      var session = sessions.get(data.sessionId);
      if (session) {
        removeParticipant(session, data.participantId, {
          intentional: false,
          allowResume: true,
        });
      }
      data.sessionId = null;
    }
  });

  ws.on("error", function(err) {
    var data = wsData.get(ws) || {};
    var id = data.participantId || data.connectionId || connectionId;
    console.error("WebSocket error for " + id + ": " + err.message);
  });
});

httpServer.listen(PORT, function() {
  console.log("🎵 OuterTune Jam Server running on port " + PORT);
  console.log("   WebSocket: ws://localhost:" + PORT + "/ws");
  console.log("   Health:    http://localhost:" + PORT + "/health");
});

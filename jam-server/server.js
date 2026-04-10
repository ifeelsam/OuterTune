/**
 * OuterTune Jam Server
 * Compatible with Node.js 10+ — no optional chaining or nullish coalescing.
 */

var WebSocket = require("ws").WebSocket;
var WebSocketServer = require("ws").WebSocketServer;
var http = require("http");
var crypto = require("crypto");

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

function send(ws, message) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
  }
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
  session.participants.forEach(function(p) {
    list.push({ id: p.id, displayName: p.displayName, isHost: p.isHost });
  });
  return list;
}

function removeParticipant(session, participantId) {
  var participant = session.participants.get(participantId);
  if (!participant) return;

  session.participants.delete(participantId);

  if (participant.isHost || session.participants.size === 0) {
    var reason = participant.isHost ? "host_left" : "empty";
    broadcast(session, { type: "SESSION_ENDED", reason: reason });
    codeToSessionId.delete(session.code);
    sessions.delete(session.id);
    console.log("Session " + session.code + " ended (" + reason + ")");
  } else {
    broadcast(session, {
      type: "PARTICIPANT_LEFT",
      participantId: participantId,
      participants: getParticipantList(session),
    });
  }
}

function trim(val) {
  return val && typeof val === "string" ? val.trim() : "";
}

// ─── Cleanup stale sessions (every 5 minutes) ────────────────────────────────

setInterval(function() {
  var now = Date.now();
  var maxAge = 12 * 60 * 60 * 1000;
  sessions.forEach(function(session, id) {
    if (now - session.createdAt > maxAge) {
      broadcast(session, { type: "SESSION_ENDED", reason: "expired" });
      codeToSessionId.delete(session.code);
      sessions.delete(id);
      console.log("Session " + session.code + " expired");
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
  var participantId = data.participantId;

  switch (msg.type) {
    case "CREATE": {
      var displayName = trim(msg.displayName) || "Host";
      var sessionId = generateId();
      var code = generateCode();

      var participant = { id: participantId, displayName: displayName, isHost: true, ws: ws };
      var session = {
        id: sessionId,
        code: code,
        host: participant,
        participants: new Map([[participantId, participant]]),
        playbackState: null,
        queue: [],
        createdAt: Date.now(),
        guestControlEnabled: false,
      };

      sessions.set(sessionId, session);
      codeToSessionId.set(code, sessionId);
      data.sessionId = sessionId;

      send(ws, { type: "SESSION_CREATED", sessionId: sessionId, code: code, participants: getParticipantList(session) });
      console.log("Session created: " + code + " by " + displayName);
      break;
    }

    case "JOIN": {
      var code = trim(msg.code).toUpperCase();
      var displayName = trim(msg.displayName) || "Guest";

      if (!code) { send(ws, { type: "ERROR", message: "Code is required" }); return; }

      var sessionId = codeToSessionId.get(code);
      if (!sessionId) { send(ws, { type: "ERROR", message: "Session not found" }); return; }

      var session = sessions.get(sessionId);
      if (!session) { send(ws, { type: "ERROR", message: "Session not found" }); return; }

      var participant = { id: participantId, displayName: displayName, isHost: false, ws: ws };
      session.participants.set(participantId, participant);
      data.sessionId = sessionId;

      send(ws, {
        type: "SESSION_JOINED",
        sessionId: sessionId,
        code: session.code,
        isHost: false,
        participants: getParticipantList(session),
        playbackState: session.playbackState,
        queue: session.queue,
        guestControlEnabled: session.guestControlEnabled,
      });

      broadcast(session, {
        type: "PARTICIPANT_JOINED",
        participant: { id: participantId, displayName: displayName, isHost: false },
        participants: getParticipantList(session),
      }, participantId);

      console.log(displayName + " joined session " + code);
      break;
    }

    case "LEAVE": {
      if (!data.sessionId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;
      removeParticipant(session, participantId);
      data.sessionId = null;
      send(ws, { type: "LEFT" });
      break;
    }

    case "UPDATE_SETTINGS": {
      if (!data.sessionId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== participantId) {
        send(ws, { type: "ERROR", message: "Only the host can update settings" });
        return;
      }

      if (typeof msg.guestControlEnabled === "boolean") {
        session.guestControlEnabled = msg.guestControlEnabled;
        broadcast(session, { type: "SETTINGS_UPDATED", guestControlEnabled: session.guestControlEnabled });
        console.log("Session " + session.code + ": guestControlEnabled = " + session.guestControlEnabled);
      }
      break;
    }

    case "PLAYBACK": {
      if (!data.sessionId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== participantId && !session.guestControlEnabled) {
        send(ws, { type: "ERROR", message: "Only the host can control playback" });
        return;
      }

      var playbackState = {
        songId: msg.songId,
        title: msg.title,
        artists: msg.artists,
        thumbnailUrl: msg.thumbnailUrl || null,
        duration: msg.duration || 0,
        position: msg.position || 0,
        isPlaying: msg.isPlaying !== undefined ? msg.isPlaying : true,
        timestamp: Date.now(),
      };

      session.playbackState = playbackState;
      var broadcastMsg = Object.assign({ type: "PLAYBACK" }, playbackState);
      broadcast(session, broadcastMsg, participantId);
      break;
    }

    case "QUEUE_ADD": {
      if (!data.sessionId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      var participant = session.participants.get(participantId);
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
      broadcast(session, { type: "QUEUE_UPDATED", queue: session.queue, action: "added", item: queueItem });
      break;
    }

    case "QUEUE_REMOVE": {
      if (!data.sessionId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== participantId && !session.guestControlEnabled) {
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
      if (!data.sessionId) return;
      var session = sessions.get(data.sessionId);
      if (!session) return;

      if (session.host.id !== participantId && !session.guestControlEnabled) {
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

var PORT = parseInt(process.env.PORT || "8080");

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
  var participantId = generateId();
  wsData.set(ws, { participantId: participantId, sessionId: null });
  console.log("Client connected: " + participantId);

  ws.on("message", function(data) {
    handleMessage(ws, data.toString());
  });

  ws.on("close", function() {
    console.log("Client disconnected: " + participantId);
    var data = wsData.get(ws);
    if (data && data.sessionId) {
      var session = sessions.get(data.sessionId);
      if (session) removeParticipant(session, participantId);
    }
  });

  ws.on("error", function(err) {
    console.error("WebSocket error for " + participantId + ": " + err.message);
  });
});

httpServer.listen(PORT, function() {
  console.log("🎵 OuterTune Jam Server running on port " + PORT);
  console.log("   WebSocket: ws://localhost:" + PORT + "/ws");
  console.log("   Health:    http://localhost:" + PORT + "/health");
});

/**
 * OuterTune Jam Server
 *
 * A lightweight WebSocket server for real-time collaborative listening.
 * Uses Bun's built-in WebSocket support — no external dependencies.
 */

// ─── Types ───────────────────────────────────────────────────────────────────

interface Participant {
  id: string;
  displayName: string;
  isHost: boolean;
  ws: ServerWebSocket<WsData>;
}

interface JamSession {
  id: string;
  code: string;
  host: Participant;
  participants: Map<string, Participant>;
  playbackState: PlaybackState | null;
  queue: QueueItem[];
  createdAt: number;
}

interface PlaybackState {
  songId: string;
  title: string;
  artists: string;
  thumbnailUrl: string | null;
  duration: number;
  position: number;
  isPlaying: boolean;
  timestamp: number; // server timestamp for drift correction
}

interface QueueItem {
  id: string;
  songId: string;
  title: string;
  artists: string;
  thumbnailUrl: string | null;
  duration: number;
  addedBy: string; // participant display name
}

interface WsData {
  participantId: string;
  sessionId: string | null;
}

// ─── State ───────────────────────────────────────────────────────────────────

const sessions = new Map<string, JamSession>();
const codeToSessionId = new Map<string, string>();

// ─── Helpers ─────────────────────────────────────────────────────────────────

function generateCode(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous chars
  let code: string;
  do {
    code = Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join("");
  } while (codeToSessionId.has(code));
  return code;
}

function generateId(): string {
  return crypto.randomUUID();
}

function broadcast(session: JamSession, message: object, excludeId?: string) {
  const data = JSON.stringify(message);
  for (const [id, participant] of session.participants) {
    if (id !== excludeId) {
      participant.ws.send(data);
    }
  }
}

function getParticipantList(session: JamSession) {
  return Array.from(session.participants.values()).map(p => ({
    id: p.id,
    displayName: p.displayName,
    isHost: p.isHost,
  }));
}

function removeParticipant(session: JamSession, participantId: string) {
  const participant = session.participants.get(participantId);
  if (!participant) return;

  session.participants.delete(participantId);

  if (participant.isHost || session.participants.size === 0) {
    // Host left or session empty — end session
    broadcast(session, { type: "SESSION_ENDED", reason: participant.isHost ? "host_left" : "empty" });
    codeToSessionId.delete(session.code);
    sessions.delete(session.id);
    console.log(`Session ${session.code} ended (${participant.isHost ? "host left" : "empty"})`);
  } else {
    broadcast(session, {
      type: "PARTICIPANT_LEFT",
      participantId,
      participants: getParticipantList(session),
    });
  }
}

// ─── Cleanup stale sessions (every 5 minutes) ───────────────────────────────

setInterval(() => {
  const now = Date.now();
  const maxAge = 12 * 60 * 60 * 1000; // 12 hours
  for (const [id, session] of sessions) {
    if (now - session.createdAt > maxAge) {
      broadcast(session, { type: "SESSION_ENDED", reason: "expired" });
      codeToSessionId.delete(session.code);
      sessions.delete(id);
      console.log(`Session ${session.code} expired`);
    }
  }
}, 5 * 60 * 1000);

// ─── Message Handlers ───────────────────────────────────────────────────────

function handleMessage(ws: ServerWebSocket<WsData>, raw: string) {
  let msg: any;
  try {
    msg = JSON.parse(raw);
  } catch {
    ws.send(JSON.stringify({ type: "ERROR", message: "Invalid JSON" }));
    return;
  }

  const { participantId } = ws.data;

  switch (msg.type) {
    case "CREATE": {
      const displayName = msg.displayName?.trim() || "Host";
      const sessionId = generateId();
      const code = generateCode();

      const participant: Participant = {
        id: participantId,
        displayName,
        isHost: true,
        ws,
      };

      const session: JamSession = {
        id: sessionId,
        code,
        host: participant,
        participants: new Map([[participantId, participant]]),
        playbackState: null,
        queue: [],
        createdAt: Date.now(),
      };

      sessions.set(sessionId, session);
      codeToSessionId.set(code, sessionId);
      ws.data.sessionId = sessionId;

      ws.send(JSON.stringify({
        type: "SESSION_CREATED",
        sessionId,
        code,
        participants: getParticipantList(session),
      }));

      console.log(`Session created: ${code} by ${displayName}`);
      break;
    }

    case "JOIN": {
      const code = msg.code?.trim()?.toUpperCase();
      const displayName = msg.displayName?.trim() || "Guest";

      if (!code) {
        ws.send(JSON.stringify({ type: "ERROR", message: "Code is required" }));
        return;
      }

      const sessionId = codeToSessionId.get(code);
      if (!sessionId) {
        ws.send(JSON.stringify({ type: "ERROR", message: "Session not found" }));
        return;
      }

      const session = sessions.get(sessionId);
      if (!session) {
        ws.send(JSON.stringify({ type: "ERROR", message: "Session not found" }));
        return;
      }

      const participant: Participant = {
        id: participantId,
        displayName,
        isHost: false,
        ws,
      };

      session.participants.set(participantId, participant);
      ws.data.sessionId = sessionId;

      // Send full state to the joining participant
      ws.send(JSON.stringify({
        type: "SESSION_JOINED",
        sessionId,
        code: session.code,
        isHost: false,
        participants: getParticipantList(session),
        playbackState: session.playbackState,
        queue: session.queue,
      }));

      // Notify others
      broadcast(session, {
        type: "PARTICIPANT_JOINED",
        participant: { id: participantId, displayName, isHost: false },
        participants: getParticipantList(session),
      }, participantId);

      console.log(`${displayName} joined session ${code}`);
      break;
    }

    case "LEAVE": {
      const sessionId = ws.data.sessionId;
      if (!sessionId) return;

      const session = sessions.get(sessionId);
      if (!session) return;

      removeParticipant(session, participantId);
      ws.data.sessionId = null;
      ws.send(JSON.stringify({ type: "LEFT" }));
      break;
    }

    case "PLAYBACK": {
      const sessionId = ws.data.sessionId;
      if (!sessionId) return;

      const session = sessions.get(sessionId);
      if (!session) return;

      // Only host can update playback state
      if (session.host.id !== participantId) {
        ws.send(JSON.stringify({ type: "ERROR", message: "Only the host can control playback" }));
        return;
      }

      const playbackState: PlaybackState = {
        songId: msg.songId,
        title: msg.title,
        artists: msg.artists,
        thumbnailUrl: msg.thumbnailUrl || null,
        duration: msg.duration || 0,
        position: msg.position || 0,
        isPlaying: msg.isPlaying ?? true,
        timestamp: Date.now(),
      };

      session.playbackState = playbackState;

      broadcast(session, {
        type: "PLAYBACK",
        ...playbackState,
      }, participantId);

      break;
    }

    case "QUEUE_ADD": {
      const sessionId = ws.data.sessionId;
      if (!sessionId) return;

      const session = sessions.get(sessionId);
      if (!session) return;

      const participant = session.participants.get(participantId);
      if (!participant) return;

      const queueItem: QueueItem = {
        id: generateId(),
        songId: msg.song?.id || msg.songId,
        title: msg.song?.title || msg.title,
        artists: msg.song?.artists || msg.artists,
        thumbnailUrl: msg.song?.thumbnailUrl || msg.thumbnailUrl || null,
        duration: msg.song?.duration || msg.duration || 0,
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
      const sessionId = ws.data.sessionId;
      if (!sessionId) return;

      const session = sessions.get(sessionId);
      if (!session) return;

      // Only host can remove from queue
      if (session.host.id !== participantId) {
        ws.send(JSON.stringify({ type: "ERROR", message: "Only the host can remove queue items" }));
        return;
      }

      const index = msg.index;
      if (typeof index === "number" && index >= 0 && index < session.queue.length) {
        session.queue.splice(index, 1);
        broadcast(session, {
          type: "QUEUE_UPDATED",
          queue: session.queue,
          action: "removed",
        });
      }

      break;
    }

    case "QUEUE_REORDER": {
      const sessionId = ws.data.sessionId;
      if (!sessionId) return;

      const session = sessions.get(sessionId);
      if (!session) return;

      if (session.host.id !== participantId) {
        ws.send(JSON.stringify({ type: "ERROR", message: "Only the host can reorder the queue" }));
        return;
      }

      const { from, to } = msg;
      if (typeof from === "number" && typeof to === "number" &&
          from >= 0 && from < session.queue.length &&
          to >= 0 && to < session.queue.length) {
        const [item] = session.queue.splice(from, 1);
        session.queue.splice(to, 0, item);
        broadcast(session, {
          type: "QUEUE_UPDATED",
          queue: session.queue,
          action: "reordered",
        });
      }

      break;
    }

    case "PING": {
      ws.send(JSON.stringify({ type: "PONG" }));
      break;
    }

    default:
      ws.send(JSON.stringify({ type: "ERROR", message: `Unknown message type: ${msg.type}` }));
  }
}

// ─── Server ──────────────────────────────────────────────────────────────────

const PORT = parseInt(process.env.PORT || "8080");

const server = Bun.serve({
  port: PORT,
  fetch(req, server) {
    const url = new URL(req.url);

    // Health check endpoint
    if (url.pathname === "/health") {
      return new Response(JSON.stringify({
        status: "ok",
        sessions: sessions.size,
        uptime: process.uptime(),
      }), {
        headers: { "Content-Type": "application/json" },
      });
    }

    // WebSocket upgrade
    if (url.pathname === "/ws") {
      const success = server.upgrade(req, {
        data: {
          participantId: generateId(),
          sessionId: null,
        } as WsData,
      });
      if (success) return undefined;
      return new Response("WebSocket upgrade failed", { status: 400 });
    }

    return new Response("OuterTune Jam Server", { status: 200 });
  },
  websocket: {
    open(ws: ServerWebSocket<WsData>) {
      console.log(`Client connected: ${ws.data.participantId}`);
    },
    message(ws: ServerWebSocket<WsData>, message: string | Buffer) {
      handleMessage(ws, typeof message === "string" ? message : message.toString());
    },
    close(ws: ServerWebSocket<WsData>) {
      console.log(`Client disconnected: ${ws.data.participantId}`);
      const { sessionId, participantId } = ws.data;
      if (sessionId) {
        const session = sessions.get(sessionId);
        if (session) {
          removeParticipant(session, participantId);
        }
      }
    },
  },
});

console.log(`🎵 OuterTune Jam Server running on port ${PORT}`);
console.log(`   WebSocket: ws://localhost:${PORT}/ws`);
console.log(`   Health:    http://localhost:${PORT}/health`);

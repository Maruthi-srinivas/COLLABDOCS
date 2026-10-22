import { api } from "../api/client.js";
import { decode, encode, planWelcome } from "./protocol.js";
import * as Y from "yjs";

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("collabdocs", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("docs");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function writeQueue(documentId, value) {
  const db = await openDb();
  await new Promise((resolve, reject) => {
    const tx = db.transaction("docs", "readwrite");
    tx.objectStore("docs").put(value, documentId);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

async function readQueue(documentId) {
  const db = await openDb();
  const value = await new Promise((resolve, reject) => {
    const tx = db.transaction("docs", "readonly");
    const request = tx.objectStore("docs").get(documentId);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  db.close();
  return value;
}

export function connectDocument({ documentId, clientId, ydoc, getToken, intent, onStatus, onPresence, onReady, onRevoked, onEpoch, onComment }) {
  let socket = null;
  let epoch = null;
  const applied = new Set();
  let attempt = 0;
  let stopped = false;
  let opened = false;
  const outbound = [];
  let timer = null;
  let paused = false;
  let skipFlush = false;

  const flushHttp = async () => {
    const stored = await readQueue(documentId);
    const items = stored?.items || [];
    if (!items.length || epoch == null) return;
    onStatus(items.length ? "saving" : "saved");
    for (const item of items) {
      await api(`/api/v1/documents/${documentId}/content-updates`, {
        method: "POST",
        body: JSON.stringify({ clientId, updateId: item.updateId, epoch, intent: item.intent || intent, payloadBase64: item.payloadBase64 }),
      });
    }
    await writeQueue(documentId, { epoch, items: [] });
    outbound.length = 0;
    onStatus("saved");
  };

  const persist = () => writeQueue(documentId, {
    epoch,
    items: outbound.map((item) => ({ updateId: item.updateId, intent: item.intent, payloadBase64: encode(item.bytes) })),
  });

  const send = (frame) => {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(frame));
  };

  const applyRemote = (payloadBase64) => Y.applyUpdate(ydoc, decode(payloadBase64), "remote");

  const handle = (frame) => {
    if (frame.type === "welcome") {
      const plan = planWelcome(epoch, applied, frame.payload);
      if (plan.reset) {
        outbound.length = 0;
        skipFlush = true;
        writeQueue(documentId, { epoch: frame.payload.epoch, items: [] });
        onEpoch(frame.payload);
        return;
      }
      if (plan.applySnapshot && frame.payload.snapshotBase64) applyRemote(frame.payload.snapshotBase64);
      for (const update of plan.updates) {
        applyRemote(update.payloadBase64);
        applied.add(update.seq);
      }
      epoch = frame.payload.epoch;
      opened = true;
      onReady({ role: frame.payload.role, epoch });
      for (const item of outbound) {
        send({ type: "sync.update", payload: { updateId: item.updateId, epoch, intent: item.intent || intent, payloadBase64: encode(item.bytes) } });
      }
      onStatus(outbound.length ? "saving" : "saved");
    } else if (frame.type === "sync.apply") {
      if (!applied.has(frame.payload.seq)) {
        applyRemote(frame.payload.payloadBase64);
        applied.add(frame.payload.seq);
      }
    } else if (frame.type === "sync.ack") {
      const index = outbound.findIndex((item) => item.updateId === frame.payload.updateId);
      if (index >= 0) outbound.splice(index, 1);
      applied.add(frame.payload.seq);
      persist();
      onStatus(outbound.length ? "saving" : "saved");
    } else if (frame.type === "sync.nack") {
      onStatus("error");
    } else if (frame.type === "awareness") {
      onPresence(frame.payload);
    } else if (frame.type === "acl.revoked") {
      onRevoked();
    } else if (frame.type === "comment.changed") {
      onComment?.();
    } else if (frame.type === "document.restored") {
      outbound.length = 0;
      skipFlush = true;
      writeQueue(documentId, { epoch: frame.payload.epoch, items: [] });
      onEpoch(frame.payload);
    } else if (frame.type === "error" && frame.payload?.code === "FORBIDDEN") {
      onStatus("error");
    }
  };

  const connect = () => {
    if (stopped) return;
    const token = getToken();
    if (!token) return;
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    socket = new WebSocket(`${protocol}://${window.location.host}/collab/v1`, "collabdocs.v1");
    socket.onopen = () => {
      attempt = 0;
      const last = applied.size ? Math.max(...applied) : 0;
      send({
        type: "auth",
        payload: {
          accessToken: token, documentId, clientId, epoch: epoch || 1, lastAckSeq: last,
          intent, protocolVersion: 1,
        },
      });
    };
    socket.onmessage = (event) => handle(JSON.parse(event.data));
    socket.onclose = (event) => {
      if (event.code === 4403) {
        onRevoked();
        return;
      }
      if (!stopped) {
        if (!skipFlush && outbound.length) flushHttp().catch(() => onStatus("error"));
        const delay = Math.min(10000, 500 * (2 ** attempt));
        attempt += 1;
        timer = setTimeout(connect, delay);
      }
    };
  };

  const onUpdate = (update, origin) => {
    if (origin === "remote" || origin === "resolve" || paused) return;
    if (intent !== "edit" && intent !== "suggest") return;
    if (outbound.length >= 100) {
      onStatus("error");
      return;
    }
    outbound.push({ updateId: cryptoId(), bytes: update, intent });
    onStatus("saving");
    persist();
    if (opened && epoch != null) {
      const item = outbound[outbound.length - 1];
      send({ type: "sync.update", payload: { updateId: item.updateId, epoch, intent: item.intent, payloadBase64: encode(item.bytes) } });
    }
  };
  ydoc.on("update", onUpdate);
  connect();
  const ping = setInterval(() => send({ type: "ping", payload: { ts: Date.now() } }), 15000);

  return {
    awareness(anchor, head, displayName, color) {
      send({ type: "awareness", payload: { anchor, head, displayName, color } });
    },
    pause() { paused = true; },
    resume() { paused = false; },
    close() {
      stopped = true;
      clearInterval(ping);
      clearTimeout(timer);
      ydoc.off("update", onUpdate);
      if (socket) socket.close(1000, "bye");
    },
  };
}

function cryptoId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const api = process.env.API_URL || "http://api:8080";
const headers = { "content-type": "application/json", "X-CollabDocs-Request": "1" };

async function json(path, options = {}) {
  const response = await fetch(api + path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`${path} ${response.status} ${text}`);
  return body;
}

async function account(name) {
  const email = `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}@example.com`.toLowerCase();
  const created = await json("/api/v1/auth/register", { method: "POST", body: JSON.stringify({ email, displayName: name, password: "correct-horse-1" }) });
  return { email, access: created.accessToken, workspaceId: created.workspace.id };
}

function socket(url, access, documentId) {
  const clientId = crypto.randomUUID();
  const ws = new WebSocket(url, "collabdocs.v1");
  const frames = [];
  let waiter = null;
  ws.onmessage = (event) => {
    const frame = JSON.parse(event.data);
    frames.push(frame);
    if (waiter && waiter.type === frame.type) waiter.resolve(frame);
  };
  const closed = new Promise((resolve) => { ws.onclose = (event) => resolve(event.code); });
  const ready = new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = () => reject(new Error(`socket failed ${url}`));
  });
  return {
    ws, frames, closed, ready,
    wait(type) {
      const existing = frames.find((frame) => frame.type === type);
      if (existing) return Promise.resolve(existing);
      return new Promise((resolve) => { waiter = { type, resolve }; });
    },
    auth() {
      ws.send(JSON.stringify({ type: "auth", payload: { accessToken: access, documentId, clientId, epoch: 1, lastAckSeq: 0, intent: "edit", protocolVersion: 1 } }));
    },
  };
}

const owner = await account("Owner");
const editor = await account("Editor");
const document = await json(`/api/v1/workspaces/${owner.workspaceId}/documents`, {
  method: "POST", headers: { Authorization: `Bearer ${owner.access}` }, body: JSON.stringify({ title: "Two replicas" }),
});
await json(`/api/v1/documents/${document.id}/permissions`, {
  method: "POST", headers: { Authorization: `Bearer ${owner.access}` }, body: JSON.stringify({ email: editor.email, role: "editor" }),
});

const left = socket("ws://collab:8081/collab/v1", owner.access, document.id);
const right = socket("ws://collab-b:8081/collab/v1", editor.access, document.id);
await left.ready;
await right.ready;
left.auth();
right.auth();
await left.wait("welcome");
await right.wait("welcome");
const payload = "AQLZveD6DgAHALjv1IEGAAYEANm94PoOAAJIaQA=";
left.ws.send(JSON.stringify({ type: "sync.update", payload: { updateId: crypto.randomUUID(), epoch: 1, intent: "edit", payloadBase64: payload } }));
const ack = await left.wait("sync.ack");
const applied = await right.wait("sync.apply");
if (applied.payload.seq !== ack.payload.seq) throw new Error("replicas disagree on seq");
left.ws.close();
right.ws.close();

await json("/api/v1/auth/logout", { method: "POST", headers: { Authorization: `Bearer ${owner.access}` } });
const revoked = socket("ws://collab:8081/collab/v1", owner.access, document.id);
await revoked.ready;
revoked.auth();
const code = await Promise.race([
  revoked.closed,
  new Promise((_, reject) => setTimeout(() => reject(new Error("revoked socket stayed open")), 5000)),
]);
if (code !== 4401) throw new Error(`expected 4401, got ${code}`);
console.log("two-replica and revoked-session checks passed");
process.exit(0);

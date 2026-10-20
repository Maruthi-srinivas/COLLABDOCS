const api = process.env.API_URL || "http://api:8080";
const mailpit = process.env.MAILPIT_URL || "http://mailpit:8025";
const compactor = process.env.COMPACTOR_URL || "http://compactor:8083";
const token = process.env.INTERNAL_TOKEN || "change-me-internal";

const headers = { "content-type": "application/json", "X-CollabDocs-Request": "1" };

async function docker(path, method) {
  const http = await import("node:http");
  return new Promise((resolve, reject) => {
    const req = http.request({ socketPath: "/var/run/docker.sock", path, method }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
    });
    req.on("error", reject);
    req.end();
  });
}

async function restartGateway(name) {
  const restarted = await docker(`/containers/${name}/restart`, "POST");
  if (restarted.status < 200 || restarted.status >= 300) throw new Error(`restart ${restarted.status}`);
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const inspect = await docker(`/containers/${name}/json`, "GET");
    const state = JSON.parse(inspect.body).State;
    if (state?.Health?.Status === "healthy") return;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error("gateway did not become ready");
}

async function json(path, options = {}) {
  const response = await fetch(api + path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`${path} ${response.status} ${text}`);
  return body;
}

async function mailToken(email) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const list = await fetch(`${mailpit}/api/v1/messages`).then((response) => response.json());
    const message = (list.messages || []).find((item) => (item.To || []).some((to) => to.Address === email) || (item.Snippet || "").includes(email));
    if (message) {
      const detail = await fetch(`${mailpit}/api/v1/message/${message.ID}`).then((response) => response.json());
      const match = (detail.Text || detail.HTML || "").match(/token=([A-Za-z0-9_-]+)/);
      if (match) return match[1];
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error("mail missing");
}

async function account(name) {
  const email = `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}@example.com`.toLowerCase();
  const created = await json("/api/v1/auth/register", { method: "POST", body: JSON.stringify({ email, displayName: name, password: "correct-horse-1" }) });
  await json("/api/v1/auth/verify-email", { method: "POST", body: JSON.stringify({ token: await mailToken(email) }) });
  const login = await json("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password: "correct-horse-1" }) });
  return { email, access: login.accessToken, userId: created.user.id, workspaceId: created.workspace.id };
}

function socket(url, access, documentId, intent) {
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
    ws.onerror = () => reject(new Error("socket failed"));
  });
  return {
    ws, frames, closed, ready, clientId,
    wait(type) {
      const existing = frames.find((frame) => frame.type === type);
      if (existing) return Promise.resolve(existing);
      return new Promise((resolve) => { waiter = { type, resolve }; });
    },
    auth(intentName = intent) {
      ws.send(JSON.stringify({ type: "auth", payload: { accessToken: access, documentId, clientId, epoch: 1, lastAckSeq: 0, intent: intentName, protocolVersion: 1 } }));
    },
  };
}

const owner = await account("Owner");
const editor = await account("Editor");
const viewer = await account("Viewer");
const document = await json(`/api/v1/workspaces/${owner.workspaceId}/documents`, {
  method: "POST", headers: { Authorization: `Bearer ${owner.access}` }, body: JSON.stringify({ title: "Live" }),
});
await json(`/api/v1/documents/${document.id}/permissions`, {
  method: "POST", headers: { Authorization: `Bearer ${owner.access}` }, body: JSON.stringify({ email: editor.email, role: "editor" }),
});
await json(`/api/v1/documents/${document.id}/permissions`, {
  method: "POST", headers: { Authorization: `Bearer ${owner.access}` }, body: JSON.stringify({ email: viewer.email, role: "viewer" }),
});

const left = socket("ws://collab:8081/collab/v1", owner.access, document.id, "edit");
const right = socket("ws://collab-b:8081/collab/v1", editor.access, document.id, "edit");
await left.ready;
await right.ready;
left.auth();
right.auth();
await left.wait("welcome");
await right.wait("welcome");
const updateId = crypto.randomUUID();
const payload = "AQLZveD6DgAHALjv1IEGAAYEANm94PoOAAJIaQA=";
left.ws.send(JSON.stringify({ type: "sync.update", payload: { updateId, epoch: 1, intent: "edit", payloadBase64: payload } }));
const ack = await left.wait("sync.ack");
if (ack.payload.duplicate) throw new Error("first ack was a duplicate");
const applied = await right.wait("sync.apply");
if (applied.payload.seq !== ack.payload.seq) throw new Error("replicas disagree on seq");
left.ws.send(JSON.stringify({ type: "sync.update", payload: { updateId, epoch: 1, intent: "edit", payloadBase64: payload } }));
const duplicate = left.frames.filter((frame) => frame.type === "sync.ack").at(-1);
await new Promise((resolve) => setTimeout(resolve, 300));
const acks = left.frames.filter((frame) => frame.type === "sync.ack");
if (!acks.some((frame) => frame.payload.duplicate)) throw new Error("duplicate was stored again");
if (right.frames.filter((frame) => frame.type === "sync.apply").length !== 1) throw new Error("duplicate was published");
const ackedSeq = ack.payload.seq;
await restartGateway("collabdocs-collab-1");
const resumed = socket("ws://collab:8081/collab/v1", owner.access, document.id, "edit");
await resumed.ready;
resumed.auth();
const afterRestart = await resumed.wait("welcome");
const tail = afterRestart.payload.updates || [];
if (afterRestart.payload.throughSeq < ackedSeq && !tail.some((item) => item.seq === ackedSeq)) {
  throw new Error("restart dropped the acked tail");
}

const watching = socket("ws://collab:8081/collab/v1", viewer.access, document.id, "view");
await watching.ready;
watching.auth("view");
await watching.wait("welcome");
watching.ws.send(JSON.stringify({ type: "sync.update", payload: { updateId: crypto.randomUUID(), epoch: 1, intent: "edit", payloadBase64: payload } }));
const nack = await watching.wait("sync.nack");
if (nack.payload.code !== "FORBIDDEN") throw new Error("viewer write was accepted");

const compacted = await fetch(`${compactor}/internal/compact`, {
  method: "POST",
  headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
  body: JSON.stringify({ documentId: document.id }),
}).then((response) => response.json());
if (!compacted.throughSeq) throw new Error("compaction did not advance");
const joined = socket("ws://collab-b:8081/collab/v1", owner.access, document.id, "edit");
await joined.ready;
joined.auth();
const welcome = await joined.wait("welcome");
if (welcome.payload.throughSeq < compacted.throughSeq) throw new Error("welcome missed the snapshot");

const grants = await json(`/api/v1/documents/${document.id}/permissions`, { headers: { Authorization: `Bearer ${owner.access}` } });
const editorGrant = grants.items.find((item) => item.userId === editor.userId && item.role === "editor");
await json(`/api/v1/documents/${document.id}/permissions/${editorGrant.id}`, { method: "DELETE", headers: { Authorization: `Bearer ${owner.access}` } });
const code = await right.closed;
if (code !== 4403) throw new Error(`expected 4403, got ${code}`);
console.log("gateway checks passed");

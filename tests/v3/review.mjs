const { default: pg } = await import("/deps/pg/lib/index.js");

const api = process.env.API_URL || "http://api:8080";
const headers = { "content-type": "application/json", "X-CollabDocs-Request": "1" };

async function json(path, options = {}) {
  const response = await fetch(api + path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  return { status: response.status, body };
}

async function ok(path, options) {
  const result = await json(path, options);
  if (result.status >= 300) throw new Error(`${path} ${result.status} ${JSON.stringify(result.body)}`);
  return result.body;
}

async function account(name) {
  const email = `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}@example.com`.toLowerCase();
  const created = await ok("/api/v1/auth/register", { method: "POST", body: JSON.stringify({ email, displayName: name, password: "correct-horse-1" }) });
  const login = await ok("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password: "correct-horse-1" }) });
  return { email, access: login.accessToken, userId: created.user.id, workspaceId: created.workspace.id };
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
  const ready = new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = () => reject(new Error("socket failed"));
  });
  return {
    ws, frames, ready, clientId,
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
const auth = { Authorization: `Bearer ${owner.access}` };
const document = await ok(`/api/v1/workspaces/${owner.workspaceId}/documents`, {
  method: "POST", headers: auth, body: JSON.stringify({ title: "Review" }),
});
await ok(`/api/v1/documents/${document.id}/permissions`, {
  method: "POST", headers: auth, body: JSON.stringify({ email: editor.email, role: "editor" }),
});
const editorAuth = { Authorization: `Bearer ${editor.access}` };
const anchor = Buffer.from("anchor-bytes").toString("base64");
const thread = await ok(`/api/v1/documents/${document.id}/threads`, {
  method: "POST", headers: editorAuth,
  body: JSON.stringify({ body: "Look here", quotedText: "Hello", anchorStartBase64: anchor, anchorEndBase64: anchor }),
});
const threads = await ok(`/api/v1/documents/${document.id}/threads`, { headers: auth });
if (!threads.items.some((item) => item.id === thread.id)) throw new Error("comment was not stored");
await ok(`/api/v1/threads/${thread.id}`, { method: "PATCH", headers: editorAuth, body: JSON.stringify({ anchorState: "detached" }) });
const detached = await ok(`/api/v1/documents/${document.id}/threads`, { headers: auth });
const stored = detached.items.find((item) => item.id === thread.id);
if (stored.anchorState !== "detached" || !stored.comments.some((comment) => comment.body === "Look here")) {
  throw new Error("detached thread was not readable");
}

const suggestionId = crypto.randomUUID();
const otherId = crypto.randomUUID();
await ok(`/api/v1/documents/${document.id}/suggestions`, {
  method: "POST", headers: editorAuth, body: JSON.stringify({ id: suggestionId, type: "insert" }),
});
await ok(`/api/v1/documents/${document.id}/suggestions`, {
  method: "POST", headers: editorAuth, body: JSON.stringify({ id: otherId, type: "delete" }),
});
const update = (id) => ({
  clientId: crypto.randomUUID(), updateId: crypto.randomUUID(), epoch: 1, intent: "edit", payloadBase64: "AQLZveD6DgAHALjv1IEGAAYEANm94PoOAAJIaQA=", suggestionId: id,
});
const first = update(suggestionId);
const second = update(suggestionId);
const [left, right] = await Promise.all([
  json(`/api/v1/documents/${document.id}/suggestions/${suggestionId}/accept`, { method: "POST", headers: auth, body: JSON.stringify(first) }),
  json(`/api/v1/documents/${document.id}/suggestions/${suggestionId}/accept`, { method: "POST", headers: auth, body: JSON.stringify(second) }),
]);
const statuses = [left.status, right.status].sort();
if (statuses[0] !== 200 || statuses[1] !== 409) throw new Error(`race statuses ${statuses}`);
const resolved = await ok(`/api/v1/documents/${document.id}/suggestions?status=accepted`, { headers: auth });
if (!resolved.items.some((item) => item.id === suggestionId)) throw new Error("accepted suggestion missing");

const named = await ok(`/api/v1/documents/${document.id}/versions`, {
  method: "POST", headers: auth, body: JSON.stringify({ label: "Checkpoint" }),
});
if (!named.id) throw new Error("version was not named");
const versionId = named.ready === false
  ? (await ok(`/api/v1/documents/${document.id}/versions`, { headers: auth })).items.find((item) => item.label === "Checkpoint").id
  : named.id;

const leftSocket = socket("ws://collab:8081/collab/v1", owner.access, document.id);
const rightSocket = socket("ws://collab-b:8081/collab/v1", editor.access, document.id);
await leftSocket.ready;
await rightSocket.ready;
leftSocket.auth();
rightSocket.auth();
await leftSocket.wait("welcome");
await rightSocket.wait("welcome");
const restored = await ok(`/api/v1/documents/${document.id}/versions/${versionId}/restore`, {
  method: "POST", headers: auth, body: JSON.stringify({ confirm: true }),
});
const ownerEvent = await leftSocket.wait("document.restored");
const editorEvent = await rightSocket.wait("document.restored");
if (ownerEvent.payload.epoch !== restored.epoch || editorEvent.payload.epoch !== restored.epoch) throw new Error("restore epoch mismatch");
leftSocket.ws.send(JSON.stringify({ type: "sync.update", payload: { updateId: crypto.randomUUID(), epoch: 1, intent: "edit", payloadBase64: "AQLZveD6DgAHALjv1IEGAAYEANm94PoOAAJIaQA=" } }));
const nack = await leftSocket.wait("sync.nack");
if (nack.payload.code !== "STALE_EPOCH") throw new Error(`old epoch was stored: ${nack.payload.code}`);
const joined = socket("ws://collab:8081/collab/v1", owner.access, document.id);
await joined.ready;
joined.ws.send(JSON.stringify({ type: "auth", payload: { accessToken: owner.access, documentId: document.id, clientId: joined.clientId, epoch: restored.epoch, lastAckSeq: 0, intent: "edit", protocolVersion: 1 } }));
const welcome = await joined.wait("welcome");
if (welcome.payload.epoch !== restored.epoch || welcome.payload.snapshotBase64 !== ownerEvent.payload.snapshotBase64) {
  throw new Error("welcome missed the restored snapshot");
}
const stale = await json(`/api/v1/documents/${document.id}/suggestions/${otherId}/accept`, {
  method: "POST", headers: auth, body: JSON.stringify({ ...update(otherId), epoch: 1 }),
});
if (stale.status !== 409) throw new Error(`stale accept status ${stale.status}`);
const pending = await ok(`/api/v1/documents/${document.id}/suggestions?status=pending`, { headers: auth });
if (!pending.items.some((item) => item.id === otherId)) throw new Error("stale accept changed the suggestion");

const pool = new pg.Pool({
  host: "postgres",
  port: 5432,
  database: process.env.POSTGRES_DB || "collabdocs",
  user: process.env.POSTGRES_USER,
  password: process.env.POSTGRES_PASSWORD,
});
const audit = await pool.query("SELECT count(*) FROM audit_events WHERE action = 'restore' AND target_id = $1", [document.id]);
await pool.end();
if (Number(audit.rows[0].count) < 1) throw new Error("restore audit row missing");
console.log("review checks passed");
process.exit(0);

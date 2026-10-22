import http from "node:http";
import pg from "pg";
import { applyTail, compactState, encodeCanonical, project } from "./compact.js";

const failures = new Map();
const token = process.env.INTERNAL_TOKEN || "";

function poolConfig() {
  const raw = process.env.DATABASE_URL || "";
  const match = raw.match(/jdbc:postgresql:\/\/([^:/]+):(\d+)\/([^?]+)/);
  return {
    host: match?.[1] || process.env.PGHOST || "postgres",
    port: Number(match?.[2] || process.env.PGPORT || 5432),
    database: match?.[3] || process.env.PGDATABASE || "collabdocs",
    user: process.env.POSTGRES_USER || "collabdocs",
    password: process.env.POSTGRES_PASSWORD || "change-me",
  };
}

const pool = new pg.Pool(poolConfig());

function authorized(req) {
  return (req.headers.authorization || "") === `Bearer ${token}`;
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString("utf8");
  return text ? JSON.parse(text) : {};
}

function fromBase64(value) {
  return Buffer.from(value || "", "base64");
}

export async function compactDocument(client, documentId, force) {
  const locked = await client.query(
    `SELECT e.document_id, e.epoch, e.last_seq, e.snapshot_through_seq, d.schema_version
     FROM document_epochs e
     JOIN documents d ON d.id = e.document_id AND d.epoch = e.epoch
     WHERE e.document_id = $1
     FOR UPDATE OF e`,
    [documentId],
  );
  if (!locked.rows.length) return null;
  const row = locked.rows[0];
  const lag = Number(row.last_seq) - Number(row.snapshot_through_seq);
  const snap = await client.query(
    `SELECT id, state, created_at FROM document_snapshots
     WHERE document_id = $1 AND epoch = $2 AND state IS NOT NULL
     ORDER BY through_seq DESC LIMIT 1`,
    [row.document_id, row.epoch],
  );
  const ageMs = snap.rows.length ? Date.now() - new Date(snap.rows[0].created_at).getTime() : Number.MAX_SAFE_INTEGER;
  if (!force && lag < 100 && !(lag > 0 && ageMs >= 5 * 60 * 1000)) return null;
  if (lag <= 0) {
    return force && snap.rows[0]
      ? { documentId: row.document_id, throughSeq: Number(row.snapshot_through_seq), snapshotId: snap.rows[0].id, reused: true }
      : null;
  }
  const updates = await client.query(
    `SELECT payload FROM collab_updates
     WHERE document_id = $1 AND epoch = $2 AND seq > $3 ORDER BY seq`,
    [row.document_id, row.epoch, row.snapshot_through_seq],
  );
  const compacted = compactState(snap.rows[0]?.state, updates.rows.map((item) => item.payload));
  const snapshotId = crypto.randomUUID();
  await client.query(
    `INSERT INTO document_snapshots
      (id, document_id, epoch, through_seq, schema_version, kind, byte_size, state)
     VALUES ($1, $2, $3, $4, $5, 'automatic', $6, $7)`,
    [snapshotId, row.document_id, row.epoch, row.last_seq, row.schema_version, compacted.state.length, compacted.state],
  );
  await client.query(
    `INSERT INTO document_versions (id, document_id, snapshot_id, kind)
     VALUES ($1, $2, $3, 'automatic')`,
    [crypto.randomUUID(), row.document_id, snapshotId],
  );
  await client.query(
    `UPDATE document_epochs SET snapshot_through_seq = $3 WHERE document_id = $1 AND epoch = $2`,
    [row.document_id, row.epoch, row.last_seq],
  );
  await client.query(
    `UPDATE documents SET word_count = $2, compaction_status = 'ok', updated_at = now() WHERE id = $1`,
    [row.document_id, compacted.wordCount],
  );
  failures.delete(String(row.document_id));
  return { documentId: row.document_id, throughSeq: Number(row.last_seq), wordCount: compacted.wordCount, snapshotId };
}

export async function fulfillNamed(client, documentId, requestId) {
  const request = await client.query(
    `SELECT id, label, requested_by FROM snapshot_requests
     WHERE id = $1 AND document_id = $2 AND status = 'pending'
     FOR UPDATE`,
    [requestId, documentId],
  );
  if (!request.rows.length) return null;
  const compacted = await compactDocument(client, documentId, true);
  if (!compacted?.snapshotId) throw new Error("no snapshot");
  const versionId = crypto.randomUUID();
  await client.query(
    `INSERT INTO document_versions (id, document_id, snapshot_id, kind, label, created_by)
     VALUES ($1, $2, $3, 'named', $4, $5)`,
    [versionId, documentId, compacted.snapshotId, request.rows[0].label, request.rows[0].requested_by],
  );
  await client.query(
    `UPDATE snapshot_requests SET status = 'done', snapshot_id = $2 WHERE id = $1`,
    [requestId, compacted.snapshotId],
  );
  return { ...compacted, versionId };
}

async function compactDue() {
  const due = await pool.query(
    `SELECT e.document_id
     FROM document_epochs e
     JOIN documents d ON d.id = e.document_id AND d.epoch = e.epoch
     WHERE d.status = 'active' AND d.compaction_status <> 'failed'
       AND e.last_seq > e.snapshot_through_seq`,
  );
  for (const row of due.rows) {
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      await compactDocument(client, row.document_id, false);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      const count = (failures.get(String(row.document_id)) || 0) + 1;
      failures.set(String(row.document_id), count);
      if (count >= 5) {
        await pool.query("UPDATE documents SET compaction_status = 'failed' WHERE id = $1", [row.document_id]);
      }
      console.error("compaction failed", error.message);
    } finally {
      client.release();
    }
  }
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://localhost");
    if (req.method === "GET" && (url.pathname === "/health/live" || url.pathname === "/health/ready")) {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ status: "up" }));
      return;
    }
    if (!authorized(req)) {
      res.writeHead(401);
      res.end();
      return;
    }
    const body = req.method === "POST" ? await readBody(req) : {};
    if (req.method === "POST" && url.pathname === "/internal/project") {
      const doc = applyTail(fromBase64(body.snapshotBase64), (body.updatesBase64 || []).map(fromBase64));
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(project(doc)));
      return;
    }
    if (req.method === "POST" && url.pathname === "/internal/encode") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(encodeCanonical(body.canonicalJson)));
      return;
    }
    if (req.method === "POST" && url.pathname === "/internal/compact") {
      const client = await pool.connect();
      try {
        await client.query("BEGIN");
        const result = body.requestId
          ? await fulfillNamed(client, body.documentId, body.requestId)
          : await compactDocument(client, body.documentId, true);
        await client.query("COMMIT");
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(result || { compacted: false }));
      } catch (error) {
        await client.query("ROLLBACK");
        throw error;
      } finally {
        client.release();
      }
      return;
    }
    res.writeHead(404);
    res.end();
  } catch (error) {
    res.writeHead(500, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: { code: "INTERNAL", message: error.message } }));
  }
});

const port = Number(process.env.PORT || 8083);
server.listen(port, () => {
  setInterval(() => { compactDue().catch((error) => console.error(error)); }, 15000);
});

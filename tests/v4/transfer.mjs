import { deflateRawSync, crc32 } from "node:zlib";

const api = process.env.API_URL || "http://api:8080";
const compactor = process.env.COMPACTOR_URL || "http://compactor:8083";
const token = process.env.INTERNAL_TOKEN || "change-me-internal";
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
  return { email, access: created.accessToken, workspaceId: created.workspace.id };
}

function auth(access) {
  return { Authorization: `Bearer ${access}` };
}

async function waitJob(path, access) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const job = await ok(path, { headers: auth(access) });
    if (job.status === "succeeded" || job.status === "failed") return job;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`job did not finish ${path}`);
}

function zip(files) {
  const chunks = [];
  let offset = 0;
  const central = [];
  for (const [name, content] of files) {
    const data = Buffer.from(content);
    const compressed = deflateRawSync(data);
    const nameBytes = Buffer.from(name);
    const crc = crc32(data) >>> 0;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(8, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    chunks.push(local, nameBytes, compressed);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(20, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(8, 10);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(compressed.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt32LE(offset, 42);
    central.push(entry, nameBytes);
    offset += local.length + nameBytes.length + compressed.length;
  }
  const centralStart = offset;
  const centralBytes = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(centralStart, 16);
  return Buffer.concat([...chunks, centralBytes, end]);
}

const docx = zip([
  ["[Content_Types].xml", `<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>`],
  ["_rels/.rels", `<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`],
  ["word/document.xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Welcome</w:t></w:r></w:p>
<w:p><w:r><w:t>• First</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
</w:body></w:document>`],
]);

const html = `<h1>Welcome</h1><ul><li>First</li></ul><table><tr><td>Cell</td></tr></table><img src="http://sink.invalid/x.png" alt="remote">`;

const owner = await account("Owner");
const stranger = await account("Stranger");
const imported = await fetch(`${api}/api/v1/workspaces/${owner.workspaceId}/imports`, {
  method: "POST",
  headers: { ...auth(owner.access), "X-CollabDocs-Request": "1" },
  body: (() => {
    const body = new FormData();
    body.set("format", "html");
    body.set("file", new Blob([html], { type: "text/html" }), "notes.html");
    return body;
  })(),
});
const importBody = await imported.json();
if (imported.status !== 202) throw new Error(`import ${imported.status} ${JSON.stringify(importBody)}`);
const finished = await waitJob(`/api/v1/imports/${importBody.id}`, owner.access);
if (finished.status !== "succeeded") throw new Error(`import failed ${JSON.stringify(finished)}`);
if (!JSON.stringify(finished.report).includes("http://sink.invalid/x.png")) throw new Error("sink url was not reported");

const state = await ok(`/api/v1/documents/${finished.documentId}/content-state`, { headers: auth(owner.access) });
const projected = await fetch(`${compactor}/internal/project`, {
  method: "POST",
  headers: { Authorization: `Bearer ${token}`, "content-type": "application/json" },
  body: JSON.stringify({ snapshotBase64: state.snapshotBase64, updatesBase64: [] }),
}).then((response) => response.json());
const projectedHtml = projected.acceptedHtml || "";
if (!projectedHtml.includes("Welcome") || !projectedHtml.includes("First") || !projectedHtml.includes("Cell")) {
  throw new Error(`imported projection missing constructs ${projectedHtml}`);
}
if (projectedHtml.includes("sink.invalid")) throw new Error("remote image was kept");

const docxImport = await fetch(`${api}/api/v1/workspaces/${owner.workspaceId}/imports`, {
  method: "POST",
  headers: { ...auth(owner.access), "X-CollabDocs-Request": "1" },
  body: (() => {
    const body = new FormData();
    body.set("format", "docx");
    body.set("file", new Blob([docx], { type: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" }), "notes.docx");
    return body;
  })(),
});
const docxBody = await docxImport.json();
if (docxImport.status !== 202) throw new Error(`docx import ${docxImport.status} ${JSON.stringify(docxBody)}`);
const docxJob = await waitJob(`/api/v1/imports/${docxBody.id}`, owner.access);
if (docxJob.status !== "succeeded") throw new Error(`docx import failed ${JSON.stringify(docxJob)}`);

const exported = await ok(`/api/v1/documents/${finished.documentId}/exports`, {
  method: "POST",
  headers: { ...auth(owner.access), "Idempotency-Key": "html-once" },
  body: JSON.stringify({ format: "html", includeSuggestions: false }),
});
const exportJob = await waitJob(`/api/v1/exports/${exported.id}`, owner.access);
if (exportJob.status !== "succeeded") throw new Error(`export failed ${JSON.stringify(exportJob)}`);
const download = await fetch(`${api}/api/v1/exports/${exported.id}/download`, { headers: { ...auth(owner.access), "X-CollabDocs-Request": "1" } });
const file = await download.text();
if (!file.includes("Welcome") || !file.includes("First") || !file.includes("<table>")) throw new Error("html export missing constructs");
const denied = await fetch(`${api}/api/v1/exports/${exported.id}/download`, { headers: { ...auth(stranger.access), "X-CollabDocs-Request": "1" } });
if (denied.status !== 403) throw new Error(`expected 403, got ${denied.status}`);

const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");
const uploaded = await fetch(`${api}/api/v1/documents/${finished.documentId}/attachments`, {
  method: "POST",
  headers: { ...auth(owner.access), "X-CollabDocs-Request": "1" },
  body: (() => {
    const body = new FormData();
    body.set("file", new Blob([png], { type: "image/png" }), "pixel.png");
    body.set("altText", "Chart");
    body.set("decorative", "false");
    return body;
  })(),
});
const attachment = await uploaded.json();
if (uploaded.status !== 201) throw new Error(`upload ${uploaded.status} ${JSON.stringify(attachment)}`);
const stored = await fetch(`${api}/api/v1/documents/${finished.documentId}/attachments/${attachment.id}`, { headers: { ...auth(owner.access), "X-CollabDocs-Request": "1" } });
if (stored.status !== 200) throw new Error(`attachment read ${stored.status}`);
console.log("v4 transfer checks passed");
process.exit(0);

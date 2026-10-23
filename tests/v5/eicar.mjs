const api = process.env.API_URL || "http://api:8080";
const eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

async function json(path, options = {}) {
  const response = await fetch(api + path, options);
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null, text };
}

const email = `eicar-${Date.now()}@example.com`;
const created = await json("/api/v1/auth/register", {
  method: "POST",
  headers: { "content-type": "application/json", "X-CollabDocs-Request": "1" },
  body: JSON.stringify({ email, displayName: "Eicar", password: "correct-horse-1" }),
});
if (created.status >= 300) throw new Error(`register ${created.status} ${created.text}`);
const access = created.body.accessToken;
const imported = await fetch(`${api}/api/v1/workspaces/${created.body.workspace.id}/imports`, {
  method: "POST",
  headers: { Authorization: `Bearer ${access}`, "X-CollabDocs-Request": "1" },
  body: (() => {
    const body = new FormData();
    body.set("format", "html");
    body.set("file", new Blob([eicar], { type: "text/html" }), "eicar.html");
    return body;
  })(),
});
const job = await imported.json();
if (imported.status !== 202) throw new Error(`import ${imported.status} ${JSON.stringify(job)}`);
let finished = null;
for (let attempt = 0; attempt < 60; attempt += 1) {
  const current = await json(`/api/v1/imports/${job.id}`, { headers: { Authorization: `Bearer ${access}`, "X-CollabDocs-Request": "1" } });
  if (current.body.status === "succeeded" || current.body.status === "failed") {
    finished = current.body;
    break;
  }
  await new Promise((resolve) => setTimeout(resolve, 2000));
}
if (!finished || finished.status !== "failed" || finished.errorCode !== "MALWARE_DETECTED") {
  throw new Error(`expected malware failure, got ${JSON.stringify(finished)}`);
}
if (finished.documentId) throw new Error("malware import created a document");
console.log("eicar import rejected");
process.exit(0);

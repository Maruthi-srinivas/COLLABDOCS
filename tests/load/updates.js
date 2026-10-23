import http from "k6/http";
import { check, sleep } from "k6";
import encoding from "k6/encoding";

export const options = {
  vus: 10,
  duration: "30s",
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
};

export function setup() {
  const base = __ENV.BASE_URL;
  const headers = { "Content-Type": "application/json", "X-CollabDocs-Request": "1" };
  const email = `ack-${Date.now()}@example.com`;
  const created = http.post(`${base}/api/v1/auth/register`, JSON.stringify({
    email, displayName: "Ack", password: "correct-horse-1",
  }), { headers });
  const body = created.json();
  const document = http.post(`${base}/api/v1/workspaces/${body.workspace.id}/documents`, JSON.stringify({ title: "Ack" }), {
    headers: { ...headers, Authorization: `Bearer ${body.accessToken}` },
  });
  const payload = encoding.b64encode(new Uint8Array(16 * 1024).fill(65));
  return { base, token: body.accessToken, documentId: document.json().id, payload };
}

export default function (data) {
  const response = http.post(`${data.base}/api/v1/documents/${data.documentId}/content-updates`, JSON.stringify({
    clientId: `018f2c1a-7c31-7b2a-9a10-${String(__VU).padStart(12, "0")}`,
    updateId: `018f2c1a-7c31-7b2a-${String(__VU).padStart(4, "0")}-${String(__ITER).padStart(12, "0")}`,
    epoch: 1,
    intent: "edit",
    payloadBase64: data.payload,
  }), {
    headers: {
      Authorization: `Bearer ${data.token}`,
      "Content-Type": "application/json",
      "X-CollabDocs-Request": "1",
    },
  });
  check(response, { "ack 200": (item) => item.status === 200 });
  sleep(0.05);
}

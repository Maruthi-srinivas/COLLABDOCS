import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: 50,
  duration: "30s",
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  thresholds: {
    http_req_failed: ["rate<0.01"],
  },
};

export function setup() {
  const base = __ENV.BASE_URL;
  const headers = { "Content-Type": "application/json", "X-CollabDocs-Request": "1" };
  const email = `load-${Date.now()}@example.com`;
  const created = http.post(`${base}/api/v1/auth/register`, JSON.stringify({
    email, displayName: "Load", password: "correct-horse-1",
  }), { headers });
  const body = created.json();
  const access = body.accessToken;
  const auth = { ...headers, Authorization: `Bearer ${access}` };
  const document = http.post(`${base}/api/v1/workspaces/${body.workspace.id}/documents`, JSON.stringify({ title: "Load" }), { headers: auth });
  return { base, token: access, documentId: document.json().id };
}

export default function (data) {
  const response = http.get(`${data.base}/api/v1/documents/${data.documentId}`, {
    headers: { Authorization: `Bearer ${data.token}`, "X-CollabDocs-Request": "1" },
  });
  check(response, { "metadata 200": (item) => item.status === 200 });
  sleep(0.1);
}

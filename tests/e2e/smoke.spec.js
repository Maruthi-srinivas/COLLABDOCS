import { test, expect } from "@playwright/test";

const mailpit = process.env.MAILPIT_URL || "http://localhost:8025";

test("register, verify, edit, and reload", async ({ page }) => {
  const email = `smoke-${Date.now()}@example.com`;
  await page.goto("/register");
  await page.getByLabel("Display name").fill("Smoke User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("correct-horse-1");
  await page.getByRole("button", { name: "Register" }).click();
  await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();

  const token = await verificationToken(email);
  await page.goto(`/verify-email?token=${token}`);
  await expect(page.getByRole("button", { name: "New document" })).toBeVisible();
  await page.getByRole("button", { name: "New document" }).click();
  const editor = page.getByTestId("editor");
  await expect(editor).toBeVisible();
  await editor.click();
  await page.keyboard.type("Hello CollabDocs");
  await expect(page.getByTestId("save-status")).toHaveText("Saved", { timeout: 15000 });
  await restartApi();
  await page.reload();
  await expect(page.getByTestId("editor")).toContainText("Hello CollabDocs");
});

async function restartApi() {
  const socketPath = process.env.DOCKER_SOCK || "/var/run/docker.sock";
  const http = await import("node:http");
  const call = (path, method) => new Promise((resolve, reject) => {
    const req = http.request({ socketPath, path, method }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
    });
    req.on("error", reject);
    req.end();
  });
  const restarted = await call("/containers/collabdocs-api-1/restart", "POST");
  if (restarted.status < 200 || restarted.status >= 300) {
    throw new Error(`restart ${restarted.status}`);
  }
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const inspect = await call("/containers/collabdocs-api-1/json", "GET");
    const state = JSON.parse(inspect.body).State;
    if (state?.Health?.Status === "healthy") return;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error("API did not become ready");
}

async function verificationToken(email) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const list = await fetch(`${mailpit}/api/v1/messages`);
    const body = await list.json();
    const message = (body.messages || []).find((item) => (item.To || []).some((to) => to.Address === email) || (item.Snippet || "").includes(email));
    if (message) {
      const full = await fetch(`${mailpit}/api/v1/message/${message.ID}`);
      const detail = await full.json();
      const text = detail.Text || detail.HTML || "";
      const match = text.match(/token=([A-Za-z0-9_-]+)/);
      if (match) return match[1];
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("verification mail not found");
}

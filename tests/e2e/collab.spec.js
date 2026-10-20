import { test, expect } from "@playwright/test";

const mailpit = process.env.MAILPIT_URL || "http://localhost:8025";

test("two editors converge and revoke closes the second", async ({ browser }) => {
  const owner = await browser.newContext();
  const guest = await browser.newContext();
  const ownerPage = await owner.newPage();
  const guestPage = await guest.newPage();
  const ownerEmail = `owner-${Date.now()}@example.com`;
  const guestEmail = `guest-${Date.now()}@example.com`;
  await register(ownerPage, "Owner One", ownerEmail);
  await register(guestPage, "Guest Two", guestEmail);
  await ownerPage.getByRole("button", { name: "New document" }).click();
  await expect(ownerPage.getByTestId("save-status")).toHaveText("Saved", { timeout: 15000 });
  await ownerPage.getByRole("button", { name: "Share" }).click();
  await ownerPage.getByLabel("Email").fill(guestEmail);
  await ownerPage.getByRole("button", { name: "Invite" }).click();
  await expect(ownerPage.getByText(guestEmail)).toBeVisible();
  await ownerPage.getByRole("button", { name: "Close" }).click();
  await guestPage.goto(ownerPage.url());
  await expect(guestPage.getByTestId("save-status")).toHaveText("Saved", { timeout: 15000 });
  await ownerPage.getByTestId("editor").click();
  await guestPage.getByTestId("editor").click();
  await ownerPage.keyboard.type("A");
  await guestPage.keyboard.type("B");
  await expect(ownerPage.getByTestId("editor")).toContainText("A", { timeout: 15000 });
  await expect(ownerPage.getByTestId("editor")).toContainText("B", { timeout: 15000 });
  await expect(guestPage.getByTestId("editor")).toContainText("A", { timeout: 15000 });
  await expect(guestPage.getByTestId("editor")).toContainText("B", { timeout: 15000 });
  await ownerPage.getByRole("button", { name: "Share" }).click();
  await ownerPage.getByRole("button", { name: "Revoke" }).click();
  await expect(guestPage.getByTestId("access-revoked")).toBeVisible({ timeout: 10000 });
});

async function register(page, name, email) {
  await page.goto("/register");
  await page.getByLabel("Display name").fill(name);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("correct-horse-1");
  await page.getByRole("button", { name: "Register" }).click();
  await page.goto(`/verify-email?token=${await verificationToken(email)}`);
  await expect(page.getByRole("button", { name: "New document" })).toBeVisible();
}

async function verificationToken(email) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const list = await fetch(`${mailpit}/api/v1/messages`).then((response) => response.json());
    const message = (list.messages || []).find((item) => JSON.stringify(item).includes(email));
    if (message) {
      const detail = await fetch(`${mailpit}/api/v1/message/${message.ID}`).then((response) => response.json());
      const match = (detail.Text || detail.HTML || "").match(/token=([A-Za-z0-9_-]+)/);
      if (match) return match[1];
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error("verification mail not found");
}

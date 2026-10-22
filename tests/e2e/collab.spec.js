import { test, expect } from "@playwright/test";

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
  await expect(page.getByRole("button", { name: "New document" })).toBeVisible();
}

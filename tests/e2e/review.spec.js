import { test, expect } from "@playwright/test";

test("comment, suggestion, and restore converge", async ({ browser }) => {
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
  await ownerPage.getByTestId("editor").click();
  await ownerPage.keyboard.type("Hello");
  await expect(ownerPage.getByTestId("save-status")).toHaveText("Saved", { timeout: 15000 });
  await ownerPage.getByRole("button", { name: "Share" }).click();
  await ownerPage.getByLabel("Email").fill(guestEmail);
  await ownerPage.getByLabel("Role").selectOption("commenter");
  await ownerPage.getByRole("button", { name: "Invite" }).click();
  await expect(ownerPage.getByText(guestEmail)).toBeVisible();
  await ownerPage.getByRole("button", { name: "Close" }).click();
  await ownerPage.getByRole("button", { name: "History" }).click();
  await ownerPage.getByLabel("Version name").fill("Checkpoint");
  await ownerPage.getByRole("button", { name: "Name version" }).click();
  await expect(ownerPage.getByRole("button", { name: /Checkpoint/ })).toBeVisible({ timeout: 15000 });
  await guestPage.goto(ownerPage.url());
  await expect(guestPage.getByTestId("editor")).toContainText("Hello", { timeout: 15000 });
  await guestPage.getByTestId("editor").click({ clickCount: 3 });
  await guestPage.getByLabel("Comment", { exact: true }).fill("Please check this");
  await guestPage.getByRole("button", { name: "Add comment" }).click();
  await expect(guestPage.getByText("Please check this")).toBeVisible({ timeout: 10000 });
  await expect(ownerPage.getByText("Please check this")).toBeVisible({ timeout: 15000 });
  await guestPage.getByRole("button", { name: "Suggesting" }).click();
  await guestPage.getByTestId("editor").click();
  await guestPage.keyboard.type("!");
  await expect(ownerPage.getByRole("button", { name: "Accept" })).toBeVisible({ timeout: 15000 });
  await ownerPage.getByRole("button", { name: "Accept" }).click();
  await expect(ownerPage.getByTestId("editor")).toContainText("!", { timeout: 15000 });
  ownerPage.once("dialog", (dialog) => dialog.accept());
  await ownerPage.locator(".version-panel .row", { hasText: "Checkpoint" }).getByRole("button", { name: "Restore" }).click();
  await guestPage.getByRole("button", { name: "Continue" }).click();
  await expect(guestPage.getByTestId("editor")).toContainText("Hello", { timeout: 15000 });
  await expect(guestPage.getByTestId("editor")).not.toContainText("!");
});

async function register(page, name, email) {
  await page.goto("/register");
  await page.getByLabel("Display name").fill(name);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("correct-horse-1");
  await page.getByRole("button", { name: "Register" }).click();
  await expect(page.getByRole("button", { name: "New document" })).toBeVisible();
}

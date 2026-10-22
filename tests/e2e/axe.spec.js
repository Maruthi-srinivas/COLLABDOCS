import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

async function serious(page) {
  const results = await new AxeBuilder({ page }).analyze();
  return results.violations.filter((item) => item.impact === "serious" || item.impact === "critical");
}

test("named pages have no serious axe violations", async ({ page }) => {
  await page.goto("/register");
  expect(await serious(page)).toEqual([]);
  await page.goto("/login");
  expect(await serious(page)).toEqual([]);
  const email = `axe-${Date.now()}@example.com`;
  await page.goto("/register");
  await page.getByLabel("Display name").fill("Axe User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("correct-horse-1");
  await page.getByRole("button", { name: "Register" }).click();
  await expect(page.getByRole("button", { name: "New document" })).toBeVisible();
  expect(await serious(page)).toEqual([]);
  await page.getByRole("button", { name: "New document" }).click();
  await expect(page.getByTestId("editor")).toBeVisible();
  expect(await serious(page)).toEqual([]);
  await page.getByRole("button", { name: "Share" }).click();
  expect(await serious(page)).toEqual([]);
  await page.getByRole("button", { name: "Close" }).click();
  await page.getByRole("button", { name: "TXT" }).click();
  await expect(page.getByTestId("export-status")).toBeVisible();
  expect(await serious(page)).toEqual([]);
});

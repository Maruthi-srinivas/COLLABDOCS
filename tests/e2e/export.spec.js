import { test, expect } from "@playwright/test";

test("upload an image and download a text export", async ({ page }) => {
  const email = `export-${Date.now()}@example.com`;
  await page.goto("/register");
  await page.getByLabel("Display name").fill("Export User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("correct-horse-1");
  await page.getByRole("button", { name: "Register" }).click();
  await expect(page.getByRole("button", { name: "New document" })).toBeVisible();
  await page.getByRole("button", { name: "New document" }).click();
  await expect(page.getByTestId("save-status")).toHaveText("Saved", { timeout: 15000 });
  await page.getByTestId("editor").click();
  await page.keyboard.type("Hello export");
  await expect(page.getByTestId("save-status")).toHaveText("Saved", { timeout: 15000 });
  await page.getByRole("button", { name: "Image" }).click();
  await page.getByLabel("Image file").setInputFiles({
    name: "pixel.png",
    mimeType: "image/png",
    buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64"),
  });
  await page.getByLabel("Alt text").fill("Chart");
  await page.getByRole("button", { name: "Insert image" }).click();
  await expect(page.getByTestId("save-status")).toHaveText("Saved", { timeout: 15000 });
  await page.getByRole("button", { name: "TXT" }).click();
  await expect(page.getByTestId("export-status")).toHaveText("Export ready", { timeout: 30000 });
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: "Download" }).click(),
  ]);
  const text = await download.createReadStream().then((stream) => new Promise((resolve, reject) => {
    const chunks = [];
    stream.on("data", (chunk) => chunks.push(chunk));
    stream.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    stream.on("error", reject);
  }));
  expect(text).toContain("Hello export");
});

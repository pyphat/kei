import { test, expect } from "@playwright/test";
import { mkdirSync, copyFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const SHOTS = "e2e/screenshots";
const TEST_IMG = join(__dirname, "..", "..", "..", "test");

test("kei e2e smoke", async ({ page, browser, request }) => {
  mkdirSync(SHOTS, { recursive: true });
  // prepare a "Kaguya Test" folder with 3 pages
  const dir = join(tmpdir(), "Kaguya Test");
  mkdirSync(dir, { recursive: true });
  for (const n of ["010.jpg", "011.jpg", "012.jpg"])
    copyFileSync(join(TEST_IMG, n), join(dir, n));

  // 1. upload via the folder picker
  await page.goto("/upload");
  await page.locator('input[aria-label="Choose folder"]').setInputFiles(dir);
  await expect(page.getByText("3 pages")).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/upload_preview.png` });
  await page.getByRole("button", { name: "Upload", exact: true }).click();
  await expect(page.getByRole("link", { name: /Open series/ }))
    .toBeVisible({ timeout: 30_000 });
  await page.getByRole("link", { name: /Open series/ }).click();
  await page.waitForURL(/\/series\/\d+/);
  const seriesUrl = page.url();
  const seriesId = seriesUrl.split("/").pop()!;

  // series page: chapter row + cover thumb
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page.screenshot({ path: `${SHOTS}/series.png` });

  // 2. library: rename + cover
  await page.goto("/");
  // NB: not hasText — while renaming, the title lives in the input's value,
  // which isn't textContent. Anchor the card by its series link instead.
  const card = page.locator(".group")
    .filter({ has: page.locator(`a[href="/series/${seriesId}"]`) });
  await expect(card.locator("img")).toBeVisible(); // auto cover
  await card.hover();
  await card.getByRole("button", { name: "Rename" }).click();
  await card.locator('input[aria-label="Rename series"]').fill("Kaguya Renamed");
  await page.keyboard.press("Enter");
  await page.reload();
  await expect(page.getByText("Kaguya Renamed").first()).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/library.png`, fullPage: true });

  // 3. reader: page 1 -> next -> reload resumes page 2
  await page.goto(seriesUrl);
  await page.getByRole("link", { name: "Read" }).first().click();
  await page.waitForURL(/\/read\/\d+/);
  await expect(page.getByText("1 / 3")).toBeVisible({ timeout: 15_000 });
  await page.keyboard.press("a"); // RTL on => "a" is next page
  await expect(page.getByText("2 / 3")).toBeVisible();
  await page.waitForTimeout(1500); // let the debounced progress PUT land
  await page.reload();
  await expect(page.getByText("2 / 3")).toBeVisible();

  // 4. translate -> page 1 becomes processed/translated via SSE
  await page.keyboard.press("d"); // back to page 1 (RTL: "d" is prev)
  await expect(page.getByText("1 / 3")).toBeVisible();
  await page.getByRole("button", { name: "Translate" }).click();
  await expect(
    page.locator("span.rounded-full", { hasText: /processed|translated/ }),
  ).toBeVisible({ timeout: 120_000 });
  await page.selectOption('select[aria-label="View"]', "original");
  await page.selectOption('select[aria-label="View"]', "translated");
  // wait for the <img> to have actually loaded the translated URL
  await page.waitForFunction(() => {
    const im = document.querySelector<HTMLImageElement>("main img");
    return !!im && im.src.includes("view=translated")
      && im.complete && im.naturalWidth > 0;
  });
  // proof the processed page renders differently than the original
  const differs = await page.evaluate(async () => {
    const src = document.querySelector<HTMLImageElement>("main img")!.src;
    const orig = src.replace(/view=\w+/, "view=original");
    const [a, b] = await Promise.all(
      [src, orig].map(async (u) => (await fetch(u)).arrayBuffer()));
    return a.byteLength !== b.byteLength
      || new Uint8Array(a).some((v, i) => v !== new Uint8Array(b)[i]);
  });
  expect(differs).toBe(true);
  await page.screenshot({ path: `${SHOTS}/reader_single.png` });

  // 5. continuous mode shows 3 images
  await page.getByRole("button", { name: "Continuous" }).click();
  await expect(page.locator("main img")).toHaveCount(3);
  await page.screenshot({ path: `${SHOTS}/reader_continuous.png` });

  // settings shot
  await page.goto("/settings");
  await expect(page.getByText("OpenRouter API key")).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/settings.png` });

  // library with continue row (progress was saved)
  await page.goto("/");
  await expect(page.getByText("Continue reading")).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/library_continue.png`, fullPage: true });

  // phone-width shots
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const mob = await ctx.newPage();
  await mob.goto("/");
  await mob.screenshot({ path: `${SHOTS}/mobile_library.png`, fullPage: true });
  await mob.goto(seriesUrl);
  await mob.getByRole("link", { name: "Read" }).first().click();
  await expect(mob.getByText(/\/ 3/)).toBeVisible({ timeout: 15_000 });
  await mob.screenshot({ path: `${SHOTS}/mobile_reader.png` });
  await ctx.close();

  // keep the dev library clean for reruns
  await request.delete(`/api/series/${seriesId}`);
});

import { test, expect } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { join } from "node:path";

const SHOTS = join(__dirname, "..", "debug_out", "revamp");

async function setTheme(page: import("@playwright/test").Page, theme: "Dark" | "Light") {
  await page.getByRole("button", { name: "Theme" }).click();
  await page.getByRole("menuitem", { name: theme, exact: true }).click();
  await page.waitForFunction((value) => document.documentElement.classList.contains(value.toLowerCase()), theme);
}

async function blockReaderWrites(page: import("@playwright/test").Page) {
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = input instanceof Request ? input.url : String(input);
      const method = init?.method ?? (input instanceof Request ? input.method : "GET");
      if (url.includes("/focus") || (method === "PUT" && url.includes("/api/progress/"))) {
        return Promise.resolve(new Response(JSON.stringify({ ok: true }), {
          status: 200, headers: { "Content-Type": "application/json" },
        }));
      }
      return originalFetch(input, init);
    };
  });
}

test("capture revamp screens in both themes", async ({ page, browser, request }) => {
  mkdirSync(SHOTS, { recursive: true });
  const seriesResponse = await request.get("/api/series");
  expect(seriesResponse.ok()).toBeTruthy();
  const seriesList = await seriesResponse.json() as { id: number; title: string }[];
  expect(seriesList.length).toBeGreaterThan(0);
  const series = seriesList[0];
  const detailResponse = await request.get(`/api/series/${series.id}`);
  const detail = await detailResponse.json() as {
    chapters: { id: number; number: number }[];
  };
  expect(detail.chapters.length).toBeGreaterThan(0);
  const chapter = detail.chapters[0];
  const chapterResponse = await request.get(`/api/chapters/${chapter.id}`);
  const chapterData = await chapterResponse.json() as { pages: { idx: number }[] };

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Library" })).toBeVisible();
  await setTheme(page, "Dark");
  await page.screenshot({ path: join(SHOTS, "library-dark.png"), fullPage: true, caret: "initial" });
  await setTheme(page, "Light");
  await page.screenshot({ path: join(SHOTS, "library-light.png"), fullPage: true, caret: "initial" });

  await page.goto(`/series/${series.id}`);
  await expect(page.getByRole("heading", { name: "Chapters" })).toBeVisible();
  await setTheme(page, "Dark");
  await page.screenshot({ path: join(SHOTS, "series-dark.png"), fullPage: true, caret: "initial" });
  await setTheme(page, "Light");
  await page.screenshot({ path: join(SHOTS, "series-light.png"), fullPage: true, caret: "initial" });

  await page.goto("/upload");
  await expect(page.getByRole("heading", { name: "Add manga" })).toBeVisible();
  await setTheme(page, "Dark");
  await page.screenshot({ path: join(SHOTS, "upload-dark.png"), fullPage: true, caret: "initial" });
  await setTheme(page, "Light");
  await page.screenshot({ path: join(SHOTS, "upload-light.png"), fullPage: true, caret: "initial" });

  await page.goto("/settings");
  await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
  await page.setViewportSize({ width: 1280, height: 1200 });
  await setTheme(page, "Dark");
  await page.screenshot({ path: join(SHOTS, "settings-dark.png"), fullPage: true, caret: "initial" });
  await setTheme(page, "Light");
  await page.screenshot({ path: join(SHOTS, "settings-light.png"), fullPage: true, caret: "initial" });
  await page.setViewportSize({ width: 1280, height: 900 });

  const readerUrl = `/read/${chapter.id}?page=0`;
  await blockReaderWrites(page);
  await setTheme(page, "Dark");
  await page.goto(readerUrl);
  await expect(page.getByText(/\/ \d+/)).toBeVisible({ timeout: 15_000 });
  await page.waitForFunction(() => {
    const image = document.querySelector<HTMLImageElement>("main img");
    return !!image && image.complete && image.naturalWidth > 0;
  });
  await page.mouse.move(180, 50);
  await page.waitForTimeout(350);
  await page.screenshot({ path: join(SHOTS, "reader-bars-shown-dark.png"), caret: "initial" });
  await page.keyboard.press("Escape");
  await page.waitForTimeout(350);
  await page.screenshot({ path: join(SHOTS, "reader-bars-hidden-dark.png"), caret: "initial" });
  if (chapterData.pages.length > 1) {
    await page.keyboard.press("a");
    await expect(page.getByText(`2 / ${chapterData.pages.length}`)).toBeVisible({ timeout: 5000 });
    await page.keyboard.press("d");
  }

  await page.goto("/");
  await setTheme(page, "Light");
  await page.goto(readerUrl);
  await expect(page.getByText(/\/ \d+/)).toBeVisible({ timeout: 15_000 });
  await page.waitForFunction(() => {
    const image = document.querySelector<HTMLImageElement>("main img");
    return !!image && image.complete && image.naturalWidth > 0;
  });
  await page.mouse.move(180, 50);
  await page.waitForTimeout(350);
  await page.screenshot({ path: join(SHOTS, "reader-bars-shown-light.png"), caret: "initial" });
  await page.keyboard.press("Escape");
  await page.waitForTimeout(350);
  await page.screenshot({ path: join(SHOTS, "reader-bars-hidden-light.png"), caret: "initial" });

  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const mobilePage = await mobile.newPage();
  await blockReaderWrites(mobilePage);
  await mobilePage.goto("/");
  await mobilePage.screenshot({ path: join(SHOTS, "library-mobile.png"), fullPage: true, caret: "initial" });
  await mobilePage.goto(readerUrl);
  await expect(mobilePage.getByText(/\/ \d+/)).toBeVisible({ timeout: 15_000 });
  await mobilePage.waitForFunction(() => {
    const image = document.querySelector<HTMLImageElement>("main img");
    return !!image && image.complete && image.naturalWidth > 0;
  });
  await mobilePage.mouse.move(180, 50);
  await mobilePage.waitForTimeout(350);
  await mobilePage.screenshot({ path: join(SHOTS, "reader-mobile.png"), caret: "initial" });
  await mobile.close();
});

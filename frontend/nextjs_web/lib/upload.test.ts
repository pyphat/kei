import { test } from "node:test";
import assert from "node:assert/strict";
import {
  chapterNumberFromName, detectChapters, isImagePath,
} from "./upload.ts";

test("isImagePath filters junk", () => {
  assert.ok(isImagePath("a/001.jpg"));
  assert.ok(isImagePath("a/001.WEBP"));
  assert.ok(!isImagePath("a/Thumbs.db"));
  assert.ok(!isImagePath("a/.hidden.jpg"));
  assert.ok(!isImagePath("__MACOSX/x.jpg"));
  assert.ok(!isImagePath("a/readme.txt"));
});

test("chapterNumberFromName takes the last number", () => {
  assert.equal(chapterNumberFromName("Ch 2"), 2);
  assert.equal(chapterNumberFromName("chapter_10"), 10);
  assert.equal(chapterNumberFromName("Vol 3 Ch 12"), 12);
  assert.equal(chapterNumberFromName("prologue"), null);
});

test("root images -> one chapter", () => {
  const d = detectChapters(["3.jpg", "1.jpg", "2.jpg", "x.txt"]);
  assert.equal(d!.length, 1);
  assert.equal(d![0].number, 1);
  assert.deepEqual(d![0].files, ["1.jpg", "2.jpg", "3.jpg"]);
});

test("subfolders -> one chapter each, numbers parsed", () => {
  const d = detectChapters([
    "Manga/Chapter_10/1.jpg", "Manga/Ch 2/2.jpg", "Manga/Ch 2/10.jpg",
    "Manga/Ch 2/1.jpg", "Manga/cover.jpg",
  ]);
  // root image + subfolders: root becomes its own chapter 0
  assert.equal(d!.length, 3);
  assert.equal(d![0].number, 0);
  assert.deepEqual(d![0].files, ["Manga/cover.jpg"]);

  const d2 = detectChapters([
    "Manga/Chapter_10/1.jpg", "Manga/Ch 2/2.jpg", "Manga/Ch 2/10.jpg",
    "Manga/Ch 2/1.jpg", "Manga/Thumbs.db", "Manga/__MACOSX/x.jpg",
  ]);
  assert.equal(d2!.length, 2);
  assert.equal(d2![0].number, 2);   // Ch 2 first (nat sort)
  assert.equal(d2![1].number, 10);
  assert.deepEqual(d2![0].files,
    ["Manga/Ch 2/1.jpg", "Manga/Ch 2/2.jpg", "Manga/Ch 2/10.jpg"]);
});

test("subfolder without number gets order index", () => {
  const d = detectChapters(["S/prologue/1.jpg", "S/Ch 5/1.jpg"]);
  assert.equal(d!.length, 2);
  assert.equal(d!.find((c) => c.dir === "Ch 5")!.number, 5);
  assert.equal(d!.find((c) => c.dir === "prologue")!.number, 2);
});

test("no images -> null", () => {
  assert.equal(detectChapters(["a.txt", "b/x.txt"]), null);
});

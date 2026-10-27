import { test } from "node:test";
import assert from "node:assert/strict";
import { natsort } from "./natsort.ts";

test("numeric ordering: 2 < 10", () => {
  assert.deepEqual(
    ["p10.jpg", "p2.jpg", "p1.jpg"].sort(natsort),
    ["p1.jpg", "p2.jpg", "p10.jpg"]);
});

test("case-insensitive", () => {
  assert.deepEqual(["B.jpg", "a.jpg"].sort(natsort), ["a.jpg", "B.jpg"]);
});

test("chapter names", () => {
  assert.deepEqual(
    ["Ch 10", "Ch 2", "Ch 1"].sort(natsort),
    ["Ch 1", "Ch 2", "Ch 10"]);
});

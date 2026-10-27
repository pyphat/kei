/** Pure upload logic: structure detection, chapter-number parsing, sha256.
 *  No DOM/React imports — node --test can run these directly. */
import { natsort } from "./natsort.ts";

export const IMG_EXT = new Set(["jpg", "jpeg", "png", "webp"]);
const IGNORED = new Set(["thumbs.db", "desktop.ini"]);

export function extOf(path: string): string {
  const base = path.slice(path.lastIndexOf("/") + 1);
  const dot = base.lastIndexOf(".");
  return dot < 0 ? "" : base.slice(dot + 1).toLowerCase();
}

export function isImagePath(path: string): boolean {
  const base = path.slice(path.lastIndexOf("/") + 1);
  if (base.startsWith(".") || IGNORED.has(base.toLowerCase())) return false;
  if (path.split("/").some((seg) => seg === "__MACOSX" || seg.startsWith(".")))
    return false;
  return IMG_EXT.has(extOf(path));
}

/** Last number in a folder name, e.g. "Ch 2" -> 2, "chapter_10" -> 10. */
export function chapterNumberFromName(name: string): number | null {
  const m = name.match(/(\d+(?:\.\d+)?)(?!.*\d)/);
  return m ? parseFloat(m[1]) : null;
}

export interface DetectedChapter {
  /** Relative directory of the chapter ("" for root), or the folder name. */
  dir: string;
  number: number;
  title: string;
  /** natural-sorted relative image paths */
  files: string[];
}

/**
 * Given relative paths of a dropped/selected set, detect chapters:
 * - images directly in the root  -> ONE chapter (number 1)
 * - subfolders containing images -> one chapter per subfolder
 * - deeper nesting flattens to the first directory level
 * Returns null when there are no images at all.
 */
export function detectChapters(paths: string[]): DetectedChapter[] | null {
  const imgs = paths.filter(isImagePath);
  if (!imgs.length) return null;
  const rootDir = commonRoot(paths);
  const rel = imgs.map((p) => p.slice(rootDir.length));

  const byDir = new Map<string, string[]>();
  for (const p of rel) {
    const i = p.indexOf("/");
    const dir = i < 0 ? "" : p.slice(0, i);
    (byDir.get(dir) ?? byDir.set(dir, []).get(dir)!).push(p);
  }
  const dirs = [...byDir.keys()].sort((a, b) =>
    a === "" ? -1 : b === "" ? 1 : natsort(a, b));
  const rootImgs = byDir.get("") ?? [];
  return dirs.map((dir, i) => ({
    dir,
    // root images alone = chapter 1; mixed with subfolders = chapter 0
    number: dir === "" ? (dirs.length === 1 ? 1 : 0)
      : (chapterNumberFromName(dir) ?? i + 1),
    title: dir,
    files: (dir === "" ? rootImgs : byDir.get(dir)!)
      .sort(natsort).map((p) => rootDir + p),
  }));
}

/** Strip a common leading directory ("MangaName/ch1/1.jpg" -> "MangaName/"). */
function commonRoot(paths: string[]): string {
  if (!paths.length) return "";
  const first = paths[0];
  const top = first.includes("/") ? first.slice(0, first.indexOf("/") + 1) : "";
  if (!top) return "";
  return paths.every((p) => p.startsWith(top)) ? top : "";
}

export async function sha256hex(data: ArrayBuffer): Promise<string> {
  const h = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(h)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Run tasks with limited concurrency, preserving input order of results. */
export async function pooled<T, R>(
  items: T[], n: number, fn: (item: T, i: number) => Promise<R>,
): Promise<R[]> {
  const out = new Array<R>(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i], i);
    }
  }));
  return out;
}

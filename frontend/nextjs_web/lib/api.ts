/** Typed fetch helpers matching backend/api_fastapi/app.py responses. */

export interface Series {
  id: number;
  title: string;
  author: string;
  tags: string[];
  cover_url: string | null;
  chapter_count: number;
  page_count: number;
  updated: number;
  progress: { chapter_id: number; page: number; percent: number } | null;
}

export interface ChapterSummary {
  id: number;
  number: number;
  title: string;
  page_count: number;
  present_count: number;
  processed_count: number;
  translated_count: number;
  state: "running" | "paused" | "idle";
}

export interface SeriesDetail extends Series {
  chapters: ChapterSummary[];
}

export type PageStatus =
  | "missing" | "pending" | "queued" | "processing"
  | "processed" | "translated" | "failed";

export interface ChapterDetail {
  id: number;
  series_id: number;
  number: number;
  title: string;
  state: "running" | "paused" | "idle";
  pages: { idx: number; width: number; height: number; status: PageStatus }[];
}

export interface Blocks {
  width: number;
  height: number;
  blocks: { id: string; bbox: [number, number, number, number]; source: string; translation: string }[];
}

export interface Status {
  device: string;
  requests_today: number;
  has_key: boolean;
  inpainter: string;
  target_lang: string;
}

export interface SettingsDict {
  model_priority: string[];
  target_lang: string;
  reading_rtl: boolean;
  font_path: string;
  font_min: number;
  font_max: number;
  inpainter: string;
  device: string;
  cache_dir: string;
  library_dir: string;
  det_conf: number;
  batch_pages: number;
  masker: "threshold" | "model" | "combined";
  mask_dilate: number;
  mask_feather: number;
  mask_thresh: number;
  has_key: boolean;
}

export interface ProgressRow {
  series_id: number;
  chapter_id: number;
  page: number;
  percent: number;
  device_id: string;
  ts: number;
}

export interface ContinueRow extends ProgressRow {
  series_title: string;
  cover_url: string | null;
  chapter_title: string;
  chapter_number: number;
}

export interface ManifestFile { idx: number; ext: string; sha256: string; size: number }
export interface ManifestChapter { number: number; title: string; files: ManifestFile[] }
export interface ManifestResult {
  series_id: number;
  chapters: { chapter_id: number; number: number; missing: number[] }[];
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, init);
  if (!r.ok) {
    let detail = `${r.status}`;
    try { detail = (await r.json()).detail ?? detail; } catch { /* non-json */ }
    throw new ApiError(r.status, String(detail));
  }
  return r.status === 204 ? (undefined as T) : r.json();
}

export class ApiError extends Error {
  constructor(public status: number, msg: string) { super(msg); }
}

const json = (body: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const api = {
  listSeries: () => req<Series[]>("/api/series"),
  getSeries: (id: number) => req<SeriesDetail>(`/api/series/${id}`),
  patchSeries: (id: number, b: { title?: string; author?: string; tags?: string[] }) =>
    req<Series>(`/api/series/${id}`, { ...json(b), method: "PATCH" }),
  deleteSeries: (id: number) =>
    req<{ ok: boolean }>(`/api/series/${id}`, { method: "DELETE" }),
  uploadCover: async (sid: number, f: File) => {
    const fd = new FormData();
    fd.append("file", f);
    const r = await fetch(`/api/series/${sid}/cover`, { method: "POST", body: fd });
    if (!r.ok) throw new ApiError(r.status, (await r.json()).detail ?? "cover failed");
    return r.json() as Promise<{ cover_url: string }>;
  },
  coverFromPage: (sid: number, chapter_id: number, page: number) =>
    req<{ cover_url: string }>(`/api/series/${sid}/cover/from-page`,
      json({ chapter_id, page })),

  getChapter: (cid: number) => req<ChapterDetail>(`/api/chapters/${cid}`),
  patchChapter: (cid: number, b: { title?: string; number?: number; sort?: number }) =>
    req<{ ok: boolean }>(`/api/chapters/${cid}`, { ...json(b), method: "PATCH" }),
  deleteChapter: (cid: number) =>
    req<{ ok: boolean }>(`/api/chapters/${cid}`, { method: "DELETE" }),

  uploads: (b: { series_id?: number; series_title?: string; chapters: ManifestChapter[] }) =>
    req<ManifestResult>("/api/uploads", json(b)),
  putPage: async (cid: number, idx: number, f: File | Blob, name = "p") => {
    const fd = new FormData();
    fd.append("file", f, name);
    const r = await fetch(`/api/chapters/${cid}/pages/${idx}/file`,
      { method: "PUT", body: fd });
    if (!r.ok) throw new ApiError(r.status, (await r.json()).detail ?? "upload failed");
    return r.json() as Promise<{ ok: boolean }>;
  },

  getBlocks: (cid: number, idx: number) =>
    req<Blocks>(`/api/chapters/${cid}/pages/${idx}/blocks`),
  patchBlock: (cid: number, idx: number, bid: string, translation: string) =>
    req<{ ok: boolean }>(`/api/chapters/${cid}/pages/${idx}/blocks/${bid}`,
      { ...json({ translation }), method: "PATCH" }),

  translate: (cid: number, action: "start" | "pause" | "resume") =>
    req<{ state: string }>(`/api/chapters/${cid}/translate`, json({ action })),
  focus: (cid: number, page: number) =>
    req<{ ok: boolean }>(`/api/chapters/${cid}/focus`, json({ page })),
  reprocessPage: (cid: number, idx: number) =>
    req<{ ok: boolean }>(`/api/chapters/${cid}/pages/${idx}/reprocess`,
      { method: "POST" }),
  reprocessChapter: (cid: number) =>
    req<{ ok: boolean }>(`/api/chapters/${cid}/reprocess`, { method: "POST" }),

  status: () => req<Status>("/api/status"),
  models: () => req<{ id: string; name: string }[]>("/api/models"),
  getSettings: () => req<SettingsDict>("/api/settings"),
  patchSettings: (b: Record<string, unknown>) =>
    req<SettingsDict>("/api/settings", { ...json(b), method: "PATCH" }),

  getProgress: (sid: number) => req<ProgressRow | null>(`/api/progress/${sid}`),
  putProgress: (sid: number, b: Omit<ProgressRow, "series_id">) =>
    req<ProgressRow>(`/api/progress/${sid}`, { ...json(b), method: "PUT" }),
  continueReading: () => req<ContinueRow[]>("/api/continue"),

  pageImage: (cid: number, idx: number, view: string, v = 0) =>
    `/api/chapters/${cid}/pages/${idx}/image?view=${view}&v=${v}`,
};

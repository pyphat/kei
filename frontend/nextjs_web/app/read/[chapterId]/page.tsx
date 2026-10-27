"use client";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import {
  Suspense, useCallback, useEffect, useMemo, useRef, useState,
} from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type PageStatus } from "@/lib/api";
import { deviceId } from "@/lib/device";
import { useEvents } from "@/lib/events";

type View = "original" | "clean" | "translated";
type Zoom = "fit-h" | "fit-w" | number;

const STATUS_COLOR: Record<PageStatus, string> = {
  missing: "bg-neutral-700", pending: "bg-neutral-700", queued: "bg-sky-900",
  processing: "bg-amber-800", processed: "bg-sky-800",
  translated: "bg-emerald-800", failed: "bg-red-800",
};

function Reader() {
  const { chapterId } = useParams<{ chapterId: string }>();
  const cid = Number(chapterId);
  const sp = useSearchParams();
  const qc = useQueryClient();
  const { pageVersion, bumpPage } = useEvents();

  const { data: ch } = useQuery({
    queryKey: ["chapter", cid], queryFn: () => api.getChapter(cid), refetchInterval: 5000,
  });
  const { data: series } = useQuery({
    queryKey: ["series", ch?.series_id], queryFn: () => api.getSeries(ch!.series_id),
    enabled: !!ch,
  });
  const { data: progress } = useQuery({
    queryKey: ["progress", ch?.series_id], queryFn: () => api.getProgress(ch!.series_id),
    enabled: !!ch,
  });

  const count = ch?.pages.length ?? 0;
  const [page, setPage] = useState<number | null>(null);
  const [mode, setMode] = useState<"single" | "continuous">(
    () => (typeof localStorage !== "undefined" &&
      (localStorage.getItem("kei_mode") as "single" | "continuous")) || "single");
  const [rtl, setRtl] = useState(true);
  const [view, setView] = useState<View>("translated");
  const [zoom, setZoom] = useState<Zoom>("fit-h");
  const [editing, setEditing] = useState(false);
  const [selBlock, setSelBlock] = useState<string | null>(null);
  const [imgEl, setImgEl] = useState<HTMLImageElement | null>(null);

  // initial page: ?page= > saved progress > 0 (derived state, set during
  // render once the progress query has resolved — it's undefined while loading)
  if (page === null && ch) {
    const qp = sp.get("page");
    if (qp !== null) setPage(Math.min(Math.max(0, Number(qp)), count - 1));
    else if (progress !== undefined)
      setPage(progress && progress.chapter_id === cid
        ? Math.min(progress.page, count - 1) : 0);
  }

  const cur = page ?? 0;
  const go = useCallback((p: number) => {
    if (count) setPage(Math.min(Math.max(0, p), count - 1));
  }, [count]);

  // debounced focus (300ms) + progress (1s)
  useEffect(() => {
    if (page === null || !ch) return;
    const t1 = setTimeout(() => api.focus(cid, page).catch(() => {}), 300);
    const t2 = setTimeout(() => api.putProgress(ch.series_id, {
      chapter_id: cid, page,
      percent: ((page + 1) / count) * 100,
      device_id: deviceId(), ts: Date.now() / 1000,
    }).catch(() => {}), 1000);
    return () => { clearTimeout(t1); clearTimeout(t2); };
  }, [page, ch, cid, count]);

  // keys (single mode): RTL means Left=next
  useEffect(() => {
    if (mode !== "single") return;
    const h = (e: KeyboardEvent) => {
      const next = rtl ? ["ArrowLeft", "a", "A"] : ["ArrowRight", "d", "D"];
      const prev = rtl ? ["ArrowRight", "d", "D"] : ["ArrowLeft", "a", "A"];
      if (next.includes(e.key)) go(cur + 1);
      else if (prev.includes(e.key)) go(cur - 1);
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [mode, rtl, cur, go]);

  // preload next 2
  useEffect(() => {
    if (mode !== "single") return;
    for (const d of [1, 2]) {
      const i = cur + d;
      if (i < count) {
        const im = new Image();
        im.src = api.pageImage(cid, i, view, pageVersion(cid, i));
      }
    }
  }, [mode, cur, count, cid, view, pageVersion]);

  const tr = useMutation({
    mutationFn: (a: "start" | "pause") => api.translate(cid, a),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["chapter", cid] }),
  });
  const setCover = useMutation({
    mutationFn: () => api.coverFromPage(ch!.series_id, cid, ch!.pages[cur].idx),
  });
  const patchBlock = useMutation({
    mutationFn: ({ bid, text }: { bid: string; text: string }) =>
      api.patchBlock(cid, ch!.pages[cur].idx, bid, text),
    onSuccess: () => {
      bumpPage(cid, ch!.pages[cur].idx);
      qc.invalidateQueries({ queryKey: ["blocks", cid, ch!.pages[cur].idx] });
    },
  });

  const { data: blocks } = useQuery({
    queryKey: ["blocks", cid, ch?.pages[cur]?.idx],
    queryFn: () => api.getBlocks(cid, ch!.pages[cur].idx),
    enabled: editing && !!ch && view !== "original",
  });

  const nextChapter = useMemo(() => {
    if (!series || !ch) return null;
    const sorted = [...series.chapters].sort((a, b) => a.number - b.number);
    const i = sorted.findIndex((c) => c.id === cid);
    return i >= 0 && i < sorted.length - 1 ? sorted[i + 1] : null;
  }, [series, ch, cid]);

  if (!ch || page === null) return <div className="p-8 text-neutral-400">Loading…</div>;
  const pg = ch.pages[cur];
  const v = pageVersion(cid, pg?.idx ?? 0);
  const src = pg ? api.pageImage(cid, pg.idx, view, v) : "";
  const curStatus = pg?.status;

  const zoomCls =
    zoom === "fit-h" ? "h-[calc(100vh-8rem)] w-auto"
    : zoom === "fit-w" ? "w-full h-auto"
    : "";

  return (
    <div className="pt-2">
      {/* toolbar */}
      <div className="mb-2 flex flex-wrap items-center gap-2 rounded bg-neutral-900 px-2 py-1.5 text-xs">
        <Link href={`/series/${ch.series_id}`} className="btn !py-1">← Series</Link>
        <span className="hidden text-neutral-400 sm:inline">ch {ch.number} {ch.title}</span>
        <div className="flex overflow-hidden rounded border border-neutral-700">
          {(["single", "continuous"] as const).map((m) => (
            <button key={m} aria-pressed={mode === m}
                    className={`px-2 py-1 ${mode === m ? "bg-indigo-600" : "bg-neutral-800"}`}
                    onClick={() => { setMode(m); localStorage.setItem("kei_mode", m); }}>
              {m[0].toUpperCase() + m.slice(1)}
            </button>
          ))}
        </div>
        <button className={`btn !py-1 ${rtl ? "bg-neutral-700" : ""}`} aria-pressed={rtl}
                onClick={() => setRtl(!rtl)}>RTL {rtl ? "on" : "off"}</button>
        <select value={view} onChange={(e) => setView(e.target.value as View)} aria-label="View">
          <option value="original">Original</option>
          <option value="clean">Clean</option>
          <option value="translated">Translated</option>
        </select>
        <div className="flex items-center gap-1">
          <button className="btn !px-2 !py-1" aria-label="Zoom out"
                  onClick={() => setZoom((z) => typeof z === "number" ? Math.max(0.25, z - 0.25) : 0.75)}>−</button>
          <select value={typeof zoom === "number" ? "num" : zoom} aria-label="Zoom"
                  onChange={(e) => setZoom(e.target.value === "num" ? 1 : e.target.value as Zoom)}>
            <option value="fit-h">Fit height</option>
            <option value="fit-w">Fit width</option>
            <option value="num">{typeof zoom === "number" ? `${Math.round(zoom * 100)}%` : "100%"}</option>
          </select>
          <button className="btn !px-2 !py-1" aria-label="Zoom in"
                  onClick={() => setZoom((z) => typeof z === "number" ? z + 0.25 : 1.25)}>+</button>
        </div>
        {ch.state === "running"
          ? <button className="btn !py-1" onClick={() => tr.mutate("pause")}>Pause tr.</button>
          : <button className="btn !py-1" onClick={() => tr.mutate("start")}>Translate</button>}
        <button className="btn !py-1" onClick={() => setCover.mutate()}>Set as cover</button>
        {mode === "single" && view !== "original" && (
          <button className={`btn !py-1 ${editing ? "bg-indigo-700" : ""}`} aria-pressed={editing}
                  onClick={() => { setEditing(!editing); setSelBlock(null); }}>
            Edit text
          </button>
        )}
        <span className="ml-auto text-neutral-400">{cur + 1} / {count}</span>
        {curStatus && (
          <span className={`rounded-full px-2 py-0.5 ${STATUS_COLOR[curStatus]}`}>
            {curStatus}
          </span>
        )}
      </div>

      {mode === "single" ? (
        <div className="relative flex justify-center" style={{ direction: rtl ? "rtl" : "ltr" }}>
          <div className="relative inline-block">
            <img ref={setImgEl} src={src} alt={`page ${cur + 1}`}
                 className={`${zoomCls} max-w-none`}
                 style={typeof zoom === "number" ? { width: `${zoom * 100}%` } : undefined} />
            {editing && blocks && imgEl && (
              <BubbleOverlay blocks={blocks} img={imgEl}
                             sel={selBlock} onSel={setSelBlock} />
            )}
          </div>
          {/* click zones: RTL = left half is next; hidden while editing */}
          {!editing && (<>
            <button aria-label="Previous page"
                    className="absolute inset-y-0 left-0 w-1/2"
                    style={{ direction: "ltr", cursor: rtl ? "e-resize" : "w-resize" }}
                    onClick={() => go(rtl ? cur + 1 : cur - 1)} />
            <button aria-label="Next page"
                    className="absolute inset-y-0 right-0 w-1/2"
                    style={{ direction: "ltr", cursor: rtl ? "w-resize" : "e-resize" }}
                    onClick={() => go(rtl ? cur - 1 : cur + 1)} />
          </>)}
          {selBlock && blocks && (
            <BlockEditor
              block={blocks.blocks.find((b) => b.id === selBlock)!}
              onSave={(text) => { patchBlock.mutate({ bid: selBlock, text }); setSelBlock(null); }}
              onClose={() => setSelBlock(null)} />
          )}
        </div>
      ) : (
        <Continuous ch={ch} view={view} cid={cid} pageVersion={pageVersion}
                    onVisible={(i) => setPage(i)} />
      )}

      {mode === "single" && cur >= count - 1 && nextChapter && (
        <div className="py-6 text-center">
          <Link className="btn-primary" href={`/read/${nextChapter.id}`}>
            Next: chapter {nextChapter.number} →
          </Link>
        </div>
      )}
    </div>
  );
}

function BubbleOverlay({ blocks, img, sel, onSel }: {
  blocks: { width: number; height: number; blocks: { id: string; bbox: number[] }[] };
  img: HTMLImageElement; sel: string | null; onSel: (id: string) => void;
}) {
  const [, force] = useState(0);
  useEffect(() => {
    const ro = new ResizeObserver(() => force((x) => x + 1));
    ro.observe(img);
    return () => ro.disconnect();
  }, [img]);
  const sx = img.clientWidth / blocks.width;
  const sy = img.clientHeight / blocks.height;
  if (!sx || !isFinite(sx)) return null;
  return (
    <div className="absolute inset-0">
      {blocks.blocks.map((b) => (
        <button key={b.id} aria-label={`Edit block ${b.id}`}
                onClick={(e) => { e.stopPropagation(); onSel(b.id); }}
                className={`absolute border-2 ${sel === b.id ? "border-yellow-400 bg-yellow-400/20" : "border-red-500/70 bg-red-500/10 hover:bg-red-500/25"}`}
                style={{
                  left: b.bbox[0] * sx, top: b.bbox[1] * sy,
                  width: (b.bbox[2] - b.bbox[0]) * sx,
                  height: (b.bbox[3] - b.bbox[1]) * sy,
                }} />
      ))}
    </div>
  );
}

function BlockEditor({ block, onSave, onClose }: {
  block: { id: string; source: string; translation: string };
  onSave: (t: string) => void; onClose: () => void;
}) {
  const [text, setText] = useState(block.translation);
  return (
    <div className="absolute bottom-4 left-1/2 z-10 w-80 -translate-x-1/2 rounded bg-neutral-900 p-3 shadow-xl"
         style={{ direction: "ltr" }}
         onClick={(e) => e.stopPropagation()}>
      <div className="mb-1 text-xs text-neutral-400">{block.source}</div>
      <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3}
                className="w-full" aria-label="Translation" />
      <div className="mt-1 flex justify-end gap-2">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn-primary" onClick={() => onSave(text)}>Save</button>
      </div>
    </div>
  );
}

function Continuous({ ch, view, cid, pageVersion, onVisible }: {
  ch: { pages: { idx: number; width: number; height: number; status: PageStatus }[] };
  view: string; cid: number; pageVersion: (c: number, i: number) => number;
  onVisible: (i: number) => void;
}) {
  const refs = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    const io = new IntersectionObserver((ents) => {
      for (const e of ents) {
        if (e.isIntersecting) {
          const i = Number((e.target as HTMLElement).dataset.i);
          onVisible(i);
        }
      }
    }, { threshold: 0.5 });
    refs.current.forEach((el) => el && io.observe(el));
    return () => io.disconnect();
  }, [ch.pages.length, onVisible]);

  return (
    <div className="mx-auto max-w-3xl space-y-1">
      {ch.pages.map((p, i) => (
        <div key={p.idx} ref={(el) => { refs.current[i] = el; }} data-i={i}
             className="relative"
             style={{ aspectRatio: p.width && p.height ? `${p.width}/${p.height}` : "2/3" }}>
          <img src={api.pageImage(cid, p.idx, view, pageVersion(cid, p.idx))}
               alt={`page ${i + 1}`} loading="lazy"
               className="absolute inset-0 h-full w-full object-contain" />
          <span className={`absolute right-2 top-2 rounded-full px-2 py-0.5 text-[10px] ${STATUS_COLOR[p.status]}`}>
            {p.status}
          </span>
        </div>
      ))}
    </div>
  );
}

export default function Page() {
  return <Suspense><Reader /></Suspense>;
}

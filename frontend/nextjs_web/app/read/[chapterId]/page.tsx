"use client";

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import {
  Suspense, useCallback, useEffect, useMemo, useRef, useState,
  useSyncExternalStore,
} from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft, BookOpen, ChevronLeft, ChevronRight, Languages, Maximize2,
  Minimize2, Minus, MoreHorizontal, Plus, Pencil, RefreshCw, Search, Settings2,
} from "lucide-react";
import { toast } from "sonner";
import { api, type PageStatus } from "@/lib/api";
import { deviceId } from "@/lib/device";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Slider } from "@/components/ui/slider";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

type View = "original" | "clean" | "translated";
type Zoom = "fit-h" | "fit-w" | number;

const STATUS_VARIANT: Record<PageStatus, "default" | "secondary" | "outline" | "destructive"> = {
  missing: "outline", pending: "outline", queued: "secondary",
  processing: "secondary", processed: "secondary", translated: "default", failed: "destructive",
};
const REPROCESS_WARNING =
  "Re-cleans with current settings and re-translates (uses OpenRouter requests); edited translations are lost.";
type ReaderMode = "single" | "continuous";
type Prefs = { mode: ReaderMode; rtl: boolean; showPage: boolean; view: View };
const DEFAULT_PREFS: Prefs = { mode: "single", rtl: true, showPage: false, view: "translated" };

const readPrefsRaw = () => localStorage.getItem("kei_reader") ?? "";
function parsePrefs(raw: string): Prefs {
  try { return { ...DEFAULT_PREFS, ...JSON.parse(raw) }; } catch { return DEFAULT_PREFS; }
}

function subscribePrefs(onChange: () => void) {
  window.addEventListener("storage", onChange);
  window.addEventListener("kei-reader-change", onChange);
  return () => {
    window.removeEventListener("storage", onChange);
    window.removeEventListener("kei-reader-change", onChange);
  };
}

function savePrefs(patch: Partial<Prefs>) {
  localStorage.setItem("kei_reader", JSON.stringify({ ...parsePrefs(readPrefsRaw()), ...patch }));
  window.dispatchEvent(new Event("kei-reader-change"));
}

function Reader() {
  const { chapterId } = useParams<{ chapterId: string }>();
  const cid = Number(chapterId);
  const sp = useSearchParams();
  const qc = useQueryClient();
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
  const prefsRaw = useSyncExternalStore(subscribePrefs, readPrefsRaw, () => "");
  const prefs = useMemo(() => parsePrefs(prefsRaw), [prefsRaw]);
  const { mode, rtl } = prefs;
  const [viewOverride, setView] = useState<View | null>(null);
  const view = viewOverride ?? prefs.view;
  const [zoom, setZoom] = useState<Zoom>("fit-h");
  const [editing, setEditing] = useState(false);
  const [selBlock, setSelBlock] = useState<string | null>(null);
  const [imgEl, setImgEl] = useState<HTMLImageElement | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [hoveringControls, setHoveringControls] = useState(false);
  const [openMenu, setOpenMenu] = useState<"more" | "settings" | "search" | null>(null);
  const menuOpen = openMenu !== null;
  const [searchQuery, setSearchQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const [reprocessOpen, setReprocessOpen] = useState(false);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  if (page === null && ch) {
    const qp = sp.get("page");
    if (qp !== null) setPage(Math.min(Math.max(0, Number(qp)), count - 1));
    else if (progress !== undefined)
      setPage(progress && progress.chapter_id === cid
        ? Math.min(progress.page, count - 1) : 0);
  }

  const cur = page ?? 0;
  const go = useCallback((next: number) => {
    if (count) setPage(Math.min(Math.max(0, next), count - 1));
  }, [count]);

  const revealControls = useCallback(() => {
    setControlsVisible(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    if (!menuOpen && !reprocessOpen && !editing && !hoveringControls) {
      hideTimer.current = setTimeout(() => setControlsVisible(false), 2500);
    }
  }, [menuOpen, reprocessOpen, editing, hoveringControls]);

  useEffect(() => {
    if (menuOpen || reprocessOpen || editing || hoveringControls) {
      if (hideTimer.current) clearTimeout(hideTimer.current);
    } else if (controlsVisible) {
      if (hideTimer.current) clearTimeout(hideTimer.current);
      hideTimer.current = setTimeout(() => setControlsVisible(false), 2500);
    }
    return () => { if (hideTimer.current) clearTimeout(hideTimer.current); };
  }, [controlsVisible, menuOpen, reprocessOpen, editing, hoveringControls]);

  useEffect(() => {
    if (page === null || !ch) return;
    const focusTimer = setTimeout(() => api.focus(cid, page).catch(() => {}), 300);
    const progressTimer = setTimeout(() => api.putProgress(ch.series_id, {
      chapter_id: cid, page,
      percent: ((page + 1) / count) * 100,
      device_id: deviceId(), ts: Date.now() / 1000,
    }).catch(() => {}), 1000);
    return () => { clearTimeout(focusTimer); clearTimeout(progressTimer); };
  }, [page, ch, cid, count]);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setControlsVisible(menuOpen || reprocessOpen || editing ? true : false);
        return;
      }
      if (mode !== "single" || editing || menuOpen || reprocessOpen) return;
      const next = rtl ? ["ArrowLeft", "a", "A"] : ["ArrowRight", "d", "D"];
      const previous = rtl ? ["ArrowRight", "d", "D"] : ["ArrowLeft", "a", "A"];
      if (next.includes(e.key)) { e.preventDefault(); revealControls(); go(cur + 1); }
      else if (previous.includes(e.key)) { e.preventDefault(); revealControls(); go(cur - 1); }
    };
    window.addEventListener("keydown", handleKey, true);
    return () => window.removeEventListener("keydown", handleKey, true);
  }, [mode, rtl, editing, menuOpen, reprocessOpen, cur, go, revealControls]);

  useEffect(() => {
    const pages = ch?.pages;
    if (mode !== "single" || !pages) return;
    for (const offset of [1, 2]) {
      const next = pages[cur + offset];
      if (next) {
        const image = new Image();
        image.src = api.pageImage(cid, next.idx, view, next.rev);
      }
    }
  }, [mode, cur, cid, view, ch?.pages]);

  const tr = useMutation({
    mutationFn: (action: "start" | "pause") => api.translate(cid, action),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["chapter", cid] }),
    onError: (error) => toast.error(error instanceof Error ? error.message : String(error)),
  });
  const setCover = useMutation({
    mutationFn: () => api.coverFromPage(ch!.series_id, cid, ch!.pages[cur].idx),
    onSuccess: () => toast.success("Cover updated"),
    onError: (error) => toast.error(error instanceof Error ? error.message : String(error)),
  });
  const reprocess = useMutation({
    mutationFn: () => api.reprocessPage(cid, ch!.pages[cur].idx),
    onSuccess: () => {
      setReprocessOpen(false);
      qc.invalidateQueries({ queryKey: ["chapter", cid] });
      toast.success("Page queued for reprocessing");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : String(error)),
  });
  const patchBlock = useMutation({
    mutationFn: ({ bid, text }: { bid: string; text: string }) =>
      api.patchBlock(cid, ch!.pages[cur].idx, bid, text),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["chapter", cid] });
      qc.invalidateQueries({ queryKey: ["blocks", cid, ch!.pages[cur].idx] });
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : String(error)),
  });

  const { data: blocks } = useQuery({
    queryKey: ["blocks", cid, ch?.pages[cur]?.idx],
    queryFn: () => api.getBlocks(cid, ch!.pages[cur].idx),
    enabled: editing && !!ch && view !== "original",
  });
  const { data: searchResults } = useQuery({
    queryKey: ["search", cid, submitted],
    queryFn: () => api.searchChapter(cid, submitted),
    enabled: submitted.trim().length > 0,
  });

  const nextChapter = useMemo(() => {
    if (!series || !ch) return null;
    const sorted = [...series.chapters].sort((a, b) => a.number - b.number);
    const i = sorted.findIndex((chapter) => chapter.id === cid);
    return i >= 0 && i < sorted.length - 1 ? sorted[i + 1] : null;
  }, [series, ch, cid]);

  if (!ch || page === null) {
    return <div className="fixed inset-0 grid place-items-center bg-neutral-950 text-sm text-white/60">Loading reader…</div>;
  }
  const pg = ch.pages[cur];
  const src = pg ? api.pageImage(cid, pg.idx, view, pg.rev) : "";
  const zoomClass = zoom === "fit-h"
    ? "max-h-[calc(100dvh-10rem)] max-w-[92vw]"
    : "max-h-[calc(100dvh-10rem)] max-w-none";
  const imageStyle = zoom === "fit-w" ? { width: "min(92vw, 64rem)" }
    : typeof zoom === "number" ? { width: `${(imgEl?.naturalWidth ?? 844) * zoom}px` }
    : undefined;

  return (
    <div className="fixed inset-0 z-50 overflow-hidden bg-neutral-950 text-neutral-100"
         onPointerMove={revealControls}>
      {mode === "single" ? (
        <div className="relative flex h-full items-center justify-center overflow-hidden">
          {pg && (
            <div className="relative flex max-h-full max-w-full items-center justify-center">
              <img ref={setImgEl} src={src} alt={`page ${cur + 1}`}
                   className={`${zoomClass} object-contain shadow-2xl shadow-black/30`}
                   style={imageStyle} />
              {editing && blocks && imgEl && (
                <BubbleOverlay blocks={blocks} img={imgEl} sel={selBlock} onSel={setSelBlock} />
              )}
            </div>
          )}
          {!editing && (
            <>
              <button aria-label={rtl ? "Next page" : "Previous page"}
                      className="absolute inset-y-0 left-0 z-10 w-1/3 cursor-pointer"
                      onClick={() => { revealControls(); go(rtl ? cur + 1 : cur - 1); }} />
              <button aria-label="Show reader controls"
                      className="absolute inset-y-0 left-1/3 z-10 w-1/3 cursor-pointer"
                      onClick={revealControls} />
              <button aria-label={rtl ? "Previous page" : "Next page"}
                      className="absolute inset-y-0 right-0 z-10 w-1/3 cursor-pointer"
                      onClick={() => { revealControls(); go(rtl ? cur - 1 : cur + 1); }} />
            </>
          )}
          {selBlock && blocks && (
            <BlockEditor block={blocks.blocks.find((b) => b.id === selBlock)!}
                         onSave={(text) => { patchBlock.mutate({ bid: selBlock, text }); setSelBlock(null); }}
                         onClose={() => setSelBlock(null)} />
          )}
        </div>
      ) : (
        <Continuous ch={ch} view={view} cid={cid} onVisible={(i) => setPage(i)} />
      )}

      <div className={`pointer-events-none absolute inset-x-0 top-0 z-30 transition duration-300 ${
        controlsVisible ? "translate-y-0 opacity-100" : "-translate-y-full opacity-0"
      }`}>
        <div className="pointer-events-auto flex items-center gap-2 border-b border-white/10 bg-neutral-950/80 px-3 py-3 backdrop-blur-xl sm:px-5"
             onMouseEnter={() => { setHoveringControls(true); revealControls(); }}
             onMouseLeave={() => setHoveringControls(false)}>
          <Button asChild size="icon" variant="ghost" aria-label="Back to series" className="text-white hover:bg-white/10 hover:text-white">
            <Link href={`/series/${ch.series_id}`}><ArrowLeft /></Link>
          </Button>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold">{series?.title ?? "kei"}</p>
            <p className="truncate text-xs text-white/55">Chapter {ch.number}{ch.title ? ` · ${ch.title}` : ""}</p>
          </div>
          <Button size="icon" variant="ghost" aria-label="Search text"
                  className="text-white hover:bg-white/10 hover:text-white"
                  onClick={() => setOpenMenu("search")}>
            <Search />
          </Button>
          <Badge variant={STATUS_VARIANT[pg?.status ?? "pending"]}
                 className="max-w-20 truncate px-1.5 text-[10px] capitalize sm:max-w-none sm:px-2">{pg?.status ?? "pending"}</Badge>
          <span className="min-w-14 text-right text-sm tabular-nums text-white/75">{cur + 1} / {count}</span>
        </div>
      </div>

      {prefs.showPage && !controlsVisible && (
        <span className="pointer-events-none absolute bottom-3 right-3 z-20 rounded-full bg-black/60 px-2.5 py-1 text-xs tabular-nums text-white/80">
          {cur + 1} / {count}
        </span>
      )}

      {nextChapter && cur >= count - 1 && (
        <Link className="absolute bottom-28 left-1/2 z-20 -translate-x-1/2 rounded-full bg-primary px-4 py-2 text-sm font-medium text-primary-foreground shadow-xl"
              href={`/read/${nextChapter.id}`}>
          Next chapter <ChevronRight className="ml-1 inline size-4" />
        </Link>
      )}

      <div className={`pointer-events-none absolute inset-x-0 bottom-0 z-30 transition duration-300 ${
        controlsVisible ? "translate-y-0 opacity-100" : "translate-y-full opacity-0"
      }`}>
        <div className="pointer-events-auto space-y-3 border-t border-white/10 bg-neutral-950/85 px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 backdrop-blur-xl sm:px-5"
             onMouseEnter={() => { setHoveringControls(true); revealControls(); }}
             onMouseLeave={() => setHoveringControls(false)}>
          <div className="flex items-center gap-2">
            <Button size="icon" variant="ghost" aria-label={rtl ? "Next page" : "Previous page"}
                    className="shrink-0 text-white hover:bg-white/10 hover:text-white"
                    onClick={() => go(rtl ? cur + 1 : cur - 1)}>
              <ChevronLeft />
            </Button>
            <Slider aria-label="Page" dir={rtl ? "rtl" : "ltr"} min={0} max={Math.max(count - 1, 0)}
                    step={1} value={[cur]} onValueChange={(value) => go(value[0] ?? 0)}
                    className="min-w-0 flex-1 px-1" />
            <Button size="icon" variant="ghost" aria-label={rtl ? "Previous page" : "Next page"}
                    className="shrink-0 text-white hover:bg-white/10 hover:text-white"
                    onClick={() => go(rtl ? cur - 1 : cur + 1)}>
              <ChevronRight />
            </Button>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2">
            <ToggleGroup type="single" value={view}
                         onValueChange={(value) => value && setView(value as View)}
                         aria-label="View" className="rounded-lg bg-white/5 p-0.5"
                         variant="outline" size="sm" spacing={0}>
              <ToggleGroupItem value="original" aria-label="Original" className="h-7 px-2 text-xs text-white data-[state=on]:bg-white/15 data-[state=on]:text-white">Original</ToggleGroupItem>
              <ToggleGroupItem value="clean" aria-label="Clean" className="h-7 px-2 text-xs text-white data-[state=on]:bg-white/15 data-[state=on]:text-white">Clean</ToggleGroupItem>
              <ToggleGroupItem value="translated" aria-label="Translated" className="h-7 px-2 text-xs text-white data-[state=on]:bg-white/15 data-[state=on]:text-white">Translated</ToggleGroupItem>
            </ToggleGroup>
            <div className="flex flex-wrap items-center gap-2">
              <div className="flex items-center gap-0.5 rounded-lg bg-white/5 p-0.5">
                <Button size="icon" variant="ghost" aria-label="Fit height" className="size-7 text-white hover:bg-white/10 hover:text-white"
                        onClick={() => setZoom("fit-h")}><Minimize2 /></Button>
                <Button size="icon" variant="ghost" aria-label="Fit width" className="size-7 text-white hover:bg-white/10 hover:text-white"
                        onClick={() => setZoom("fit-w")}><Maximize2 /></Button>
                <Button size="icon" variant="ghost" aria-label="Zoom out" className="size-7 text-white hover:bg-white/10 hover:text-white"
                        onClick={() => setZoom((z) => typeof z === "number" ? Math.max(0.25, z - 0.25) : 0.75)}>
                  <Minus />
                </Button>
                <span className="min-w-12 text-center text-[10px] text-white/60">
                  {typeof zoom === "number" ? `${Math.round(zoom * 100)}%` : zoom === "fit-h" ? "Fit height" : "Fit width"}
                </span>
                <Button size="icon" variant="ghost" aria-label="Zoom in" className="size-7 text-white hover:bg-white/10 hover:text-white"
                        onClick={() => setZoom((z) => typeof z === "number" ? z + 0.25 : 1.25)}>
                  <Plus />
                </Button>
              </div>
              <DropdownMenu open={openMenu === "settings"} onOpenChange={(open) => {
                setOpenMenu(open ? "settings" : null);
                if (open) revealControls();
              }}>
                <DropdownMenuTrigger asChild>
                  <Button size="icon" variant="ghost" aria-label="Reader settings"
                          className="size-7 text-white hover:bg-white/10 hover:text-white">
                    <Settings2 />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" side="top" className="min-w-56">
                  <DropdownMenuLabel>Reading direction</DropdownMenuLabel>
                  <DropdownMenuRadioGroup value={rtl ? "rtl" : "ltr"}
                                          onValueChange={(value) => savePrefs({ rtl: value === "rtl" })}>
                    <DropdownMenuRadioItem value="rtl" onSelect={(e) => e.preventDefault()}>
                      Right to left
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="ltr" onSelect={(e) => e.preventDefault()}>
                      Left to right
                    </DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                  <DropdownMenuSeparator />
                  <DropdownMenuLabel>Layout</DropdownMenuLabel>
                  <DropdownMenuRadioGroup value={mode}
                                          onValueChange={(value) => savePrefs({ mode: value as ReaderMode })}>
                    <DropdownMenuRadioItem value="single" onSelect={(e) => e.preventDefault()}>
                      Single
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="continuous" onSelect={(e) => e.preventDefault()}>
                      Continuous
                    </DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                  <DropdownMenuSeparator />
                  <DropdownMenuLabel>Default view</DropdownMenuLabel>
                  <DropdownMenuRadioGroup value={prefs.view}
                                          onValueChange={(value) => {
                                            savePrefs({ view: value as View });
                                            setView(null);
                                          }}>
                    <DropdownMenuRadioItem value="original" onSelect={(e) => e.preventDefault()}>
                      Original
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="clean" onSelect={(e) => e.preventDefault()}>
                      Clean
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="translated" onSelect={(e) => e.preventDefault()}>
                      Translated
                    </DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                  <DropdownMenuSeparator />
                  <DropdownMenuCheckboxItem checked={prefs.showPage}
                                            onSelect={(e) => e.preventDefault()}
                                            onCheckedChange={(checked) => savePrefs({ showPage: checked === true })}>
                    Always show page number
                  </DropdownMenuCheckboxItem>
                </DropdownMenuContent>
              </DropdownMenu>
              <DropdownMenu open={openMenu === "more"} onOpenChange={(open) => {
                setOpenMenu(open ? "more" : null);
                if (open) revealControls();
              }}>
                <DropdownMenuTrigger asChild>
                  <Button size="icon" variant="secondary" aria-label="More actions"
                          className="size-8 bg-white/10 text-white hover:bg-white/20">
                    <MoreHorizontal />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" side="top" className="min-w-48">
                  <DropdownMenuItem onSelect={() => tr.mutate(ch.state === "running" ? "pause" : "start")}>
                    <Languages />{ch.state === "running" ? "Pause translation" : "Translate chapter"}
                  </DropdownMenuItem>
                  {mode === "single" && view !== "original" && (
                    <DropdownMenuItem onSelect={() => {
                      revealControls();
                      setEditing((active) => !active);
                      setSelBlock(null);
                    }}>
                      <Pencil />{editing ? "Stop editing text" : "Edit text"}
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuItem onSelect={() => setCover.mutate()}><BookOpen />Set as cover</DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onSelect={() => { revealControls(); setReprocessOpen(true); }}>
                    <RefreshCw />Reprocess page
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </div>
        </div>
      </div>

      <Dialog open={openMenu === "search"} onOpenChange={(open) => setOpenMenu(open ? "search" : null)}>
        <DialogContent className="gap-3 sm:max-w-lg">
          <DialogTitle>Search text</DialogTitle>
          <form className="flex gap-2" onSubmit={(e) => {
            e.preventDefault();
            setSubmitted(searchQuery);
          }}>
            <Input autoFocus aria-label="Search query" placeholder="Find text in this chapter…"
                   value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
            <Button type="submit">Search</Button>
          </form>
          <p className="text-xs text-muted-foreground">Only processed pages are searchable.</p>
          {searchResults?.length === 0 && (
            <p className="text-sm text-muted-foreground">No matches</p>
          )}
          <div className="max-h-80 space-y-1 overflow-y-auto">
            {searchResults?.map((hit) => {
              const i = ch.pages.findIndex((p) => p.idx === hit.idx);
              return (
                <button key={`${hit.idx}-${hit.block_id}`} type="button"
                        className="w-full rounded-md px-2 py-1.5 text-left hover:bg-accent"
                        onClick={() => {
                          go(i);
                          setOpenMenu(null);
                          if (mode === "continuous")
                            document.querySelector(`[data-i="${i}"]`)?.scrollIntoView();
                        }}>
                  <span className="block text-xs text-muted-foreground">Page {i + 1}</span>
                  <span className="line-clamp-2 block text-sm">{hit.translation || hit.source}</span>
                  {hit.translation && (
                    <span className="line-clamp-2 block text-xs text-muted-foreground">{hit.source}</span>
                  )}
                </button>
              );
            })}
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={reprocessOpen} onOpenChange={setReprocessOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Reprocess this page?</AlertDialogTitle>
            <AlertDialogDescription>{REPROCESS_WARNING}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction disabled={reprocess.isPending} onClick={(e) => {
              e.preventDefault();
              reprocess.mutate();
            }}>Reprocess page</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function BubbleOverlay({ blocks, img, sel, onSel }: {
  blocks: { width: number; height: number; blocks: { id: string; bbox: number[] }[] };
  img: HTMLImageElement; sel: string | null; onSel: (id: string) => void;
}) {
  const [, force] = useState(0);
  useEffect(() => {
    const resize = new ResizeObserver(() => force((value) => value + 1));
    resize.observe(img);
    return () => resize.disconnect();
  }, [img]);
  const sx = img.clientWidth / blocks.width;
  const sy = img.clientHeight / blocks.height;
  if (!sx || !Number.isFinite(sx)) return null;
  return (
    <div className="absolute inset-0">
      {blocks.blocks.map((block) => (
        <button key={block.id} aria-label={`Edit block ${block.id}`}
                onClick={(e) => { e.stopPropagation(); onSel(block.id); }}
                className={`absolute border-2 ${sel === block.id ? "border-amber-300 bg-amber-300/20" : "border-primary/80 bg-primary/10 hover:bg-primary/25"}`}
                style={{
                  left: block.bbox[0] * sx, top: block.bbox[1] * sy,
                  width: (block.bbox[2] - block.bbox[0]) * sx,
                  height: (block.bbox[3] - block.bbox[1]) * sy,
                }} />
      ))}
    </div>
  );
}

function BlockEditor({ block, onSave, onClose }: {
  block: { id: string; source: string; translation: string };
  onSave: (text: string) => void; onClose: () => void;
}) {
  const [text, setText] = useState(block.translation);
  return (
    <div className="fixed bottom-32 left-1/2 z-40 w-[min(24rem,calc(100vw-2rem))] -translate-x-1/2 rounded-xl border border-white/10 bg-neutral-900 p-4 text-white shadow-2xl"
         style={{ direction: "ltr" }}>
      <p className="mb-2 text-xs text-white/60">{block.source}</p>
      <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3}
                className="w-full resize-y rounded-lg border border-white/10 bg-black/30 p-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-primary"
                aria-label="Translation" />
      <div className="mt-3 flex justify-end gap-2">
        <Button size="sm" variant="ghost" className="text-white hover:bg-white/10 hover:text-white" onClick={onClose}>Cancel</Button>
        <Button size="sm" onClick={() => onSave(text)}>Save text</Button>
      </div>
    </div>
  );
}

function Continuous({ ch, view, cid, onVisible }: {
  ch: { pages: { idx: number; width: number; height: number; status: PageStatus; rev: number }[] };
  view: string; cid: number; onVisible: (i: number) => void;
}) {
  const refs = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) onVisible(Number((entry.target as HTMLElement).dataset.i));
      }
    }, { threshold: 0.5 });
    refs.current.forEach((element) => element && observer.observe(element));
    return () => observer.disconnect();
  }, [ch.pages.length, onVisible]);

  return (
    <div className="absolute inset-0 overflow-y-auto overscroll-contain bg-neutral-950 px-2 pb-32 pt-20 sm:px-6">
      <div className="mx-auto max-w-5xl space-y-4">
        {ch.pages.map((page, i) => (
          <div key={page.idx} ref={(element) => { refs.current[i] = element; }}
               data-i={i} className="relative mx-auto"
               style={{ aspectRatio: page.width && page.height ? `${page.width}/${page.height}` : "2/3" }}>
            <img src={api.pageImage(cid, page.idx, view, page.rev)}
                 alt={`page ${i + 1}`} loading="lazy"
                 className="absolute inset-0 size-full object-contain" />
            <Badge variant={STATUS_VARIANT[page.status]}
                   className="absolute right-2 top-2 capitalize">{page.status}</Badge>
          </div>
        ))}
      </div>
    </div>
  );
}

export default function Page() {
  return <Suspense><Reader /></Suspense>;
}

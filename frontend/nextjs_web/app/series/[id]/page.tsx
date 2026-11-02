"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Suspense, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft, BookOpen, MoreHorizontal, Pencil, Play, Plus, RefreshCw, Trash2, Languages,
} from "lucide-react";
import { toast } from "sonner";
import { api, type ChapterSummary } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";

const REPROCESS_WARNING =
  "Re-cleans with current settings and re-translates (uses OpenRouter requests); edited translations are lost.";

export default function Page() {
  return <Suspense><SeriesPage /></Suspense>;
}

function SeriesPage() {
  const { id } = useParams<{ id: string }>();
  const sid = Number(id);
  const qc = useQueryClient();
  const router = useRouter();
  const { data: series } = useQuery({
    queryKey: ["series", sid], queryFn: () => api.getSeries(sid), refetchInterval: 5000,
  });
  const fileRef = useRef<HTMLInputElement>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [edit, setEdit] = useState({ title: "", author: "", tags: "" });
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["series"] });
    qc.invalidateQueries({ queryKey: ["series", sid] });
  };
  const patch = useMutation({
    mutationFn: () => api.patchSeries(sid, {
      title: edit.title.trim(), author: edit.author.trim(),
      tags: edit.tags.split(",").map((tag) => tag.trim()).filter(Boolean),
    }),
    onSuccess: () => { setEditOpen(false); invalidate(); toast.success("Series details saved"); },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });
  const cover = useMutation({
    mutationFn: (file: File) => api.uploadCover(sid, file),
    onSuccess: invalidate,
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });
  const translateAll = useMutation({
    mutationFn: async () => {
      for (const chapter of series?.chapters ?? []) await api.translate(chapter.id, "start");
    },
    onSuccess: () => { invalidate(); toast.success("Translation queued for all chapters"); },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });
  const del = useMutation({
    mutationFn: () => api.deleteSeries(sid),
    onSuccess: () => { setDeleteOpen(false); router.push("/"); },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });

  if (!series) return <SeriesSkeleton />;
  const resume = series.progress
    ? `/read/${series.progress.chapter_id}?page=${series.progress.page}`
    : series.chapters[0] ? `/read/${series.chapters[0].id}` : `/upload?series=${sid}`;

  return (
    <div className="mx-auto max-w-7xl space-y-8 px-4 py-7 sm:px-6">
      <section className="relative isolate overflow-hidden rounded-3xl border bg-card">
        {series.cover_url && (
          <img src={series.cover_url} alt="" aria-hidden="true"
               className="absolute inset-0 -z-20 size-full scale-110 object-cover opacity-20 blur-3xl" />
        )}
        <div className="absolute inset-0 -z-10 bg-gradient-to-r from-background via-background/90 to-background/60" />
        <div className="grid gap-6 p-5 sm:grid-cols-[180px_1fr] sm:gap-8 sm:p-8">
          <button aria-label="Change cover" title="Change cover"
                  onClick={() => fileRef.current?.click()}
                  className="aspect-[2/3] w-36 overflow-hidden rounded-xl bg-muted shadow-2xl shadow-black/25 ring-1 ring-border sm:w-full">
            {series.cover_url ? <img src={series.cover_url} alt={series.title} className="size-full object-cover" />
              : <div className="flex size-full flex-col items-center justify-center gap-3 p-4 text-center text-sm text-muted-foreground">
                  <BookOpen className="size-8" />Add cover
                </div>}
          </button>
          <input ref={fileRef} type="file" accept="image/*" className="hidden" aria-label="Upload cover"
                 onChange={(e) => {
                   const file = e.target.files?.[0];
                   if (file) cover.mutate(file);
                   e.target.value = "";
                 }} />
          <div className="flex min-w-0 flex-col justify-center">
            <Button asChild variant="ghost" size="sm" className="mb-5 w-fit -ml-2 text-muted-foreground">
              <Link href="/"><ArrowLeft />Library</Link>
            </Button>
            <div className="flex flex-wrap items-center gap-2">
              {series.tags.slice(0, 5).map((tag) => <Badge key={tag} variant="secondary">{tag}</Badge>)}
            </div>
            <h1 className="mt-3 break-words text-3xl font-bold tracking-tight sm:text-4xl">{series.title}</h1>
            <p className="mt-2 text-sm text-muted-foreground">{series.author || "Unknown author"}</p>
            <p className="mt-4 text-sm text-muted-foreground">
              {series.chapter_count} chapters <span className="px-1">·</span> {series.page_count} pages
            </p>
            <div className="mt-6 flex flex-wrap gap-2">
              <Button asChild>
                <Link href={resume}><Play />Continue reading</Link>
              </Button>
              <Button variant="secondary" onClick={() => translateAll.mutate()}
                      disabled={translateAll.isPending || !series.chapters.length}>
                <Languages />Translate all
              </Button>
              <Button asChild variant="outline">
                <Link href={`/upload?series=${sid}`}><Plus />Add chapters</Link>
              </Button>
              <Button variant="outline" onClick={() => {
                setEdit({ title: series.title, author: series.author, tags: series.tags.join(", ") });
                setEditOpen(true);
              }}>
                <Pencil />Edit details
              </Button>
              <Button variant="ghost" aria-label="Delete series" onClick={() => setDeleteOpen(true)}>
                <Trash2 />
              </Button>
            </div>
          </div>
        </div>
      </section>

      <Separator />

      <section className="space-y-4">
        <div className="flex items-end justify-between gap-4">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">In this collection</p>
            <h2 className="mt-1 text-2xl font-semibold tracking-tight">Chapters</h2>
          </div>
          <span className="text-xs text-muted-foreground">{series.chapters.length} total</span>
        </div>
        <div className="space-y-3">
          {series.chapters.map((chapter) => (
            <ChapterRow key={chapter.id} chapter={chapter}
                        progress={series.progress?.chapter_id === chapter.id ? series.progress : null}
                        invalidate={invalidate} />
          ))}
          {!series.chapters.length && (
            <div className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
              No chapters yet. <Link className="text-primary underline underline-offset-4" href={`/upload?series=${sid}`}>Add the first one</Link>
            </div>
          )}
        </div>
      </section>

      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent>
          <form onSubmit={(e) => { e.preventDefault(); patch.mutate(); }}>
            <DialogHeader>
              <DialogTitle>Edit series</DialogTitle>
              <DialogDescription>Update the details shown on your shelf.</DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-5">
              <label className="grid gap-2 text-sm font-medium">Title
                <Input value={edit.title} onChange={(e) => setEdit({ ...edit, title: e.target.value })} />
              </label>
              <label className="grid gap-2 text-sm font-medium">Author
                <Input value={edit.author} onChange={(e) => setEdit({ ...edit, author: e.target.value })} />
              </label>
              <label className="grid gap-2 text-sm font-medium">Tags <span className="text-xs font-normal text-muted-foreground">Comma separated</span>
                <Input value={edit.tags} onChange={(e) => setEdit({ ...edit, tags: e.target.value })} />
              </label>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setEditOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={!edit.title.trim() || patch.isPending}>Save changes</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this series?</AlertDialogTitle>
            <AlertDialogDescription>This removes {series.title} and all its chapters from the library.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={(e) => { e.preventDefault(); del.mutate(); }}>
              Delete series
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function SeriesSkeleton() {
  return (
    <div className="mx-auto max-w-7xl space-y-8 px-4 py-8 sm:px-6">
      <Skeleton className="h-72 rounded-3xl" />
      <Skeleton className="h-24 rounded-xl" />
      <Skeleton className="h-24 rounded-xl" />
    </div>
  );
}

function ChapterRow({ chapter, progress, invalidate }: {
  chapter: ChapterSummary;
  progress: { page: number; percent: number } | null;
  invalidate: () => void;
}) {
  const [reprocessOpen, setReprocessOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const del = useMutation({
    mutationFn: () => api.deleteChapter(chapter.id),
    onSuccess: () => { setDeleteOpen(false); invalidate(); toast.success("Chapter deleted"); },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });
  const tr = useMutation({
    mutationFn: (action: "start" | "pause") => api.translate(chapter.id, action),
    onSuccess: invalidate,
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });
  const readHref = `/read/${chapter.id}${progress ? `?page=${progress.page}` : ""}`;
  const status = chapter.state;

  return (
    <div className="grid gap-4 rounded-2xl border bg-card/70 p-4 shadow-sm transition hover:border-primary/30 sm:grid-cols-[minmax(180px,1fr)_minmax(220px,1.1fr)_auto] sm:items-center sm:p-5">
      <div className="flex min-w-0 items-center gap-3">
        <div className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary/10 font-semibold text-primary">
          {chapter.number}
        </div>
        <div className="min-w-0">
          <p className="truncate font-medium">{chapter.title || `Chapter ${chapter.number}`}</p>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <span>{chapter.page_count} pages</span>
            {progress && <span>· page {progress.page + 1} ({Math.round(progress.percent)}%)</span>}
            <Badge variant={status === "running" ? "default" : status === "paused" ? "secondary" : "outline"}
                   className="h-5 px-1.5 text-[10px]">{status}</Badge>
          </div>
        </div>
      </div>
      <div className="space-y-2">
        <CountProgress label="Uploaded" value={chapter.present_count} total={chapter.page_count} />
        <CountProgress label="Processed" value={chapter.processed_count} total={chapter.page_count} />
        <CountProgress label="Translated" value={chapter.translated_count} total={chapter.page_count} />
      </div>
      <div className="flex items-center gap-2">
        <Button asChild size="sm">
          <Link href={readHref}><BookOpen />Read</Link>
        </Button>
        <Button variant="outline" size="sm" className="hidden sm:inline-flex"
                onClick={() => tr.mutate(status === "running" ? "pause" : "start")}>
          {status === "running" ? "Pause" : "Translate"}
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button size="icon" variant="ghost" aria-label={`Chapter ${chapter.number} actions`}>
              <MoreHorizontal />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem className="sm:hidden"
              onSelect={() => tr.mutate(status === "running" ? "pause" : "start")}>
              {status === "running" ? "Pause translation" : "Translate"}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => setReprocessOpen(true)}><RefreshCw />Reprocess</DropdownMenuItem>
            <DropdownMenuItem variant="destructive" onSelect={() => setDeleteOpen(true)}><Trash2 />Delete chapter</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <AlertDialog open={reprocessOpen} onOpenChange={setReprocessOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Reprocess chapter?</AlertDialogTitle>
            <AlertDialogDescription>{REPROCESS_WARNING}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={(e) => {
              e.preventDefault();
              api.reprocessChapter(chapter.id).then(() => {
                setReprocessOpen(false);
                invalidate();
                toast.success("Chapter queued for reprocessing");
              }).catch((error: unknown) => toast.error(error instanceof Error ? error.message : String(error)));
            }}>Reprocess</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete chapter?</AlertDialogTitle>
            <AlertDialogDescription>This removes this chapter and its cached pages.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={(e) => { e.preventDefault(); del.mutate(); }}>
              Delete chapter
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function CountProgress({ label, value, total }: { label: string; value: number; total: number }) {
  const pct = total ? (value / total) * 100 : 0;
  return (
    <div className="grid grid-cols-[62px_1fr_42px] items-center gap-2 text-[10px] text-muted-foreground">
      <span>{label}</span><Progress value={pct} className="h-1.5" />
      <span className="text-right">{value}/{total}</span>
    </div>
  );
}

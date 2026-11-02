"use client";

import Link from "next/link";
import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpRight, BookOpen, MoreHorizontal, Search, Upload } from "lucide-react";
import { toast } from "sonner";
import { api, type Series } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  Card, CardContent, CardFooter,
} from "@/components/ui/card";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";

export default function LibraryPage() {
  const { data: series } = useQuery({ queryKey: ["series"], queryFn: api.listSeries });
  const { data: cont } = useQuery({ queryKey: ["continue"], queryFn: api.continueReading });
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<"recent" | "title">("recent");

  const shown = useMemo(() => {
    let list = (series ?? []).filter((item) =>
      item.title.toLowerCase().includes(q.toLowerCase()));
    if (sort === "title") list = [...list].sort((a, b) => a.title.localeCompare(b.title));
    return list;
  }, [series, q, sort]);

  return (
    <div className="mx-auto max-w-7xl space-y-10 px-4 py-8 sm:px-6 lg:py-10">
      {cont?.length ? (
        <section aria-labelledby="continue-title" className="space-y-4">
          <div className="flex items-end justify-between">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Pick up where you left off</p>
              <h2 id="continue-title" className="mt-1 text-xl font-semibold tracking-tight">Continue reading</h2>
            </div>
            <span className="text-xs text-muted-foreground">{cont.length} recent</span>
          </div>
          <div className="flex snap-x gap-3 overflow-x-auto pb-2">
            {cont.map((r) => (
              <Link key={r.series_id}
                    href={`/read/${r.chapter_id}?page=${r.page}`}
                    className="group flex min-w-64 snap-start items-center gap-4 rounded-xl border bg-card p-3 shadow-sm transition hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-lg sm:min-w-72">
                <div className="h-20 w-14 shrink-0 overflow-hidden rounded-md bg-muted">
                  {r.cover_url ? <img src={r.cover_url} alt="" className="size-full object-cover" />
                    : <BookOpen className="m-auto mt-7 size-5 text-muted-foreground" />}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium">{r.series_title}</p>
                  <p className="mt-1 truncate text-xs text-muted-foreground">
                    Chapter {r.chapter_number} · page {r.page + 1}
                  </p>
                  <Progress value={r.percent} className="mt-3 h-1.5" />
                </div>
                <ArrowUpRight className="size-4 shrink-0 text-muted-foreground transition group-hover:text-primary" />
              </Link>
            ))}
          </div>
        </section>
      ) : null}

      <section className="space-y-6">
        <div className="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Your collection</p>
            <h1 className="mt-1 text-3xl font-semibold tracking-tight">Library</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              {series ? `${series.length} series on your shelf` : "Your manga, all in one place"}
            </p>
          </div>
          <div className="flex gap-2">
            <div className="relative min-w-0 flex-1 sm:w-56">
              <Search className="pointer-events-none absolute left-3 top-2.5 size-4 text-muted-foreground" />
              <Input aria-label="Search" placeholder="Search your library"
                     value={q} onChange={(e) => setQ(e.target.value)} className="pl-9"
                     suppressHydrationWarning />
            </div>
            <Select value={sort} onValueChange={(v) => setSort(v as "recent" | "title")}>
              <SelectTrigger aria-label="Sort" className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="recent">Recent</SelectItem>
                <SelectItem value="title">Title</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        {!series ? (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5 xl:grid-cols-6">
            {Array.from({ length: 6 }, (_, i) => (
              <Card key={i} className="overflow-hidden border-0 bg-transparent shadow-none">
                <Skeleton className="aspect-[2/3] w-full rounded-xl" />
                <Skeleton className="mt-3 h-4 w-3/4" />
                <Skeleton className="mt-2 h-3 w-1/2" />
              </Card>
            ))}
          </div>
        ) : shown.length ? (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
            {shown.map((s) => <SeriesCard key={s.id} series={s} />)}
          </div>
        ) : (
          <Card className="border-dashed bg-card/50">
            <CardContent className="flex flex-col items-center px-6 py-16 text-center">
              <div className="mb-4 flex size-14 items-center justify-center rounded-2xl bg-primary/10 text-primary">
                {series.length ? <Search className="size-6" /> : <BookOpen className="size-6" />}
              </div>
              <h2 className="text-lg font-semibold">{series.length ? "No matches" : "Your shelf is waiting"}</h2>
              <p className="mt-1 max-w-sm text-sm text-muted-foreground">
                {series.length ? "Try a different title." : "Bring your manga here to build a personal reading library."}
              </p>
              {!series.length && (
                <Button asChild className="mt-5">
                  <Link href="/upload"><Upload />Upload manga</Link>
                </Button>
              )}
            </CardContent>
          </Card>
        )}
      </section>
    </div>
  );
}

function SeriesCard({ series }: { series: Series }) {
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [title, setTitle] = useState(series.title);
  const detail = useQuery({
    queryKey: ["series", series.id], queryFn: () => api.getSeries(series.id),
  });
  const translated = detail.data?.chapters.reduce((sum, chapter) =>
    sum + chapter.translated_count, 0) ?? 0;
  const total = detail.data?.chapters.reduce((sum, chapter) =>
    sum + chapter.page_count, 0) ?? series.page_count;
  const translatedPercent = total ? (translated / total) * 100 : 0;
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["series"] });
    qc.invalidateQueries({ queryKey: ["series", series.id] });
  };

  const rename = useMutation({
    mutationFn: () => api.patchSeries(series.id, { title: title.trim() }),
    onSuccess: () => { setRenameOpen(false); invalidate(); },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });
  const del = useMutation({
    mutationFn: () => api.deleteSeries(series.id),
    onSuccess: () => {
      setDeleteOpen(false);
      qc.invalidateQueries({ queryKey: ["series"] });
      qc.invalidateQueries({ queryKey: ["continue"] });
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });
  const cover = useMutation({
    mutationFn: (file: File) => api.uploadCover(series.id, file),
    onSuccess: invalidate,
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  });

  return (
    <Card className="group overflow-hidden border-border/70 bg-card/70 py-0 shadow-sm transition duration-200 hover:-translate-y-1 hover:border-primary/40 hover:shadow-xl hover:shadow-primary/5">
      <div className="relative aspect-[2/3] overflow-hidden bg-muted">
        <Link href={`/series/${series.id}`} className="block size-full" aria-label={`Open ${series.title}`}>
          {series.cover_url ? (
            <img src={series.cover_url} alt={series.title}
                 className="size-full object-cover object-top transition duration-500 group-hover:scale-[1.035]" />
          ) : (
            <div className="flex size-full flex-col items-center justify-center gap-3 bg-gradient-to-br from-primary/15 via-muted to-muted p-4 text-center">
              <BookOpen className="size-8 text-primary/70" />
              <span className="line-clamp-3 font-medium">{series.title}</span>
            </div>
          )}
        </Link>
        <div className="absolute right-2 top-2">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="icon" variant="secondary" aria-label={`Actions for ${series.title}`}
                      className="size-8 bg-background/85 opacity-100 shadow-sm backdrop-blur transition sm:opacity-0 sm:group-hover:opacity-100 sm:focus-visible:opacity-100">
                <MoreHorizontal />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => { setTitle(series.title); setRenameOpen(true); }}>Rename</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => fileRef.current?.click()}>Change cover</DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem variant="destructive" onSelect={() => setDeleteOpen(true)}>Delete</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>
      <CardFooter className="block space-y-3 p-3.5">
        <div className="flex min-w-0 items-start justify-between gap-2">
          <div className="min-w-0">
            <Link href={`/series/${series.id}`} className="block truncate text-sm font-semibold hover:text-primary">
              {series.title}
            </Link>
            <p className="mt-1 text-xs text-muted-foreground">
              {series.chapter_count} chapters <span className="px-1">·</span> {series.page_count} pages
            </p>
          </div>
          {series.progress && (
            <Badge variant="secondary" className="shrink-0 text-[10px]">
              {Math.round(series.progress.percent)}%
            </Badge>
          )}
        </div>
        <div className="space-y-1.5">
          <div className="flex justify-between text-[10px] text-muted-foreground">
            <span>Reading progress</span>
            <span>{Math.round(series.progress?.percent ?? 0)}%</span>
          </div>
          <Progress value={series.progress?.percent ?? 0} className="h-1.5" />
          <div className="flex justify-between text-[10px] text-muted-foreground">
            <span>Translated</span><span>{Math.round(translatedPercent)}%</span>
          </div>
          <Progress value={translatedPercent} className="h-1" />
        </div>
        <input ref={fileRef} type="file" accept="image/*" className="hidden" aria-label="Change cover"
               onChange={(e) => {
                 const file = e.target.files?.[0];
                 if (file) cover.mutate(file);
                 e.target.value = "";
               }} />
      </CardFooter>

      <Dialog open={renameOpen} onOpenChange={setRenameOpen}>
        <DialogContent>
          <form onSubmit={(e) => { e.preventDefault(); rename.mutate(); }}>
            <DialogHeader>
              <DialogTitle>Rename series</DialogTitle>
              <DialogDescription>Choose the title shown on your shelf.</DialogDescription>
            </DialogHeader>
            <Input autoFocus aria-label="Rename series" value={title}
                   onChange={(e) => setTitle(e.target.value)} className="my-5" />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setRenameOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={!title.trim() || rename.isPending}>Save title</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {series.title}?</AlertDialogTitle>
            <AlertDialogDescription>This removes the series and all its chapters from your library.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" disabled={del.isPending}
                               onClick={(e) => { e.preventDefault(); del.mutate(); }}>Delete series</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

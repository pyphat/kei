"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useId, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Archive, Check, FileUp, FolderOpen, X } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/lib/api";
import {
  detectChapters, extOf, isImagePath, pooled, sha256hex,
} from "@/lib/upload";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

type Item = { path: string; file: File };

function UploadPage() {
  const params = useSearchParams();
  const router = useRouter();
  const { data: seriesList } = useQuery({ queryKey: ["series"], queryFn: api.listSeries });

  const [items, setItems] = useState<Item[]>([]);
  const [archives, setArchives] = useState<File[]>([]);
  const [chapters, setChapters] = useState<ReturnType<typeof detectChapters>>(null);
  const [rootName, setRootName] = useState("");
  const [target, setTarget] = useState<string>(params.get("series") ?? "new");
  const [newTitle, setNewTitle] = useState("");
  const [thumbs, setThumbs] = useState<Record<string, string>>({});
  const [phase, setPhase] = useState<"idle" | "preview" | "uploading" | "done">("idle");
  const [progress, setProgress] = useState({
    done: 0, total: 0, bytes: 0, bytesTotal: 0,
    chapterDone: [] as number[], chapterTotal: [] as number[],
  });
  const [resultSid, setResultSid] = useState<number | null>(null);
  const [err, setErr] = useState("");
  const dirRef = useRef<HTMLInputElement>(null);
  const zipRef = useRef<HTMLInputElement>(null);
  const [drag, setDrag] = useState(false);

  const fileByPath = useMemo(
    () => new Map(items.map((i) => [i.path, i.file])), [items]);

  useEffect(() => () => Object.values(thumbs).forEach(URL.revokeObjectURL), [thumbs]);

  function accept(list: Item[], zips: File[]) {
    setErr("");
    if (zips.length) setArchives((a) => [...a, ...zips]);
    if (!list.length) return;
    setItems(list);
    const det = detectChapters(list.map((i) => i.path));
    setChapters(det);
    if (det) {
      const root = list[0].path.split("/")[0];
      const isDir = list.every((i) => i.path.startsWith(root + "/"));
      setRootName(isDir && det.length > 1 ? root : "");
      setNewTitle((t) => t || (isDir ? root : ""));
      const t: Record<string, string> = {};
      for (const c of det) {
        const f = list.find((i) => i.path === c.files[0])?.file;
        if (f) t[c.dir] = URL.createObjectURL(f);
      }
      setThumbs(t);
      setPhase("preview");
    }
  }

  async function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setDrag(false);
    const items0 = [...e.dataTransfer.items];
    const out: Item[] = [];
    const zips: File[] = [];
    const walk = async (en: FileSystemEntry, prefix: string): Promise<void> => {
      if (en.isFile) {
        const f = await new Promise<File>((res, rej) =>
          (en as FileSystemFileEntry).file(res, rej));
        const p = prefix + f.name;
        if (/\.(cbz|zip)$/i.test(f.name)) zips.push(f);
        else if (isImagePath(p)) out.push({ path: p, file: f });
      } else if (en.isDirectory) {
        const rd = (en as FileSystemDirectoryEntry).createReader();
        for (;;) {
          const ents = await new Promise<FileSystemEntry[]>((res, rej) =>
            rd.readEntries(res, rej));
          if (!ents.length) break;
          for (const sub of ents) await walk(sub, prefix + en.name + "/");
        }
      }
    };
    await Promise.all(items0.map(async (it) => {
      const en = it.webkitGetAsEntry?.();
      if (en) await walk(en, "");
      else { const f = it.getAsFile(); if (f) out.push({ path: f.name, file: f }); }
    }));
    accept(out, zips);
  }

  function onFolderPick(e: React.ChangeEvent<HTMLInputElement>) {
    const files = [...(e.target.files ?? [])];
    accept(
      files.filter((f) => isImagePath(f.webkitRelativePath || f.name))
        .map((f) => ({ path: f.webkitRelativePath || f.name, file: f })),
      [],
    );
    e.target.value = "";
  }

  async function runUpload() {
    if (!chapters?.length) return;
    setPhase("uploading");
    setErr("");
    try {
      const sid = target === "new" ? undefined : Number(target);
      // 1. sha256 all files, limited concurrency
      const all = chapters.flatMap((c, ci) =>
        c.files.map((p, pi) => ({ ci, pi, p, f: fileByPath.get(p)! })));
      const files = await pooled(all, 4, async ({ ci, pi, p, f }) => ({
        ci, idx: pi, p,
        ext: extOf(p) === "jpeg" ? "jpg" : extOf(p),
        sha256: await sha256hex(await f.arrayBuffer()),
        size: f.size,
      }));
      const manifest = {
        ...(sid ? { series_id: sid } : { series_title: newTitle || rootName || "Untitled" }),
        chapters: chapters.map((c, ci) => ({
          number: c.number, title: c.title,
          files: files.filter((x) => x.ci === ci)
            .map(({ idx, ext, sha256, size }) => ({ idx, ext, sha256, size })),
        })),
      };
      const res = await api.uploads(manifest);
      // 2. PUT only missing pages, 4 in parallel, 3 retries
      const jobs = res.chapters.flatMap((ch, ci) =>
        ch.missing.map((idx) => ({ cid: ch.chapter_id, idx, ci })));
      const totalB = jobs.reduce((a, j) => a + files.find((x) => x.ci === j.ci && x.idx === j.idx)!.size, 0);
      const chapterTotal = chapters.map((_, ci) => jobs.filter((job) => job.ci === ci).length);
      setProgress({
        done: 0, total: jobs.length, bytes: 0, bytesTotal: totalB,
        chapterDone: chapters.map(() => 0),
        chapterTotal,
      });
      let done = 0, bytes = 0;
      const chapterDone = chapters.map(() => 0);
      await pooled(jobs, 4, async (j) => {
        const meta = files.find((x) => x.ci === j.ci && x.idx === j.idx)!;
        const f = fileByPath.get(meta.p)!;
        let last: unknown;
        for (let a = 0; a < 3; a++) {
          try {
            await api.putPage(j.cid, j.idx, f, meta.p);
            last = null;
            break;
          } catch (e) { last = e; await new Promise((r) => setTimeout(r, 500 * 2 ** a)); }
        }
        if (last) throw last;
        done++; bytes += meta.size; chapterDone[j.ci]++;
        setProgress({
          done, total: jobs.length, bytes, bytesTotal: totalB,
          chapterDone: [...chapterDone],
          chapterTotal,
        });
      });
      setResultSid(res.series_id);
      setPhase("done");
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
      toast.error(e instanceof Error ? e.message : String(e));
      setPhase("preview");
    }
  }

  const pct = progress.total ? Math.round((progress.done / progress.total) * 100) : 0;

  return (
    <div className="mx-auto max-w-5xl space-y-8 px-4 py-8 sm:px-6"
         onDragOver={(e) => { e.preventDefault(); setDrag(true); }}
         onDragLeave={() => setDrag(false)} onDrop={onDrop}>
      <div>
        <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Build your shelf</p>
        <h1 className="mt-1 text-3xl font-semibold tracking-tight">Add manga</h1>
        <p className="mt-2 text-sm text-muted-foreground">Import a folder of chapters or a CBZ/ZIP archive.</p>
      </div>

      <Card className={`border-2 border-dashed transition ${drag ? "border-primary bg-primary/5" : "border-border bg-card/60"}`}>
        <CardContent className="flex min-h-64 flex-col items-center justify-center px-5 py-10 text-center">
          <div className="mb-4 flex size-14 items-center justify-center rounded-2xl bg-primary/10 text-primary">
            <FileUp className="size-7" />
          </div>
          <h2 className="text-lg font-semibold">{drag ? "Drop to add your files" : "Drop a folder or archive here"}</h2>
          <p className="mt-1 max-w-sm text-sm text-muted-foreground">
            We’ll detect chapters and let you review everything before it uploads.
          </p>
          <div className="mt-5 flex flex-wrap justify-center gap-2">
            <Button variant="outline" onClick={() => dirRef.current?.click()}>
              <FolderOpen />Choose folder
            </Button>
            <Button variant="outline" onClick={() => zipRef.current?.click()}>
              <Archive />Choose .cbz/.zip
            </Button>
          </div>
        </CardContent>
      </Card>
      <input ref={dirRef} type="file" className="hidden" aria-label="Choose folder"
             {...({ webkitdirectory: "" } as object)} multiple onChange={onFolderPick} />
      <input ref={zipRef} type="file" accept=".cbz,.zip" className="hidden" multiple
             aria-label="Choose archive"
             onChange={(e) => { accept([], [...(e.target.files ?? [])]); e.target.value = ""; }} />

      {!!archives.length && (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">Archives</h2>
          {archives.map((f, i) => (
            <ArchiveRow key={i} file={f} seriesList={seriesList ?? []} defaultSid={target}
                        onDone={() => router.refresh()} />
          ))}
        </section>
      )}

      {chapters && phase !== "idle" && (
        <section className="space-y-5">
          <div className="flex flex-col gap-3 rounded-xl border bg-card/60 p-4 sm:flex-row sm:items-end">
            <div className="grid gap-2 sm:w-56">
              <Label htmlFor="target-series">Add to</Label>
              <Select value={target} onValueChange={setTarget}>
                <SelectTrigger id="target-series" aria-label="Target series" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="new">New series</SelectItem>
                  {(seriesList ?? []).map((s) => <SelectItem key={s.id} value={String(s.id)}>{s.title}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            {target === "new" && (
              <div className="grid flex-1 gap-2">
                <Label htmlFor="new-series-title">Series title</Label>
                <Input id="new-series-title" value={newTitle} onChange={(e) => setNewTitle(e.target.value)}
                       placeholder="Series title" aria-label="New series title" />
              </div>
            )}
          </div>

          <div className="space-y-3">
            {chapters.map((c, i) => (
              <Card key={i} className="overflow-hidden bg-card/70">
                <CardContent className="grid gap-4 p-3 sm:grid-cols-[64px_110px_1fr_auto] sm:items-center sm:p-4">
                  <div className="aspect-[2/3] w-16 overflow-hidden rounded-lg bg-muted">
                    {thumbs[c.dir] && <img src={thumbs[c.dir]} alt="" className="size-full object-cover" />}
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor={`chapter-number-${i}`}>Number</Label>
                    <Input id={`chapter-number-${i}`} type="number" step="0.5" value={c.number}
                           aria-label="Chapter number" onChange={(e) => setChapters(chapters.map((x, j) =>
                             j === i ? { ...x, number: Number(e.target.value) } : x))} />
                  </div>
                  <div className="grid min-w-0 gap-2">
                    <Label htmlFor={`chapter-title-${i}`}>Chapter title</Label>
                    <Input id={`chapter-title-${i}`} value={c.title} aria-label="Chapter title"
                           onChange={(e) => setChapters(chapters.map((x, j) =>
                             j === i ? { ...x, title: e.target.value } : x))} />
                    <div className="flex items-center gap-2 text-xs text-muted-foreground">
                      <Badge variant="secondary">{c.files.length} pages</Badge>
                      <span className="truncate">{c.dir || rootName}</span>
                    </div>
                  </div>
                  <Button variant="ghost" size="icon" aria-label="Remove chapter"
                          onClick={() => setChapters(chapters.filter((_, j) => j !== i))}>
                    <X />
                  </Button>
                  {phase === "uploading" && (
                    <div className="col-span-full grid grid-cols-[1fr_auto] items-center gap-3">
                      <Progress value={progress.chapterTotal[i]
                        ? ((progress.chapterDone[i] ?? 0) / progress.chapterTotal[i]) * 100 : 100} />
                      <span className="text-xs text-muted-foreground">
                        {progress.chapterDone[i] ?? 0}/{progress.chapterTotal[i] ?? c.files.length}
                      </span>
                    </div>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>

          {phase === "uploading" && (
            <Card>
              <CardContent className="space-y-3 p-4">
                <div className="flex justify-between text-sm">
                  <span className="font-medium">Upload progress</span>
                  <span className="text-muted-foreground">{pct}%</span>
                </div>
                <Progress value={pct} />
                <p className="text-xs text-muted-foreground">
                  {progress.done}/{progress.total} files · {(progress.bytes / 1e6).toFixed(1)}/{(progress.bytesTotal / 1e6).toFixed(1)} MB
                </p>
              </CardContent>
            </Card>
          )}

          <div className="flex flex-wrap items-center gap-2">
            {phase === "done" && resultSid ? (
              <Button asChild><Link href={`/series/${resultSid}`}><Check />Open series</Link></Button>
            ) : (
              <>
                <Button disabled={phase === "uploading" || !chapters.length} onClick={runUpload}>
                  <FileUp />{phase === "uploading" ? "Uploading…" : "Upload"}
                </Button>
                <Button variant="outline" disabled={phase === "uploading"} onClick={runUpload}>Resume</Button>
                <Button variant="ghost" disabled={phase === "uploading"}
                        onClick={() => { setChapters(null); setItems([]); setPhase("idle"); }}>Cancel</Button>
              </>
            )}
          </div>
          {err && <div role="alert" className="rounded-lg border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">{err}</div>}
        </section>
      )}
    </div>
  );
}

function ArchiveRow({ file, seriesList, defaultSid, onDone }: {
  file: File; seriesList: { id: number; title: string }[];
  defaultSid: string; onDone: () => void;
}) {
  const id = useId();
  const [sid, setSid] = useState(defaultSid);
  const [title, setTitle] = useState(file.name.replace(/\.(cbz|zip)$/i, ""));
  const [num, setNum] = useState(1);
  const [chTitle, setChTitle] = useState("");
  const [pct, setPct] = useState<number | null>(null);
  const [err, setErr] = useState("");

  function send() {
    const fd = new FormData();
    fd.append("file", file);
    fd.append("chapter_number", String(num));
    fd.append("chapter_title", chTitle);
    if (sid === "new") fd.append("series_title", title);
    else fd.append("series_id", sid);
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/uploads/archive");
    xhr.upload.onprogress = (e) => e.lengthComputable && setPct(Math.round((e.loaded / e.total) * 100));
    xhr.onload = () => {
      if (xhr.status < 300) {
        onDone();
        toast.success(`${file.name} uploaded`);
      } else {
        try { setErr(JSON.parse(xhr.responseText).detail); } catch { setErr(`HTTP ${xhr.status}`); }
        toast.error(`Upload failed: ${file.name}`);
      }
      setPct(null);
    };
    xhr.onerror = () => { setErr("upload failed"); setPct(null); toast.error("Archive upload failed"); };
    xhr.send(fd);
    setPct(0);
  }

  return (
    <Card className="bg-card/70">
      <CardContent className="grid gap-4 p-4 md:grid-cols-[minmax(150px,1fr)_180px_130px_1fr_auto] md:items-end">
        <div className="min-w-0">
          <Badge variant="outline" className="mb-2"><Archive />Archive</Badge>
          <p className="truncate text-sm font-semibold" title={file.name}>{file.name}</p>
        </div>
        <div className="grid gap-2">
          <Label htmlFor={`${id}-target`}>Add to</Label>
          <Select value={sid} onValueChange={setSid}>
            <SelectTrigger id={`${id}-target`} aria-label="Target series" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="new">New series</SelectItem>
              {seriesList.map((s) => <SelectItem key={s.id} value={String(s.id)}>{s.title}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        {sid === "new" && (
          <div className="grid gap-2">
            <Label htmlFor={`${id}-series`}>Series title</Label>
            <Input id={`${id}-series`} value={title} aria-label="Series title"
                   onChange={(e) => setTitle(e.target.value)} />
          </div>
        )}
        <div className="grid gap-2">
          <Label htmlFor={`${id}-chapter`}>Chapter</Label>
          <div className="flex gap-2">
            <Input id={`${id}-chapter`} type="number" step="0.5" value={num}
                   aria-label="Chapter number" className="w-20"
                   onChange={(e) => setNum(Number(e.target.value))} />
            <Input value={chTitle} onChange={(e) => setChTitle(e.target.value)}
                   placeholder="Chapter title" aria-label="Chapter title" />
          </div>
        </div>
        {pct !== null ? (
          <div className="grid min-w-24 gap-2">
            <span className="text-xs text-muted-foreground">Uploading {pct}%</span>
            <Progress value={pct} />
          </div>
        ) : <Button onClick={send}><FileUp />Upload</Button>}
        {err && <p role="alert" className="col-span-full text-xs text-destructive">{err}</p>}
      </CardContent>
    </Card>
  );
}

export default function Page() {
  return <Suspense><UploadPage /></Suspense>;
}

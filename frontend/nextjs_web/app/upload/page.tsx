"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import {
  detectChapters, extOf, isImagePath, pooled, sha256hex,
} from "@/lib/upload";

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
  const [progress, setProgress] = useState({ done: 0, total: 0, bytes: 0, bytesTotal: 0 });
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
      setProgress({ done: 0, total: jobs.length, bytes: 0, bytesTotal: totalB });
      let done = 0, bytes = 0;
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
        done++; bytes += meta.size;
        setProgress({ done, total: jobs.length, bytes, bytesTotal: totalB });
      });
      setResultSid(res.series_id);
      setPhase("done");
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
      setPhase("preview");
    }
  }

  const pct = progress.total ? Math.round((progress.done / progress.total) * 100) : 0;

  return (
    <div className="space-y-5 pt-4" onDragOver={(e) => { e.preventDefault(); setDrag(true); }}
         onDragLeave={() => setDrag(false)} onDrop={onDrop}>
      <h1 className="text-xl font-bold">Upload</h1>

      <div className={`rounded border-2 border-dashed p-8 text-center text-neutral-400 ${drag ? "border-indigo-500 bg-indigo-950/30" : "border-neutral-700"}`}>
        Drop a folder, images, or .cbz/.zip here
        <div className="mt-3 flex flex-wrap justify-center gap-2">
          <button className="btn" onClick={() => dirRef.current?.click()}>Choose folder</button>
          <button className="btn" onClick={() => zipRef.current?.click()}>Choose .cbz/.zip</button>
        </div>
      </div>
      <input ref={dirRef} type="file" className="hidden" aria-label="Choose folder"
             {...({ webkitdirectory: "" } as object)} multiple onChange={onFolderPick} />
      <input ref={zipRef} type="file" accept=".cbz,.zip" className="hidden" multiple
             aria-label="Choose archive"
             onChange={(e) => { accept([], [...(e.target.files ?? [])]); e.target.value = ""; }} />

      {archives.map((f, i) => (
        <ArchiveRow key={i} file={f} seriesList={seriesList ?? []} defaultSid={target}
                    onDone={() => router.refresh()} />
      ))}

      {chapters && phase !== "idle" && (
        <section className="space-y-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="text-neutral-400">Target series:</span>
            <select value={target} onChange={(e) => setTarget(e.target.value)} aria-label="Target series">
              <option value="new">New series</option>
              {(seriesList ?? []).map((s) => <option key={s.id} value={s.id}>{s.title}</option>)}
            </select>
            {target === "new" && (
              <input value={newTitle} onChange={(e) => setNewTitle(e.target.value)}
                     placeholder="Series title" aria-label="New series title" />
            )}
          </div>

          {chapters.map((c, i) => (
            <div key={i} className="flex items-center gap-3 rounded bg-neutral-900 p-2">
              {thumbs[c.dir] &&
                <img src={thumbs[c.dir]} alt="" className="h-14 w-10 rounded object-cover" />}
              <label className="text-xs text-neutral-400">#
                <input type="number" step="0.5" value={c.number} aria-label="Chapter number"
                       className="ml-1 w-16"
                       onChange={(e) => setChapters(chapters.map((x, j) =>
                         j === i ? { ...x, number: Number(e.target.value) } : x))} />
              </label>
              <input value={c.title} aria-label="Chapter title" className="flex-1"
                     onChange={(e) => setChapters(chapters.map((x, j) =>
                       j === i ? { ...x, title: e.target.value } : x))} />
              <span className="text-xs text-neutral-400">{c.files.length} pages</span>
              <button className="btn !px-2 text-xs text-red-300" aria-label="Remove chapter"
                      onClick={() => setChapters(chapters.filter((_, j) => j !== i))}>✕</button>
            </div>
          ))}

          {phase === "uploading" && (
            <div>
              <div className="h-2 rounded bg-neutral-800">
                <div className="h-2 rounded bg-indigo-500 transition-all" style={{ width: `${pct}%` }} />
              </div>
              <div className="mt-1 text-xs text-neutral-400">
                {progress.done}/{progress.total} files · {(progress.bytes / 1e6).toFixed(1)}/{(progress.bytesTotal / 1e6).toFixed(1)} MB
              </div>
            </div>
          )}

          <div className="flex gap-2">
            {phase === "done" && resultSid ? (
              <Link className="btn-primary" href={`/series/${resultSid}`}>Open series →</Link>
            ) : (
              <>
                <button className="btn-primary" disabled={phase === "uploading" || !chapters.length}
                        onClick={runUpload}>
                  {phase === "uploading" ? "Uploading…" : "Upload"}
                </button>
                <button className="btn" disabled={phase === "uploading"} onClick={runUpload}>Resume</button>
                <button className="btn" disabled={phase === "uploading"}
                        onClick={() => { setChapters(null); setItems([]); setPhase("idle"); }}>
                  Cancel
                </button>
              </>
            )}
          </div>
          {err && <div className="text-sm text-red-400">{err}</div>}
        </section>
      )}
    </div>
  );
}

function ArchiveRow({ file, seriesList, defaultSid, onDone }: {
  file: File; seriesList: { id: number; title: string }[];
  defaultSid: string; onDone: () => void;
}) {
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
      if (xhr.status < 300) onDone();
      else { try { setErr(JSON.parse(xhr.responseText).detail); } catch { setErr(`HTTP ${xhr.status}`); } }
      setPct(null);
    };
    xhr.onerror = () => { setErr("upload failed"); setPct(null); };
    xhr.send(fd);
    setPct(0);
  }

  return (
    <div className="flex flex-wrap items-center gap-2 rounded bg-neutral-900 p-2 text-sm">
      <span className="truncate font-medium">{file.name}</span>
      <select value={sid} onChange={(e) => setSid(e.target.value)} aria-label="Target series">
        <option value="new">New series</option>
        {seriesList.map((s) => <option key={s.id} value={s.id}>{s.title}</option>)}
      </select>
      {sid === "new" &&
        <input value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Series title" />}
      <label className="text-xs text-neutral-400">ch #
        <input type="number" step="0.5" value={num} className="ml-1 w-16" aria-label="Chapter number"
               onChange={(e) => setNum(Number(e.target.value))} /></label>
      <input value={chTitle} onChange={(e) => setChTitle(e.target.value)}
             placeholder="Chapter title" aria-label="Chapter title" />
      {pct !== null ? <span className="text-xs text-neutral-400">{pct}%</span>
        : <button className="btn-primary" onClick={send}>Upload</button>}
      {err && <span className="text-xs text-red-400">{err}</span>}
    </div>
  );
}

export default function Page() {
  return <Suspense><UploadPage /></Suspense>;
}

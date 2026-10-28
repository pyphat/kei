"use client";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Suspense, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type ChapterSummary } from "@/lib/api";

export default function Page() {
  return <Suspense><SeriesPage /></Suspense>;
}

function SeriesPage() {
  const { id } = useParams<{ id: string }>();
  const sid = Number(id);
  const qc = useQueryClient();
  const router = useRouter();
  const { data: s } = useQuery({ queryKey: ["series", sid], queryFn: () => api.getSeries(sid) });
  const inv = () => qc.invalidateQueries({ queryKey: ["series"] });
  const fileRef = useRef<HTMLInputElement>(null);

  const patch = useMutation({
    mutationFn: (b: Parameters<typeof api.patchSeries>[1]) => api.patchSeries(sid, b),
    onSuccess: inv,
  });
  const cover = useMutation({ mutationFn: (f: File) => api.uploadCover(sid, f), onSuccess: inv });
  const translateAll = useMutation({
    mutationFn: async () => {
      for (const c of s?.chapters ?? []) await api.translate(c.id, "start");
    },
    onSuccess: inv,
  });
  const del = useMutation({
    mutationFn: () => api.deleteSeries(sid),
    onSuccess: () => router.push("/"),
  });

  if (!s) return <div className="p-8 text-neutral-400">Loading…</div>;

  return (
    <div className="space-y-5 pt-4">
      <div className="flex flex-wrap gap-4">
        <button aria-label="Change cover" title="Change cover"
                onClick={() => fileRef.current?.click()}
                className="h-56 w-40 shrink-0 overflow-hidden rounded bg-neutral-800">
          {s.cover_url
            ? <img src={s.cover_url} alt={s.title} className="h-full w-full object-cover" />
            : <span className="text-xs text-neutral-500">no cover — click to add</span>}
        </button>
        <input ref={fileRef} type="file" accept="image/*" className="hidden"
               aria-label="Upload cover"
               onChange={(e) => e.target.files?.[0] && cover.mutate(e.target.files[0])} />
        <div className="min-w-0 flex-1 space-y-2">
          <Editable label="Title" value={s.title} big
                    onSave={(title) => patch.mutate({ title })} />
          <Editable label="Author" value={s.author}
                    onSave={(author) => patch.mutate({ author })} />
          <Editable label="Tags (comma separated)" value={s.tags.join(", ")}
                    onSave={(v) => patch.mutate({ tags: v.split(",").map((t) => t.trim()).filter(Boolean) })} />
          <div className="flex flex-wrap gap-2 pt-1">
            <button className="btn-primary" disabled={translateAll.isPending}
                    onClick={() => translateAll.mutate()}>Translate all</button>
            <Link className="btn" href={`/upload?series=${sid}`}>Add chapters</Link>
            <button className="btn text-red-300"
                    onClick={() => confirm(`Delete "${s.title}"?`) && del.mutate()}>
              Delete series
            </button>
          </div>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="text-left text-xs uppercase text-neutral-500">
            <tr>
              <th className="py-1 pr-3">#</th><th className="pr-3">Title</th>
              <th className="w-64 pr-3">Progress</th>
              <th className="pr-3">State</th><th />
            </tr>
          </thead>
          <tbody>
            {s.chapters.map((c) => (
              <ChapterRow key={c.id} c={c}
                          progress={s.progress?.chapter_id === c.id ? s.progress : null}
                          invalidate={inv} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Editable({ label, value, onSave, big }: {
  label: string; value: string; onSave: (v: string) => void; big?: boolean;
}) {
  const [v, setV] = useState(value);
  return (
    <label className="block">
      <span className="sr-only">{label}</span>
      <input value={v} onChange={(e) => setV(e.target.value)}
             onBlur={() => v !== value && onSave(v)}
             onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
             className={`w-full border-transparent bg-transparent ${big ? "text-xl font-bold" : "text-neutral-300"}`} />
    </label>
  );
}

function Bar({ label, n, total, cls }: { label: string; n: number; total: number; cls: string }) {
  return (
    <div className="flex items-center gap-1 text-[10px] text-neutral-400">
      <span className="w-14">{label}</span>
      <div className="h-1.5 flex-1 rounded bg-neutral-800">
        <div className={`h-1.5 rounded ${cls}`}
             style={{ width: `${total ? (n / total) * 100 : 0}%` }} />
      </div>
      <span className="w-10 text-right">{n}/{total}</span>
    </div>
  );
}

function ChapterRow({ c, progress, invalidate }: {
  c: ChapterSummary;
  progress: { page: number; percent: number } | null;
  invalidate: () => void;
}) {
  const del = useMutation({ mutationFn: () => api.deleteChapter(c.id), onSuccess: invalidate });
  const tr = useMutation({
    mutationFn: (a: "start" | "pause") => api.translate(c.id, a),
    onSuccess: invalidate,
  });
  const readHref = `/read/${c.id}${progress ? `?page=${progress.page}` : ""}`;
  return (
    <tr className="border-t border-neutral-800">
      <td className="py-2 pr-3 align-top text-neutral-400">{c.number}</td>
      <td className="pr-3 align-top">
        <Editable label="Chapter title" value={c.title || `Chapter ${c.number}`}
                  onSave={(title) => api.patchChapter(c.id, { title }).then(invalidate)} />
      </td>
      <td className="space-y-0.5 pr-3 align-top">
        <Bar label="uploaded" n={c.present_count} total={c.page_count} cls="bg-neutral-500" />
        <Bar label="processed" n={c.processed_count} total={c.page_count} cls="bg-sky-500" />
        <Bar label="translated" n={c.translated_count} total={c.page_count} cls="bg-indigo-500" />
      </td>
      <td className="pr-3 align-top">
        <span className={`rounded-full px-2 py-0.5 text-[10px] ${
          c.state === "running" ? "bg-emerald-900 text-emerald-200"
          : c.state === "paused" ? "bg-amber-900 text-amber-200"
          : "bg-neutral-800 text-neutral-400"}`}>{c.state}</span>
      </td>
      <td className="align-top">
        <div className="flex flex-wrap gap-1">
          <Link className="btn !px-2 !py-1 text-xs" href={readHref}>Read</Link>
          <button className="btn !px-2 !py-1 text-xs"
                  onClick={() => confirm("Re-cleans with current settings and re-translates (uses OpenRouter requests); edited translations are lost.")
                    && api.reprocessChapter(c.id).then(invalidate)}>
            Reprocess
          </button>
          {c.state === "running"
            ? <button className="btn !px-2 !py-1 text-xs" onClick={() => tr.mutate("pause")}>Pause</button>
            : <button className="btn !px-2 !py-1 text-xs" onClick={() => tr.mutate("start")}>Translate</button>}
          <button className="btn !px-2 !py-1 text-xs text-red-300"
                  onClick={() => confirm("Delete this chapter?") && del.mutate()}>
            Delete
          </button>
        </div>
      </td>
    </tr>
  );
}

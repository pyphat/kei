"use client";
import Link from "next/link";
import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type Series } from "@/lib/api";

export default function LibraryPage() {
  const qc = useQueryClient();
  const { data: series } = useQuery({ queryKey: ["series"], queryFn: api.listSeries });
  const { data: cont } = useQuery({ queryKey: ["continue"], queryFn: api.continueReading });
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<"recent" | "title">("recent");

  const shown = useMemo(() => {
    let s = (series ?? []).filter((x) =>
      x.title.toLowerCase().includes(q.toLowerCase()));
    if (sort === "title") s = [...s].sort((a, b) => a.title.localeCompare(b.title));
    return s;
  }, [series, q, sort]);

  return (
    <div className="space-y-6 pt-4">
      {!!cont?.length && (
        <section>
          <h2 className="mb-2 text-sm font-semibold uppercase text-neutral-400">
            Continue reading
          </h2>
          <div className="flex gap-3 overflow-x-auto pb-1">
            {cont.map((r) => (
              <Link key={r.series_id}
                    href={`/read/${r.chapter_id}?page=${r.page}`}
                    className="flex w-40 shrink-0 items-center gap-2 rounded bg-neutral-900 p-2 hover:bg-neutral-800">
                {r.cover_url
                  ? <img src={r.cover_url} alt="" className="h-16 w-12 rounded object-cover" />
                  : <div className="h-16 w-12 rounded bg-neutral-800" />}
                <div className="min-w-0 text-xs">
                  <div className="truncate font-medium">{r.series_title}</div>
                  <div className="text-neutral-400">ch {r.chapter_number} · p{r.page + 1}</div>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <input aria-label="Search" placeholder="Search…" value={q}
               onChange={(e) => setQ(e.target.value)} className="w-48" />
        <select aria-label="Sort" value={sort}
                onChange={(e) => setSort(e.target.value as "recent" | "title")}>
          <option value="recent">Recent</option>
          <option value="title">Title</option>
        </select>
      </div>

      {series && !shown.length && (
        <div className="rounded border border-dashed border-neutral-700 p-10 text-center text-neutral-400">
          {series.length ? "No matches." : (
            <>Library is empty. <Link className="text-indigo-400 underline" href="/upload">Upload manga</Link></>
          )}
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
        {shown.map((s) => <Card key={s.id} s={s} invalidate={() => qc.invalidateQueries({ queryKey: ["series"] })} />)}
      </div>
    </div>
  );
}

function Card({ s, invalidate }: { s: Series; invalidate: () => void }) {
  const [renaming, setRenaming] = useState(false);
  const [title, setTitle] = useState(s.title);
  const fileRef = useRef<HTMLInputElement>(null);
  const rename = useMutation({
    mutationFn: () => api.patchSeries(s.id, { title }),
    onSuccess: () => { setRenaming(false); invalidate(); },
  });
  const del = useMutation({
    mutationFn: () => api.deleteSeries(s.id),
    onSuccess: invalidate,
  });
  const cover = useMutation({
    mutationFn: (f: File) => api.uploadCover(s.id, f),
    onSuccess: invalidate,
  });

  return (
    <div className="group overflow-hidden rounded bg-neutral-900">
      <Link href={`/series/${s.id}`} className="block">
        <div className="aspect-[2/3] w-full bg-neutral-800">
          {s.cover_url
            ? <img src={s.cover_url} alt={s.title} className="h-full w-full object-cover" />
            : <div className="flex h-full items-center justify-center p-3 text-center text-sm text-neutral-400">{s.title}</div>}
        </div>
      </Link>
      <div className="space-y-1 p-2 text-xs">
        {renaming ? (
          <input autoFocus value={title} aria-label="Rename series"
                 onChange={(e) => setTitle(e.target.value)}
                 onBlur={() => rename.mutate()}
                 onKeyDown={(e) => e.key === "Enter" && rename.mutate()}
                 className="w-full" />
        ) : (
          <Link href={`/series/${s.id}`} className="block truncate font-medium">{s.title}</Link>
        )}
        <div className="text-neutral-400">{s.chapter_count} ch · {s.page_count} p</div>
        {s.progress && (
          <div className="h-1 rounded bg-neutral-800">
            <div className="h-1 rounded bg-indigo-500" style={{ width: `${s.progress.percent}%` }} />
          </div>
        )}
        <div className="flex gap-1 pt-1 opacity-0 transition group-hover:opacity-100 focus-within:opacity-100">
          <button className="btn !px-2 !py-0.5 text-xs" onClick={() => { setTitle(s.title); setRenaming(true); }}>Rename</button>
          <button className="btn !px-2 !py-0.5 text-xs" onClick={() => fileRef.current?.click()}>Cover</button>
          <button className="btn !px-2 !py-0.5 text-xs text-red-300"
                  onClick={() => confirm(`Delete "${s.title}" and all its chapters?`) && del.mutate()}>
            Delete
          </button>
        </div>
        <input ref={fileRef} type="file" accept="image/*" className="hidden" aria-label="Change cover"
               onChange={(e) => e.target.files?.[0] && cover.mutate(e.target.files[0])} />
      </div>
    </div>
  );
}

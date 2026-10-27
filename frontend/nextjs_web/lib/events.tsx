"use client";
/** One EventSource for the whole app + toast area + per-page image versions. */
import {
  createContext, useCallback, useContext, useEffect, useRef, useState,
  type ReactNode,
} from "react";
import { useQueryClient } from "@tanstack/react-query";

interface Ctx {
  /** bumped by SSE page events + edits; key "cid:idx" */
  pageVersion: (cid: number, idx: number) => number;
  bumpPage: (cid: number, idx: number) => void;
  toast: (msg: string) => void;
}

const EventsCtx = createContext<Ctx>({
  pageVersion: () => 0, bumpPage: () => {}, toast: () => {},
});

export const useEvents = () => useContext(EventsCtx);

export function EventsProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [toasts, setToasts] = useState<{ id: number; msg: string }[]>([]);
  const versions = useRef(new Map<string, number>());

  const bumpPage = useCallback((cid: number, idx: number) => {
    const k = `${cid}:${idx}`;
    versions.current.set(k, (versions.current.get(k) ?? 0) + 1);
  }, []);

  const toast = useCallback((msg: string) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, msg }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 6000);
  }, []);

  useEffect(() => {
    const es = new EventSource("/api/events");
    es.onmessage = (e) => {
      let ev: Record<string, unknown>;
      try { ev = JSON.parse(e.data); } catch { return; }
      switch (ev.type) {
        case "library":
          qc.invalidateQueries({ queryKey: ["series"] });
          qc.invalidateQueries({ queryKey: ["continue"] });
          break;
        case "page": {
          const cid = ev.chapter_id as number, p = ev.page as number;
          bumpPage(cid, p);
          qc.invalidateQueries({ queryKey: ["chapter", cid] });
          qc.invalidateQueries({ queryKey: ["blocks", cid, p] });
          break;
        }
        case "chapter":
          qc.invalidateQueries({ queryKey: ["chapter", ev.chapter_id] });
          qc.invalidateQueries({ queryKey: ["series"] });
          break;
        case "progress":
          qc.invalidateQueries({ queryKey: ["continue"] });
          qc.invalidateQueries({ queryKey: ["progress", ev.series_id] });
          break;
        case "usage":
          qc.invalidateQueries({ queryKey: ["status"] });
          break;
        case "error":
          toast(String(ev.message ?? "error"));
          break;
      }
    };
    return () => es.close();
  }, [qc, bumpPage, toast]);

  const pageVersion = useCallback(
    (cid: number, idx: number) => versions.current.get(`${cid}:${idx}`) ?? 0, []);

  return (
    <EventsCtx.Provider value={{ pageVersion, bumpPage, toast }}>
      {children}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
        {toasts.map((t) => (
          <div key={t.id}
               className="rounded bg-red-900/90 px-4 py-2 text-sm text-red-100 shadow-lg">
            {t.msg}
          </div>
        ))}
      </div>
    </EventsCtx.Provider>
  );
}

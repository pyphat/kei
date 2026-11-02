"use client";
/** One EventSource for the whole app. */
import { useEffect, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Toaster } from "@/components/ui/sonner";

export function EventsProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();

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
          toast.error(String(ev.message ?? "error"));
          break;
      }
    };
    return () => es.close();
  }, [qc]);

  return (
    <>
      {children}
      <Toaster richColors position="bottom-right" closeButton />
    </>
  );
}

"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

export function Nav() {
  const { data } = useQuery({ queryKey: ["status"], queryFn: api.status });
  return (
    <header className="sticky top-0 z-40 border-b border-neutral-800 bg-neutral-950/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2">
        <Link href="/" className="text-lg font-bold tracking-wide">kei</Link>
        <nav className="flex gap-3 text-sm text-neutral-300">
          <Link className="hover:text-white" href="/">Library</Link>
          <Link className="hover:text-white" href="/upload">Upload</Link>
          <Link className="hover:text-white" href="/settings">Settings</Link>
        </nav>
        <div className="ml-auto flex items-center gap-2 text-xs">
          {data && (
            <span className="rounded-full bg-neutral-800 px-3 py-1 text-neutral-300">
              {data.device} · {data.requests_today} requests today · {data.inpainter}
            </span>
          )}
          {data && !data.has_key && (
            <Link href="/settings"
                  className="rounded-full bg-red-800 px-3 py-1 font-medium text-red-100">
              no API key
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}

"use client";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { EventsProvider } from "@/lib/events";

let browser: QueryClient | undefined;
function getClient() {
  if (typeof window === "undefined") return new QueryClient();
  browser ??= new QueryClient({
    defaultOptions: { queries: { staleTime: 10_000, retry: 1 } },
  });
  return browser;
}

export function Providers({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={getClient()}>
      <EventsProvider>{children}</EventsProvider>
    </QueryClientProvider>
  );
}

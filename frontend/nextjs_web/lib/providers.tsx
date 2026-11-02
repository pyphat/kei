"use client";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { TooltipProvider } from "@/components/ui/tooltip";
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
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem
                   disableTransitionOnChange>
      <QueryClientProvider client={getClient()}>
        <TooltipProvider>
          <EventsProvider>{children}</EventsProvider>
        </TooltipProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

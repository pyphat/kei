"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTheme } from "next-themes";
import { useQuery } from "@tanstack/react-query";
import { BookOpen, KeyRound, Moon, Settings, Sun, SunMoon, Upload, Monitor } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

const links = [
  { href: "/", label: "Library", icon: BookOpen },
  { href: "/upload", label: "Upload", icon: Upload },
  { href: "/settings", label: "Settings", icon: Settings },
];

export function Nav() {
  const pathname = usePathname();
  const { data } = useQuery({ queryKey: ["status"], queryFn: api.status });
  const { setTheme } = useTheme();

  if (pathname.startsWith("/read/")) return null;

  return (
    <header className="sticky top-0 z-40 border-b border-border/80 bg-background/85 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-7xl items-center gap-3 px-3 sm:px-6">
        <Link href="/" aria-label="kei library" className="mr-2 flex items-center gap-2 font-semibold tracking-tight">
          <span className="flex size-8 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <BookOpen className="size-4" aria-hidden="true" />
          </span>
          <span className="text-lg">kei</span>
        </Link>

        <nav aria-label="Main navigation" className="flex items-center gap-1">
          {links.map(({ href, label, icon: Icon }) => {
            const active = href === "/" ? pathname === "/" : pathname.startsWith(href);
            return (
              <Tooltip key={href}>
                <TooltipTrigger asChild>
                  <Button asChild size="sm" variant={active ? "secondary" : "ghost"}
                          className={active ? "bg-accent text-accent-foreground" : "text-muted-foreground"}>
                    <Link href={href} aria-label={label} aria-current={active ? "page" : undefined}>
                      <Icon aria-hidden="true" />
                      <span className="hidden sm:inline">{label}</span>
                    </Link>
                  </Button>
                </TooltipTrigger>
                <TooltipContent className="sm:hidden">{label}</TooltipContent>
              </Tooltip>
            );
          })}
        </nav>

        <div className="ml-auto flex items-center gap-2">
          {data && (
            <Badge variant="outline" className="hidden gap-1.5 px-2.5 py-1 font-normal sm:inline-flex">
              <span className={`size-1.5 rounded-full ${data.device === "cuda" ? "bg-emerald-500" : "bg-sky-500"}`} />
              {data.device} <span className="text-muted-foreground">· {data.requests_today} requests today</span>
            </Badge>
          )}
          {data && !data.has_key && (
            <>
              <Button asChild variant="destructive" size="icon" aria-label="No API key" className="sm:hidden">
                <Link href="/settings"><KeyRound /></Link>
              </Button>
              <Button asChild variant="destructive" size="sm" className="hidden sm:inline-flex">
                <Link href="/settings"><KeyRound />No API key</Link>
              </Button>
            </>
          )}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="icon" variant="ghost" aria-label="Theme">
                <SunMoon aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="min-w-36">
              <DropdownMenuItem onSelect={() => setTheme("light")}><Sun />Light</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => setTheme("dark")}><Moon />Dark</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => setTheme("system")}><Monitor />System</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>
    </header>
  );
}

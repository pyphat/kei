import type { Metadata } from "next";
import "./globals.css";
import { Providers } from "@/lib/providers";
import { Nav } from "@/lib/nav";

export const metadata: Metadata = { title: "kei" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-neutral-950 text-neutral-100 antialiased">
        <Providers>
          <Nav />
          <main className="mx-auto max-w-6xl px-3 pb-16">{children}</main>
        </Providers>
      </body>
    </html>
  );
}

"use client";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";

export default function SettingsPage() {
  const qc = useQueryClient();
  const { data: s } = useQuery({ queryKey: ["settings"], queryFn: api.getSettings });
  const [form, setForm] = useState<Record<string, string>>({});
  const [key, setKey] = useState("");
  const [msg, setMsg] = useState("");
  const [models, setModels] = useState<{ id: string; name: string }[] | null>(null);

  const save = useMutation({
    mutationFn: () => {
      const b: Record<string, unknown> = {};
      if (key) b.api_key = key;
      for (const [k, v] of Object.entries(form)) {
        if (k === "model_priority")
          b[k] = v.split("\n").map((x) => x.trim()).filter(Boolean);
        else if (["font_min", "font_max", "batch_pages"].includes(k)) b[k] = parseInt(v);
        else if (k === "det_conf") b[k] = parseFloat(v);
        else b[k] = v;
      }
      return api.patchSettings(b);
    },
    onSuccess: () => {
      setMsg("saved"); setKey(""); setForm({});
      qc.invalidateQueries({ queryKey: ["settings", "status"] });
    },
    onError: (e) => setMsg(e instanceof Error ? e.message : String(e)),
  });

  if (!s) return <div className="p-8 text-neutral-400">Loading…</div>;
  const val = (k: keyof typeof s) => form[k] ?? String(s[k] ?? "");

  const row = (label: string, el: React.ReactNode) => (
    <label className="grid grid-cols-1 items-center gap-1 sm:grid-cols-[180px_1fr]">
      <span className="text-sm text-neutral-400">{label}</span>{el}
    </label>
  );

  return (
    <div className="mx-auto max-w-xl space-y-4 pt-4">
      <h1 className="text-xl font-bold">Settings</h1>

      {row("OpenRouter API key",
        <input type="password" value={key} onChange={(e) => setKey(e.target.value)}
               placeholder={s.has_key ? "saved" : "sk-or-…"} aria-label="API key" />)}

      {row("Target language",
        <input value={val("target_lang")} aria-label="Target language"
               onChange={(e) => setForm({ ...form, target_lang: e.target.value })} />)}

      <div>
        <div className="mb-1 flex items-center justify-between">
          <span className="text-sm text-neutral-400">Model priority (one per line)</span>
          <button className="btn text-xs"
                  onClick={() => api.models().then(setModels).catch((e) => setMsg(String(e.message ?? e)))}>
            Load free models
          </button>
        </div>
        <textarea rows={4} value={form.model_priority ?? (s.model_priority ?? []).join("\n")}
                  aria-label="Model priority"
                  onChange={(e) => setForm({ ...form, model_priority: e.target.value })}
                  className="w-full font-mono text-xs" />
        {models && (
          <div className="max-h-40 overflow-y-auto rounded bg-neutral-900 p-2 text-xs">
            {models.map((m) => (
              <button key={m.id} className="block w-full truncate text-left hover:text-indigo-300"
                      onClick={() => setForm((f) => ({
                        ...f,
                        model_priority: `${f.model_priority ?? s.model_priority.join("\n")}${(f.model_priority ?? s.model_priority.join("\n")) ? "\n" : ""}${m.id}`,
                      }))}>
                {m.id}
              </button>
            ))}
          </div>
        )}
      </div>

      {row("Inpainter",
        <select value={form.inpainter ?? s.inpainter} aria-label="Inpainter"
                onChange={(e) => setForm({ ...form, inpainter: e.target.value })}>
          <option value="auto">auto</option><option value="opencv">opencv</option>
          <option value="lama">lama</option>
        </select>)}
      {row("Device",
        <select value={form.device ?? s.device} aria-label="Device"
                onChange={(e) => setForm({ ...form, device: e.target.value })}>
          <option value="auto">auto</option><option value="cuda">cuda</option>
          <option value="cpu">cpu</option>
        </select>)}
      {row("Font min",
        <input type="number" value={form.font_min ?? s.font_min} aria-label="Font min"
               onChange={(e) => setForm({ ...form, font_min: e.target.value })} />)}
      {row("Font max",
        <input type="number" value={form.font_max ?? s.font_max} aria-label="Font max"
               onChange={(e) => setForm({ ...form, font_max: e.target.value })} />)}
      {row("Detection confidence",
        <input type="number" step="0.05" min="0" max="1" value={form.det_conf ?? s.det_conf}
               aria-label="Detection confidence"
               onChange={(e) => setForm({ ...form, det_conf: e.target.value })} />)}
      {row("Batch pages",
        <input type="number" value={form.batch_pages ?? s.batch_pages} aria-label="Batch pages"
               onChange={(e) => setForm({ ...form, batch_pages: e.target.value })} />)}

      <div className="flex items-center gap-3">
        <button className="btn-primary" onClick={() => save.mutate()}
                disabled={save.isPending}>Save</button>
        <span className="text-sm text-neutral-400">{msg}</span>
      </div>
    </div>
  );
}

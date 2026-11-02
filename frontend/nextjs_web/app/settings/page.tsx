"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Download, KeyRound, Languages, LoaderCircle, Paintbrush, Settings2, Type } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

export default function SettingsPage() {
  const qc = useQueryClient();
  const { data: settings } = useQuery({ queryKey: ["settings"], queryFn: api.getSettings });
  const [form, setForm] = useState<Record<string, string>>({});
  const [key, setKey] = useState("");
  const [models, setModels] = useState<{ id: string; name: string }[] | null>(null);
  const [loadingModels, setLoadingModels] = useState(false);

  const save = useMutation({
    mutationFn: () => {
      const body: Record<string, unknown> = {};
      if (key) body.api_key = key;
      for (const [name, value] of Object.entries(form)) {
        if (name === "model_priority") body[name] = value.split("\n").map((x) => x.trim()).filter(Boolean);
        else if (["font_min", "font_max", "batch_pages", "mask_dilate", "mask_feather"].includes(name))
          body[name] = Number.parseInt(value, 10);
        else if (["det_conf", "mask_thresh"].includes(name)) body[name] = Number.parseFloat(value);
        else body[name] = value;
      }
      return api.patchSettings(body);
    },
    onSuccess: () => {
      setKey("");
      setForm({});
      qc.invalidateQueries({ queryKey: ["settings"] });
      qc.invalidateQueries({ queryKey: ["status"] });
      toast.success("Settings saved");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : String(error)),
  });

  if (!settings) return <SettingsSkeleton />;
  const val = (name: keyof typeof settings) => form[name] ?? String(settings[name] ?? "");
  const change = (name: string, value: string) => setForm((current) => ({ ...current, [name]: value }));
  const modelText = form.model_priority ?? settings.model_priority.join("\n");
  const dirty = Boolean(key) || Object.keys(form).length > 0;

  async function loadModels() {
    setLoadingModels(true);
    try {
      setModels(await api.models());
    } catch (error) {
      toast.error(error instanceof Error ? error.message : String(error));
    } finally {
      setLoadingModels(false);
    }
  }

  return (
    <div className="mx-auto flex h-[calc(100dvh-4rem)] max-w-4xl flex-col px-4 pt-8 sm:px-6">
      <div className="min-h-0 flex-1 space-y-7 overflow-y-auto pb-6">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Personalize your setup</p>
          <h1 className="mt-1 text-3xl font-semibold tracking-tight">Settings</h1>
          <p className="mt-2 text-sm text-muted-foreground">Translation, cleanup, and reader defaults.</p>
        </div>

        <div className="grid gap-5 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Languages className="size-4 text-primary" />Translation</CardTitle>
            <CardDescription>Choose where and how your text is translated.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="grid gap-2">
              <Label htmlFor="api-key" className="flex items-center gap-2"><KeyRound className="size-3.5" />OpenRouter API key</Label>
              <Input id="api-key" type="password" autoComplete="new-password" value={key}
                     onChange={(e) => setKey(e.target.value)}
                     placeholder={settings.has_key ? "Saved — enter a new key to replace" : "sk-or-…"}
                     aria-label="API key" />
              <p className="text-xs text-muted-foreground">The saved key is never shown here.</p>
            </div>
            <div className="grid gap-2">
              <Label htmlFor="target-language">Target language</Label>
              <Input id="target-language" value={val("target_lang")}
                     onChange={(e) => change("target_lang", e.target.value)} aria-label="Target language" />
            </div>
            <div className="grid gap-2">
              <div className="flex flex-wrap items-end justify-between gap-2">
                <div>
                  <Label htmlFor="model-priority">Model priority</Label>
                  <p className="mt-1 text-xs text-muted-foreground">One model ID per line, tried in order.</p>
                </div>
                <Button size="sm" variant="outline" onClick={loadModels} disabled={loadingModels}>
                  {loadingModels ? <LoaderCircle className="animate-spin" /> : <Download />}
                  Load free models
                </Button>
              </div>
              <Textarea id="model-priority" rows={5} value={modelText}
                        aria-label="Model priority" className="font-mono text-xs"
                        onChange={(e) => change("model_priority", e.target.value)} />
              {models && (
                <div className="max-h-44 overflow-y-auto rounded-lg border bg-muted/40 p-2">
                  <p className="mb-2 px-2 text-xs font-medium text-muted-foreground">
                    Select a model to append
                  </p>
                  {models.map((model) => (
                    <button key={model.id} type="button"
                      className="block w-full rounded-md px-2 py-1.5 text-left text-xs hover:bg-accent"
                      onClick={() => change("model_priority", `${modelText}${modelText ? "\n" : ""}${model.id}`)}>
                      <span className="font-medium">{model.name}</span>
                      <span className="ml-2 text-muted-foreground">{model.id}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Paintbrush className="size-4 text-primary" />Cleaning</CardTitle>
            <CardDescription>Configure bubble masks and background restoration.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Field label="Inpainter" htmlFor="inpainter">
              <Select value={val("inpainter")} onValueChange={(value) => change("inpainter", value)}>
                <SelectTrigger id="inpainter" aria-label="Inpainter" className="w-full"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="auto">Automatic</SelectItem>
                  <SelectItem value="lama">LaMa</SelectItem>
                  <SelectItem value="opencv">OpenCV</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <Field label="Masker" htmlFor="masker">
              <Select value={val("masker")} onValueChange={(value) => change("masker", value)}>
                <SelectTrigger id="masker" aria-label="Masker" className="w-full"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="combined">Combined</SelectItem>
                  <SelectItem value="model">Text model</SelectItem>
                  <SelectItem value="threshold">Threshold</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <NumberField label="Mask grow (px)" value={val("mask_dilate")} min={0} max={20}
                           onChange={(v) => change("mask_dilate", v)} />
              <NumberField label="Edge feather (px)" value={val("mask_feather")} min={0} max={20}
                           onChange={(v) => change("mask_feather", v)} />
            </div>
            <NumberField label="Text model threshold" value={val("mask_thresh")} min={0} max={1} step={0.05}
                         onChange={(v) => change("mask_thresh", v)} />
            <div className="rounded-lg border border-primary/20 bg-primary/5 p-3 text-xs text-muted-foreground">
              Mask settings apply to newly processed pages — use <strong className="font-semibold text-foreground">Reprocess</strong> to update existing pages.
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Type className="size-4 text-primary" />Typesetting</CardTitle>
            <CardDescription>Set the minimum and maximum translation text size.</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-3">
            <NumberField label="Minimum font size" value={val("font_min")} min={6} max={96}
                         onChange={(v) => change("font_min", v)} />
            <NumberField label="Maximum font size" value={val("font_max")} min={6} max={96}
                         onChange={(v) => change("font_max", v)} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Settings2 className="size-4 text-primary" />System</CardTitle>
            <CardDescription>Processing device and detection defaults.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Field label="Device" htmlFor="device">
              <Select value={val("device")} onValueChange={(value) => change("device", value)}>
                <SelectTrigger id="device" aria-label="Device" className="w-full"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="auto">Automatic</SelectItem>
                  <SelectItem value="cuda">CUDA</SelectItem>
                  <SelectItem value="cpu">CPU</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <NumberField label="Detection confidence" value={val("det_conf")} min={0} max={1} step={0.05}
                           onChange={(v) => change("det_conf", v)} />
              <NumberField label="Batch pages" value={val("batch_pages")} min={1} max={32}
                           onChange={(v) => change("batch_pages", v)} />
            </div>
            <p className="text-xs text-muted-foreground">
              Active cache: <span className="break-all font-mono">{settings.cache_dir}</span>
            </p>
          </CardContent>
        </Card>
        </div>
      </div>

      <div className="sticky bottom-0 z-20 -mx-4 shrink-0 border-t bg-background/90 px-4 py-3 backdrop-blur-xl sm:-mx-6 sm:px-6">
        <div className="flex items-center justify-between gap-4">
          <p className="min-h-5 text-sm text-muted-foreground">{dirty ? "Unsaved changes" : ""}</p>
          <Button size="lg" onClick={() => save.mutate()} disabled={save.isPending}>
            {save.isPending ? <LoaderCircle className="animate-spin" /> : <Check />}
            {save.isPending ? "Saving…" : "Save settings"}
          </Button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, htmlFor, children }: {
  label: string; htmlFor: string; children: React.ReactNode;
}) {
  return <div className="grid gap-2"><Label htmlFor={htmlFor}>{label}</Label>{children}</div>;
}

function NumberField({ label, value, min, max, step = 1, onChange }: {
  label: string; value: string; min: number; max: number; step?: number;
  onChange: (value: string) => void;
}) {
  const id = label.toLowerCase().replaceAll(" ", "-").replace(/[()]/g, "");
  return (
    <div className="grid min-w-0 gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} type="number" value={value} min={min} max={max} step={step}
             onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

function SettingsSkeleton() {
  return <div className="mx-auto max-w-4xl space-y-4 px-4 py-8"><div className="h-10 w-52 animate-pulse rounded bg-muted" /><div className="h-80 animate-pulse rounded-xl bg-muted" /></div>;
}

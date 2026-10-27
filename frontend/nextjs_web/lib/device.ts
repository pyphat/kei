let cached: string | null = null;

/** Stable per-device id for progress sync (localStorage). */
export function deviceId(): string {
  if (cached) return cached;
  cached = localStorage.getItem("kei_device_id");
  if (!cached) {
    cached = crypto.randomUUID();
    localStorage.setItem("kei_device_id", cached);
  }
  return cached;
}

const re = /(\d+)/g;

/** Natural sort: embedded digit runs compare numerically, case-insensitive. */
export function natkey(s: string): (string | number)[] {
  const out: (string | number)[] = [];
  let last = 0;
  s = s.toLowerCase();
  for (const m of s.matchAll(re)) {
    out.push(s.slice(last, m.index));
    out.push(Number(m[0]));
    last = m.index! + m[0].length;
  }
  out.push(s.slice(last));
  return out;
}

export function natsort(a: string, b: string): number {
  const ka = natkey(a), kb = natkey(b);
  for (let i = 0; i < Math.min(ka.length, kb.length); i++) {
    const x = ka[i], y = kb[i];
    if (x === y) continue;
    if (typeof x === "number" && typeof y === "number") return x - y;
    return String(x) < String(y) ? -1 : 1;
  }
  return ka.length - kb.length;
}

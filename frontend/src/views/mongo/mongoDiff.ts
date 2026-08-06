// Attribute-wise, key-aligned JSON diff (ported from the original vanilla tool).
// Produces aligned rows whose left/right cells carry a CSS class + inner HTML,
// so the two columns line up 1:1. Keys are sorted and matched across both sides.

/* eslint-disable @typescript-eslint/no-explicit-any */

export type Cell = { cls: string; html: string };
export type DiffRow = { depth: number; left: Cell; right: Cell };
export interface DiffStats {
  eq: number;
  diff: number;
  missing: number;
}

const MISSING = Symbol("missing");
type JType = "missing" | "array" | "object" | "scalar";

function jtype(v: any): JType {
  if (v === MISSING) return "missing";
  if (Array.isArray(v)) return "array";
  if (v && typeof v === "object") return "object";
  return "scalar";
}

function jDeepEqual(a: any, b: any): boolean {
  const ta = jtype(a);
  const tb = jtype(b);
  if (ta !== tb) return false;
  if (ta === "scalar" || ta === "missing") return a === b;
  if (ta === "array") {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!jDeepEqual(a[i], b[i])) return false;
    return true;
  }
  const ak = Object.keys(a).sort();
  const bk = Object.keys(b).sort();
  if (ak.length !== bk.length) return false;
  for (let i = 0; i < ak.length; i++) {
    if (ak[i] !== bk[i]) return false;
    if (!jDeepEqual(a[ak[i]], b[ak[i]])) return false;
  }
  return true;
}

function esc(s: any): string {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
}
function jfmt(v: any): string {
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}
function jKey(key: any): string {
  if (key === null) return "";
  if (typeof key === "number") return `<span class="j-k">${key}</span>: `;
  return `<span class="j-k">"${esc(key)}":</span> `;
}
function jAbsent(key: any): string {
  return `${jKey(key)}<span class="j-v j-dash">—</span>`;
}

function jtokenize(s: string): string[] {
  return s.match(/\s+|[^\s]+/g) || [];
}

function jRenderTokens(tokens: { t: string; eq: boolean }[]): string {
  let out = "";
  for (const tk of tokens) out += tk.eq ? esc(tk.t) : `<span class="j-chg">${esc(tk.t)}</span>`;
  return out;
}

function jWordDiff(a: string, b: string): [string, string] {
  const A = jtokenize(a);
  const B = jtokenize(b);
  const n = A.length;
  const m = B.length;
  if (n * m > 250000) {
    return [`<span class="j-chg">${esc(a)}</span>`, `<span class="j-chg">${esc(b)}</span>`];
  }
  const dp: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const left: { t: string; eq: boolean }[] = [];
  const right: { t: string; eq: boolean }[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (A[i] === B[j]) {
      left.push({ t: A[i], eq: true });
      right.push({ t: B[j], eq: true });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      left.push({ t: A[i], eq: false });
      i++;
    } else {
      right.push({ t: B[j], eq: false });
      j++;
    }
  }
  while (i < n) {
    left.push({ t: A[i], eq: false });
    i++;
  }
  while (j < m) {
    right.push({ t: B[j], eq: false });
    j++;
  }
  return [jRenderTokens(left), jRenderTokens(right)];
}

function jPairStatus(leftPresent: boolean, rightPresent: boolean, equal: boolean): [string, string] {
  if (!leftPresent && !rightPresent) return ["j-missing", "j-missing"];
  if (!leftPresent) return ["j-missing", "j-diff"];
  if (!rightPresent) return ["j-diff", "j-missing"];
  return equal ? ["j-eq", "j-eq"] : ["j-diff", "j-diff"];
}

function jChild(container: any, type: JType, key: any): any {
  if (type === "object") {
    return Object.prototype.hasOwnProperty.call(container, key) ? container[key] : MISSING;
  }
  if (type === "array") {
    const i = Number(key);
    return i < container.length ? container[i] : MISSING;
  }
  return MISSING;
}

function jDiff(left: any, right: any, key: any, depth: number, rows: DiffRow[], stats: DiffStats): void {
  const lt = jtype(left);
  const rt = jtype(right);
  const lContainer = lt === "object" || lt === "array";
  const rContainer = rt === "object" || rt === "array";
  const recurse = (lContainer || rContainer) && lt !== "scalar" && rt !== "scalar";

  if (recurse) {
    const anyObject = lt === "object" || rt === "object";
    const arrMode = !anyObject;
    let keys: any[];
    if (arrMode) {
      const len = Math.max(lContainer ? left.length : 0, rContainer ? right.length : 0);
      keys = Array.from({ length: len }, (_, i) => i);
    } else {
      const set = new Set<string>();
      if (lt === "object") Object.keys(left).forEach((k) => set.add(k));
      if (rt === "object") Object.keys(right).forEach((k) => set.add(k));
      if (lt === "array") (left as any[]).forEach((_, i) => set.add(String(i)));
      if (rt === "array") (right as any[]).forEach((_, i) => set.add(String(i)));
      keys = [...set].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    }
    const eq = lContainer && rContainer && jDeepEqual(left, right);
    const [lcls, rcls] = jPairStatus(lContainer, rContainer, eq);
    const open = arrMode ? "[" : "{";
    const close = arrMode ? "]" : "}";
    rows.push({
      depth,
      left: { cls: lcls, html: lContainer ? jKey(key) + open : jAbsent(key) },
      right: { cls: rcls, html: rContainer ? jKey(key) + open : jAbsent(key) },
    });
    for (const ck of keys) {
      const lchild = jChild(left, lt, ck);
      const rchild = jChild(right, rt, ck);
      jDiff(lchild, rchild, arrMode ? Number(ck) : ck, depth + 1, rows, stats);
    }
    rows.push({
      depth,
      left: { cls: lcls, html: lContainer ? close : "" },
      right: { cls: rcls, html: rContainer ? close : "" },
    });
    return;
  }

  const lPresent = lt !== "missing";
  const rPresent = rt !== "missing";
  const eq = lPresent && rPresent && jDeepEqual(left, right);
  const [lcls, rcls] = jPairStatus(lPresent, rPresent, eq);
  if (!lPresent || !rPresent) stats.missing++;
  else if (eq) stats.eq++;
  else stats.diff++;

  let lInner = "";
  let rInner = "";
  if (lPresent && rPresent) {
    if (eq) {
      lInner = esc(jfmt(left));
      rInner = esc(jfmt(right));
    } else if (typeof left === "string" && typeof right === "string") {
      const [la, rb] = jWordDiff(left, right);
      lInner = `"${la}"`;
      rInner = `"${rb}"`;
    } else {
      lInner = `<span class="j-chg">${esc(jfmt(left))}</span>`;
      rInner = `<span class="j-chg">${esc(jfmt(right))}</span>`;
    }
  } else if (lPresent) {
    lInner = `<span class="j-chg">${esc(jfmt(left))}</span>`;
  } else if (rPresent) {
    rInner = `<span class="j-chg">${esc(jfmt(right))}</span>`;
  }

  rows.push({
    depth,
    left: { cls: lcls, html: lPresent ? jKey(key) + `<span class="j-v">${lInner}</span>` : jAbsent(key) },
    right: { cls: rcls, html: rPresent ? jKey(key) + `<span class="j-v">${rInner}</span>` : jAbsent(key) },
  });
}

export function computeDiff(left: any, right: any): { rows: DiffRow[]; stats: DiffStats } {
  const rows: DiffRow[] = [];
  const stats: DiffStats = { eq: 0, diff: 0, missing: 0 };
  jDiff(left, right, null, 0, rows, stats);
  return { rows, stats };
}

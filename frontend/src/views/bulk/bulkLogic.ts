/* eslint-disable @typescript-eslint/no-explicit-any */
import type { SchemaDescriptor, SchemaField } from "../../api/types";

export type BulkRecord = any;
export type FieldMap = Map<string, SchemaField>;

export interface BulkFilter {
  id: number;
  field: string;
  op: string;
  values: string[];
}

export interface BulkOp {
  v: string;
  label: string;
  needsValue: boolean;
}

export const BULK_OPS: BulkOp[] = [
  { v: "eq", label: "equals", needsValue: true },
  { v: "ne", label: "not equals", needsValue: true },
  { v: "contains", label: "contains", needsValue: true },
  { v: "ncontains", label: "does not contain", needsValue: true },
  { v: "starts", label: "starts with", needsValue: true },
  { v: "ends", label: "ends with", needsValue: true },
  { v: "gt", label: "greater than", needsValue: true },
  { v: "gte", label: "greater or equal", needsValue: true },
  { v: "lt", label: "less than", needsValue: true },
  { v: "lte", label: "less or equal", needsValue: true },
  { v: "empty", label: "is empty", needsValue: false },
  { v: "notempty", label: "is not empty", needsValue: false },
];

export interface ParseResult {
  format: string;
  records: BulkRecord[];
  delimiter: string | null;
  headers?: string[];
}

export function schemaFieldMap(schema: SchemaDescriptor | null): FieldMap {
  const m: FieldMap = new Map();
  if (schema && schema.coercible) {
    (schema.fields || []).forEach((f) => m.set(f.name, f));
  }
  return m;
}

export function coerceBySchema(value: any, def: SchemaField): any {
  const types = def.types || [];
  const isStr = typeof value === "string";
  const empty = value === undefined || value === null || (isStr && value.trim() === "");
  if (empty) {
    if (def.nullable) return null;
    if (types.includes("string")) return "";
    return null;
  }
  if (types.includes("string")) return isStr ? value : String(value);
  if (types.includes("integer")) {
    const s = String(value).trim();
    if (/^-?\d+$/.test(s)) {
      const n = Number(s);
      if (Number.isSafeInteger(n)) return n;
    }
    return String(value);
  }
  if (types.includes("number")) {
    const n = Number(String(value).trim());
    return Number.isFinite(n) ? n : String(value);
  }
  if (types.includes("boolean")) {
    const s = String(value).trim().toLowerCase();
    if (["true", "1", "y", "yes"].includes(s)) return true;
    if (["false", "0", "n", "no"].includes(s)) return false;
    return String(value);
  }
  if (types.includes("array") || types.includes("object")) {
    if (isStr) {
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    }
    return value;
  }
  return isStr ? value : String(value);
}

export function applySchemaToRecord(obj: Record<string, any>, fieldMap: FieldMap): Record<string, any> {
  if (!fieldMap.size) return obj;
  const out: Record<string, any> = {};
  Object.keys(obj).forEach((k) => {
    const def = fieldMap.get(k);
    out[k] = def ? coerceBySchema(obj[k], def) : obj[k];
  });
  return out;
}

export function validateRecord(obj: BulkRecord, schema: SchemaDescriptor | null): string[] {
  const issues: string[] = [];
  if (!schema || !schema.coercible) return issues;
  (schema.required || []).forEach((name) => {
    const v = obj?.[name];
    if (v === undefined || v === null || (typeof v === "string" && v.trim() === "")) {
      issues.push(`${name} is required`);
    }
  });
  (schema.fields || []).forEach((def) => {
    if (!obj || !(def.name in obj)) return;
    const v = obj[def.name];
    if (v === null) {
      if (!def.nullable) issues.push(`${def.name} must not be null`);
      return;
    }
    if (def.enum && def.enum.length && !def.enum.includes(String(v))) {
      issues.push(`${def.name}="${v}" not in enum`);
    }
    const types = def.types || [];
    if (!types.includes("string")) {
      if (types.includes("integer") && !(typeof v === "number" && Number.isInteger(v))) issues.push(`${def.name} not integer`);
      else if (types.includes("number") && typeof v !== "number") issues.push(`${def.name} not number`);
      else if (types.includes("boolean") && typeof v !== "boolean") issues.push(`${def.name} not boolean`);
    }
  });
  if (schema.additionalProperties === false && obj && typeof obj === "object") {
    const allowed = new Set((schema.fields || []).map((f) => f.name));
    Object.keys(obj).forEach((k) => {
      if (!allowed.has(k)) issues.push(`unexpected field "${k}"`);
    });
  }
  return issues;
}

export function countInvalid(
  records: BulkRecord[],
  schema: SchemaDescriptor | null
): { invalid: number; samples: string[] } {
  let invalid = 0;
  const samples: string[] = [];
  if (!schema || !schema.coercible) return { invalid, samples };
  for (let i = 0; i < records.length; i++) {
    const issues = validateRecord(records[i], schema);
    if (issues.length) {
      invalid++;
      if (samples.length < 5) samples.push(`row ${i + 1}: ${issues.slice(0, 4).join("; ")}`);
    }
  }
  return { invalid, samples };
}

// ---- Parsers ----
export function parseJsonMessages(text: string, fieldMap: FieldMap): ParseResult {
  const data = JSON.parse(text);
  const items = Array.isArray(data) ? data : [data];
  const records =
    fieldMap && fieldMap.size
      ? items.map((it) =>
          it && typeof it === "object" && !Array.isArray(it) ? applySchemaToRecord(it, fieldMap) : it
        )
      : items;
  return { format: "JSON", records, delimiter: null };
}

function normalizeDelim(choice: string): string {
  if (choice === "tab") return "\t";
  return choice;
}

export function detectDelimiter(line: string): string {
  const cands = [",", "\t", "|", ";"];
  let best = ",";
  let bestCount = 0;
  for (const c of cands) {
    const count = line.split(c).length - 1;
    if (count > bestCount) {
      bestCount = count;
      best = c;
    }
  }
  return best;
}

function parseTable(text: string, delimiter: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else inQuotes = false;
      } else field += ch;
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === delimiter) {
      row.push(field);
      field = "";
    } else if (ch === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else if (ch !== "\r") {
      field += ch;
    }
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

function coerceCell(raw: string): any {
  const v = String(raw).trim();
  if (v === "") return null;
  if (v === "true") return true;
  if (v === "false") return false;
  if (/^-?\d+$/.test(v)) {
    const n = Number(v);
    if (Number.isSafeInteger(n)) return n;
  }
  if (/^-?(\d+\.\d*|\.\d+|\d+)(e[+-]?\d+)?$/i.test(v) && v !== ".") {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return v;
}

export function parseDelimited(text: string, choice: string, fieldMap: FieldMap): ParseResult {
  const firstLine = text.split(/\r?\n/, 1)[0] || "";
  const delimiter = choice === "auto" ? detectDelimiter(firstLine) : normalizeDelim(choice);
  const rows = parseTable(text, delimiter).filter((r) => !(r.length === 1 && r[0].trim() === ""));
  if (!rows.length) throw new Error("File is empty.");
  const headers = rows[0].map((h) => h.trim());
  if (!headers.some((h) => h.length)) throw new Error("No header row found.");
  const useSchema = fieldMap && fieldMap.size > 0;
  const records: BulkRecord[] = [];
  for (let i = 1; i < rows.length; i++) {
    const cells = rows[i];
    const obj: Record<string, any> = {};
    headers.forEach((h, idx) => {
      if (!h) return;
      const cell = cells[idx] !== undefined ? cells[idx] : "";
      obj[h] = useSchema ? String(cell).trim() : coerceCell(cell);
    });
    records.push(useSchema ? applySchemaToRecord(obj, fieldMap) : obj);
  }
  return { format: "CSV/TXT", records, delimiter, headers };
}

export function computeBulkFields(result: ParseResult): string[] {
  if (result.headers && result.headers.length) return result.headers.filter(Boolean);
  const seen = new Set<string>();
  const out: string[] = [];
  (result.records || []).forEach((r) => {
    if (r && typeof r === "object" && !Array.isArray(r)) {
      Object.keys(r).forEach((k) => {
        if (!seen.has(k)) {
          seen.add(k);
          out.push(k);
        }
      });
    }
  });
  return out;
}

// ---- Filtering ----
export function filterValues(f: BulkFilter): string[] {
  const out: string[] = [];
  for (const v of f.values || []) {
    const s = String(v).trim();
    if (s !== "" && !out.includes(s)) out.push(s);
  }
  return out;
}

export function isFilterActive(f: BulkFilter): boolean {
  if (!f || !f.field) return false;
  const opDef = BULK_OPS.find((o) => o.v === f.op);
  if (opDef && !opDef.needsValue) return true;
  return filterValues(f).length > 0;
}

function compileFilter(f: BulkFilter): (rec: BulkRecord) => boolean {
  const field = f.field;
  const op = f.op;
  const readObj = (rec: BulkRecord) => (rec && typeof rec === "object" && !Array.isArray(rec) ? rec[field] : undefined);

  if (op === "empty") {
    return (rec) => {
      const v = readObj(rec);
      return v === undefined || v === null || String(v).trim() === "";
    };
  }
  if (op === "notempty") {
    return (rec) => {
      const v = readObj(rec);
      return !(v === undefined || v === null || String(v).trim() === "");
    };
  }

  const targets = filterValues(f).map((raw) => ({
    raw,
    lc: raw.toLowerCase(),
    num: Number(raw),
    numOk: Number.isFinite(Number(raw)),
  }));
  const negative = op === "ne" || op === "ncontains";
  const baseOp = op === "ne" ? "eq" : op === "ncontains" ? "contains" : op;

  const matchesOne = (v: any, t: (typeof targets)[number]): boolean => {
    switch (baseOp) {
      case "eq": {
        if (v == null) return t.raw === "";
        const a = Number(v);
        if (t.numOk && Number.isFinite(a)) return a === t.num;
        return String(v).toLowerCase() === t.lc;
      }
      case "contains":
        return v != null && String(v).toLowerCase().includes(t.lc);
      case "starts":
        return v != null && String(v).toLowerCase().startsWith(t.lc);
      case "ends":
        return v != null && String(v).toLowerCase().endsWith(t.lc);
      case "gt":
      case "gte":
      case "lt":
      case "lte": {
        if (!t.numOk) return false;
        const a = Number(v);
        if (!Number.isFinite(a)) return false;
        if (baseOp === "gt") return a > t.num;
        if (baseOp === "gte") return a >= t.num;
        if (baseOp === "lt") return a < t.num;
        return a <= t.num;
      }
      default:
        return true;
    }
  };

  return (rec) => {
    const v = readObj(rec);
    let any = false;
    for (let i = 0; i < targets.length; i++) {
      if (matchesOne(v, targets[i])) {
        any = true;
        break;
      }
    }
    return negative ? !any : any;
  };
}

export function computeFiltered(
  records: BulkRecord[],
  filters: BulkFilter[],
  matchMode: "all" | "any"
): BulkRecord[] {
  const active = filters.filter(isFilterActive);
  if (!active.length) return records;
  const preds = active.map(compileFilter);
  const any = matchMode === "any";
  return records.filter((rec) => {
    if (any) {
      for (let i = 0; i < preds.length; i++) if (preds[i](rec)) return true;
      return false;
    }
    for (let i = 0; i < preds.length; i++) if (!preds[i](rec)) return false;
    return true;
  });
}

export function filterLabel(f: BulkFilter): string {
  const opDef = BULK_OPS.find((o) => o.v === f.op);
  const opLabel = opDef ? opDef.label : f.op;
  if (opDef && !opDef.needsValue) return `${f.field} ${opLabel}`;
  const vals = filterValues(f);
  const shown = vals.map((v) => `"${v}"`).join(", ");
  const suffix = vals.length > 1 ? (f.op === "ne" || f.op === "ncontains" ? " (none of)" : " (any of)") : "";
  return `${f.field} ${opLabel} ${shown}${suffix}`;
}

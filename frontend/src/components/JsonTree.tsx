import { useState } from "react";
import Box from "@mui/material/Box";

/**
 * Lightweight, dependency-free collapsible JSON tree viewer. Every object/array node is individually
 * foldable; nodes deeper than {@code autoOpenDepth} start collapsed so large results are readable at a
 * glance and drilled into on demand. Leaf values are type-colored.
 */

const COLORS = {
  key: "#8250df",
  string: "#0a7d33",
  number: "#0550ae",
  boolean: "#953800",
  null: "#6b7280",
  punct: "#57606a",
};

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function Leaf({ value }: { value: unknown }) {
  if (value === null) return <Box component="span" sx={{ color: COLORS.null }}>null</Box>;
  if (typeof value === "string") return <Box component="span" sx={{ color: COLORS.string }}>"{value}"</Box>;
  if (typeof value === "number") return <Box component="span" sx={{ color: COLORS.number }}>{String(value)}</Box>;
  if (typeof value === "boolean") return <Box component="span" sx={{ color: COLORS.boolean }}>{String(value)}</Box>;
  return <Box component="span">{String(value)}</Box>;
}

function Node({
  name,
  value,
  depth,
  autoOpenDepth,
  isLast,
}: {
  name?: string;
  value: unknown;
  depth: number;
  autoOpenDepth: number;
  isLast: boolean;
}) {
  const branch = isObject(value) || Array.isArray(value);
  const [open, setOpen] = useState(depth < autoOpenDepth);

  const keyLabel = name !== undefined && (
    <Box component="span" sx={{ color: COLORS.key }}>"{name}"<Box component="span" sx={{ color: COLORS.punct }}>: </Box></Box>
  );

  if (!branch) {
    return (
      <Box sx={{ pl: depth === 0 ? 0 : 2, whiteSpace: "pre-wrap", wordBreak: "break-word" }}>
        {keyLabel}
        <Leaf value={value} />
        {!isLast && <Box component="span" sx={{ color: COLORS.punct }}>,</Box>}
      </Box>
    );
  }

  const entries: [string | number, unknown][] = Array.isArray(value)
    ? value.map((v, i) => [i, v])
    : Object.entries(value as Record<string, unknown>);
  const openBrace = Array.isArray(value) ? "[" : "{";
  const closeBrace = Array.isArray(value) ? "]" : "}";
  const count = entries.length;

  return (
    <Box sx={{ pl: depth === 0 ? 0 : 2 }}>
      <Box
        onClick={() => setOpen((o) => !o)}
        sx={{ cursor: "pointer", userSelect: "none", "&:hover": { bgcolor: "action.hover", borderRadius: 0.5 } }}
      >
        <Box component="span" sx={{ display: "inline-block", width: 14, color: COLORS.punct, textAlign: "center" }}>
          {count > 0 ? (open ? "▾" : "▸") : " "}
        </Box>
        {keyLabel}
        <Box component="span" sx={{ color: COLORS.punct }}>{openBrace}</Box>
        {!open && count > 0 && (
          <Box component="span" sx={{ color: COLORS.null, fontStyle: "italic" }}> {count} {count === 1 ? "item" : "items"} </Box>
        )}
        {!open && <Box component="span" sx={{ color: COLORS.punct }}>{closeBrace}{!isLast ? "," : ""}</Box>}
      </Box>
      {open && (
        <>
          {entries.map(([k, v], i) => (
            <Node
              key={String(k)}
              name={Array.isArray(value) ? undefined : String(k)}
              value={v}
              depth={depth + 1}
              autoOpenDepth={autoOpenDepth}
              isLast={i === entries.length - 1}
            />
          ))}
          <Box sx={{ pl: 2 }}>
            <Box component="span" sx={{ color: COLORS.punct }}>{closeBrace}{!isLast ? "," : ""}</Box>
          </Box>
        </>
      )}
    </Box>
  );
}

export function JsonTree({
  data,
  autoOpenDepth = 1,
  maxHeight = 360,
}: {
  data: unknown;
  autoOpenDepth?: number;
  maxHeight?: number;
}) {
  return (
    <Box
      sx={{
        fontFamily: "monospace",
        fontSize: 12,
        lineHeight: 1.6,
        p: 1,
        maxHeight,
        overflow: "auto",
        bgcolor: "#fff",
      }}
    >
      <Node value={data} depth={0} autoOpenDepth={autoOpenDepth} isLast />
    </Box>
  );
}

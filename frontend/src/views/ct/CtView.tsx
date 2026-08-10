import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { keyframes } from "@mui/system";
import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Collapse from "@mui/material/Collapse";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import IconButton from "@mui/material/IconButton";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import RefreshIcon from "@mui/icons-material/Refresh";
import SearchIcon from "@mui/icons-material/Search";
import ClearIcon from "@mui/icons-material/Clear";
import CodeIcon from "@mui/icons-material/Code";
import LockIcon from "@mui/icons-material/Lock";
import CloudDoneIcon from "@mui/icons-material/CloudDone";
import ShoppingBagIcon from "@mui/icons-material/ShoppingBag";
import { api } from "../../api/client";
import type { CtCategory, CtProductResponse, CtSku, CtStatus, CtVariant } from "../../api/types";
import { useAppState } from "../../app/AppState";
import { useUi } from "../../app/UiProvider";

const ACCENT = "#f5871f";

const pulse = keyframes`
  0% { transform: scale(0.9); opacity: 0.75; }
  70% { transform: scale(1.9); opacity: 0; }
  100% { transform: scale(1.9); opacity: 0; }
`;

type ConnState = "checking" | "connected" | "down";

const CONN: Record<ConnState, { color: string; label: string }> = {
  checking: { color: "#9aa3b2", label: "Checking commercetools connection…" },
  connected: { color: "#1aa564", label: "Connected to commercetools" },
  down: { color: "#e0413f", label: "commercetools unreachable / not configured" },
};

/** Formats an attribute/JSON value for compact inline display. */
function fmt(v: unknown): string {
  if (v === null || v === undefined) return "—";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

/** Collapsible, syntax-lite JSON block reusing the shared monospace styling. */
function JsonBlock({ label, obj, defaultOpen = false }: { label: string; obj: unknown; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const text = useMemo(() => JSON.stringify(obj, null, 2), [obj]);
  return (
    <Box sx={{ borderRadius: 2, border: "1px solid", borderColor: "divider", overflow: "hidden" }}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={1}
        onClick={() => setOpen((o) => !o)}
        sx={{ px: 1.25, py: 0.75, cursor: "pointer", bgcolor: "grey.50", "&:hover": { bgcolor: "grey.100" } }}
      >
        <CodeIcon fontSize="small" sx={{ color: "text.secondary" }} />
        <Typography variant="caption" sx={{ fontWeight: 700, flex: 1 }}>
          {label}
        </Typography>
        <ExpandMoreIcon
          fontSize="small"
          sx={{ transform: open ? "rotate(180deg)" : "none", transition: "transform .15s", color: "text.secondary" }}
        />
      </Stack>
      <Collapse in={open} unmountOnExit>
        <Box className="j-body" sx={{ p: 1, maxHeight: 360, overflow: "auto", bgcolor: "#fff" }}>
          {text.split("\n").map((line, i) => (
            <div key={i} className="j-line j-plain">
              {line.length ? line : " "}
            </div>
          ))}
        </Box>
      </Collapse>
    </Box>
  );
}

function FieldGrid({ fields }: { fields: [string, string | undefined][] }) {
  const present = fields.filter(([, v]) => v !== undefined && v !== "");
  if (present.length === 0) return null;
  return (
    <Box
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", md: "repeat(3, 1fr)" },
        gap: 1,
      }}
    >
      {present.map(([k, v]) => (
        <Box key={k} sx={{ minWidth: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, display: "block" }}>
            {k}
          </Typography>
          <Typography variant="body2" sx={{ wordBreak: "break-word" }}>
            {v}
          </Typography>
        </Box>
      ))}
    </Box>
  );
}

/** Compact key/value grid for a CT attributes map. */
function AttrGrid({ attributes }: { attributes: Record<string, unknown> }) {
  const entries = Object.entries(attributes || {});
  if (entries.length === 0) return null;
  return (
    <Box
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", md: "repeat(3, 1fr)" },
        gap: 1,
      }}
    >
      {entries.map(([k, v]) => (
        <Box key={k} sx={{ minWidth: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, display: "block" }}>
            {k}
          </Typography>
          <Typography variant="body2" sx={{ wordBreak: "break-word", fontFamily: "monospace", fontSize: 12 }}>
            {fmt(v)}
          </Typography>
        </Box>
      ))}
    </Box>
  );
}

/** Renders category references as chips, resolving each id to its name via the lookup. */
function CategoryChips({
  ids,
  lookup,
}: {
  ids: string[];
  lookup?: Record<string, CtCategory>;
}) {
  if (!ids || ids.length === 0) return null;
  return (
    <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
      {ids.map((id) => {
        const cat = lookup?.[id];
        const label = cat?.name || cat?.key || cat?.externalId || id;
        const resolved = Boolean(cat?.name || cat?.key);
        return (
          <Tooltip key={id} title={cat?.name ? `${cat.name} · ${id}` : id}>
            <Chip
              size="small"
              label={label}
              variant={resolved ? "filled" : "outlined"}
              sx={{
                fontWeight: 600,
                maxWidth: 260,
                bgcolor: resolved ? "#eef4ff" : undefined,
                color: resolved ? "#2f6bff" : "text.secondary",
                border: resolved ? "1px solid #cddcff" : undefined,
                fontFamily: resolved ? undefined : "monospace",
                fontSize: resolved ? undefined : 11,
              }}
            />
          </Tooltip>
        );
      })}
    </Stack>
  );
}

function SectionHeader({ label, count, color }: { label: string; count: number; color: string }) {
  return (
    <Stack direction="row" alignItems="center" spacing={1}>
      <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: color }} />
      <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
        {label}
      </Typography>
      <Chip size="small" label={count} sx={{ height: 20, fontWeight: 700 }} />
    </Stack>
  );
}

function PublishedChip({ published }: { published: boolean }) {
  return (
    <Chip
      size="small"
      label={published ? "published" : "staged only"}
      sx={{
        fontWeight: 600,
        bgcolor: published ? "#e6f4ec" : "#f3f4f6",
        color: published ? "#1aa564" : "#6b7280",
      }}
    />
  );
}

/** Prominent "read-only" highlight — the tool only reads from CT, never mutates. */
function ReadOnlyBadge({ size = "medium" }: { size?: "small" | "medium" }) {
  return (
    <Chip
      icon={<LockIcon sx={{ fontSize: size === "small" ? 14 : 16 }} />}
      size={size}
      label="Read-only · No writes performed"
      sx={{
        fontWeight: 800,
        letterSpacing: 0.2,
        bgcolor: "#e6f4ec",
        color: "#0f8a52",
        border: "1px solid #b7e3ca",
        "& .MuiChip-icon": { color: "#0f8a52" },
      }}
    />
  );
}

function priceText(s: CtSku): string {
  if (!s.prices || s.prices.length === 0) return "—";
  return s.prices
    .map((p) => `${p.currency ?? ""} ${p.amount}${p.country ? ` (${p.country})` : ""}`.trim())
    .join(", ");
}

function SkuTable({
  skus,
  categoriesById,
}: {
  skus: CtVariant["skus"];
  categoriesById?: Record<string, CtCategory>;
}) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const toggle = (i: number) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(i)) next.delete(i);
      else next.add(i);
      return next;
    });

  return (
    <Table size="small" sx={{ "& td, & th": { borderColor: "divider" } }}>
      <TableHead>
        <TableRow>
          <TableCell />
          <TableCell sx={{ fontWeight: 700 }}>SKU</TableCell>
          <TableCell sx={{ fontWeight: 700 }}>Prices</TableCell>
          <TableCell align="right" sx={{ fontWeight: 700 }}>Categories</TableCell>
          <TableCell align="right" sx={{ fontWeight: 700 }}>Attributes</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {skus.map((s, i) => {
          const open = expanded.has(i);
          const attrCount = Object.keys(s.attributes || {}).length;
          const catCount = s.categories?.length ?? 0;
          return (
            <Fragment key={i}>
              <TableRow hover sx={{ "& > td": { borderBottom: open ? "none" : undefined } }}>
                <TableCell padding="checkbox">
                  <IconButton size="small" onClick={() => toggle(i)}>
                    <ExpandMoreIcon
                      fontSize="small"
                      sx={{ transform: open ? "rotate(180deg)" : "none", transition: "transform .15s" }}
                    />
                  </IconButton>
                </TableCell>
                <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{s.sku ?? "—"}</TableCell>
                <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{priceText(s)}</TableCell>
                <TableCell align="right">{catCount}</TableCell>
                <TableCell align="right">{attrCount}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell colSpan={5} sx={{ py: 0, borderBottom: open ? undefined : "none" }}>
                  <Collapse in={open} unmountOnExit>
                    <Stack spacing={1} sx={{ py: 1.5 }}>
                      {catCount > 0 && (
                        <Box>
                          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, display: "block", mb: 0.5 }}>
                            categories ({catCount})
                          </Typography>
                          <CategoryChips ids={s.categories} lookup={categoriesById} />
                        </Box>
                      )}
                      <AttrGrid attributes={s.attributes} />
                      <JsonBlock label={`SKU · ${s.sku ?? i}`} obj={s.raw} defaultOpen />
                    </Stack>
                  </Collapse>
                </TableCell>
              </TableRow>
            </Fragment>
          );
        })}
      </TableBody>
    </Table>
  );
}

export function CtView() {
  const { toast } = useUi();
  const { environments, activeEnv, setActiveEnv } = useAppState();

  const envNames = useMemo(
    () => (environments.length > 0 ? environments.map((e) => e.name) : ["Dev", "QA", "Perf"]),
    [environments]
  );
  const env = activeEnv || envNames[0];

  const [status, setStatus] = useState<CtStatus | null>(null);
  const [conn, setConn] = useState<ConnState>("checking");
  const [productId, setProductId] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<CtProductResponse | null>(null);

  const checkStatus = useCallback(async () => {
    if (!env) return;
    setConn("checking");
    try {
      const s = await api<CtStatus>("/api/ct/status", { params: { env } });
      setStatus(s);
      setConn(s.connected ? "connected" : "down");
    } catch (e) {
      setStatus({ env, connected: false, error: (e as Error).message });
      setConn("down");
    }
  }, [env]);

  useEffect(() => {
    void checkStatus();
  }, [checkStatus]);

  const clear = () => {
    setProductId("");
    setResult(null);
  };

  const fetchProduct = async () => {
    const pid = productId.trim();
    if (!pid) {
      toast("Enter a productId.", "error");
      return;
    }
    setLoading(true);
    setResult(null);
    try {
      const res = await api<CtProductResponse>("/api/ct/product", { params: { env, productId: pid } });
      setResult(res);
      if (!res.found) {
        toast(res.reason || `No CT product found for "${pid}".`, "info");
      }
    } catch (e) {
      toast((e as Error).message, "error", "CT fetch failed");
    } finally {
      setLoading(false);
    }
  };

  const connInfo = CONN[conn];
  const connTooltip =
    conn === "down" && status?.error
      ? `${connInfo.label} — ${status.error}`
      : status?.scope
      ? `${connInfo.label} · ${status.projectKey ?? ""} · scopes: ${status.scope}`
      : status?.projectKey
      ? `${connInfo.label} · ${status.projectKey}`
      : connInfo.label;

  const counts = result?.counts;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, p: 2, gap: 2 }}>
      <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
        <Stack direction="row" alignItems="flex-start" spacing={2} flexWrap="wrap" useFlexGap>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Stack direction="row" alignItems="center" spacing={1.5}>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>
                Commerce Tools Explorer
              </Typography>
              <Tooltip title={connTooltip}>
                <Stack direction="row" alignItems="center" spacing={0.75} sx={{ cursor: "default" }}>
                  <Box sx={{ position: "relative", width: 14, height: 14, display: "grid", placeItems: "center" }}>
                    {conn === "connected" && (
                      <Box
                        sx={{
                          position: "absolute",
                          inset: 0,
                          borderRadius: "50%",
                          bgcolor: connInfo.color,
                          animation: `${pulse} 1.8s ease-out infinite`,
                        }}
                      />
                    )}
                    <Box
                      sx={{
                        width: 12,
                        height: 12,
                        borderRadius: "50%",
                        bgcolor: connInfo.color,
                        boxShadow: `0 0 8px ${connInfo.color}`,
                        zIndex: 1,
                      }}
                    />
                  </Box>
                  <Typography variant="caption" sx={{ fontWeight: 600, color: connInfo.color }}>
                    {conn === "checking" ? "checking" : conn === "connected" ? "connected" : "not connected"}
                  </Typography>
                </Stack>
              </Tooltip>
              <Tooltip title="Re-check commercetools connection">
                <IconButton size="small" onClick={() => void checkStatus()}>
                  <RefreshIcon fontSize="small" />
                </IconButton>
              </Tooltip>
              <ReadOnlyBadge />
            </Stack>
            <Typography variant="caption" color="text.secondary">
              Fetches a productId from commercetools via GraphQL and assembles the full tree — product,
              its color-variant products, and each variant's SKUs & prices.
            </Typography>
          </Box>

          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <FormControl size="small" sx={{ minWidth: 130 }}>
              <InputLabel>Environment</InputLabel>
              <Select label="Environment" value={env} onChange={(e) => setActiveEnv(String(e.target.value))}>
                {envNames.map((name) => (
                  <MenuItem key={name} value={name}>
                    {name}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <TextField
              size="small"
              label="Product ID"
              value={productId}
              onChange={(e) => setProductId(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") void fetchProduct();
              }}
              sx={{ minWidth: 220 }}
            />
            <Button
              variant="contained"
              startIcon={loading ? <CircularProgress size={16} color="inherit" /> : <SearchIcon />}
              onClick={() => void fetchProduct()}
              disabled={loading}
              sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#d9741a" } }}
            >
              Fetch
            </Button>
            <Button
              variant="outlined"
              color="inherit"
              startIcon={<ClearIcon />}
              onClick={clear}
              disabled={loading || (!productId && !result)}
            >
              Clear
            </Button>
          </Stack>
        </Stack>
        {conn === "down" && (
          <Box sx={{ mt: 1.5, p: 1, borderRadius: 2, bgcolor: "#fdeaea", border: "1px solid #f5c2c0" }}>
            <Typography variant="caption" sx={{ color: "#a12b29" }}>
              {status?.error ||
                "commercetools is not reachable for this environment. Ensure the CT project key and OAuth client credentials are configured."}
            </Typography>
          </Box>
        )}
      </Paper>

      <Box sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
        {loading && (
          <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
            <Stack alignItems="center" spacing={1.5}>
              <CircularProgress sx={{ color: ACCENT }} />
              <Typography variant="body2" color="text.secondary">
                Fetching from commercetools and assembling the product tree…
              </Typography>
            </Stack>
          </Box>
        )}

        {!loading && !result && (
          <Box sx={{ display: "grid", placeItems: "center", py: 8, textAlign: "center" }}>
            <Stack alignItems="center" spacing={1}>
              <Box
                sx={{
                  width: 56,
                  height: 56,
                  borderRadius: 3,
                  display: "grid",
                  placeItems: "center",
                  bgcolor: "#fdf0e2",
                  color: ACCENT,
                }}
              >
                <ShoppingBagIcon fontSize="large" />
              </Box>
              <Typography variant="body2" color="text.secondary">
                Enter a productId and Fetch to explore the commercetools product tree.
              </Typography>
            </Stack>
          </Box>
        )}

        {!loading && result && !result.found && (
          <Paper variant="outlined" sx={{ p: 3, borderRadius: 3, textAlign: "center" }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
              No commercetools product found
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {result.reason || `No product with key "${result.productId}".`}
            </Typography>
          </Paper>
        )}

        {!loading && result && result.found && result.product && (
          <Stack spacing={2}>
            {/* Summary bar */}
            <Paper
              variant="outlined"
              sx={{ p: 2, borderRadius: 3, background: `linear-gradient(180deg, ${ACCENT}0d 0%, transparent 60%)` }}
            >
              <Stack spacing={1.5}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Chip
                    label={result.productId}
                    sx={{ fontWeight: 700, bgcolor: "#fdf0e2", color: ACCENT, fontFamily: "monospace" }}
                  />
                  <Chip size="small" variant="outlined" label={`env: ${result.env}`} sx={{ fontWeight: 600 }} />
                  {result.projectKey && (
                    <Chip size="small" variant="outlined" label={`project: ${result.projectKey}`} />
                  )}
                  <PublishedChip published={result.product.published} />
                  <Box sx={{ flex: 1 }} />
                  <ReadOnlyBadge size="small" />
                </Stack>

                <Divider />

                <Box>
                  <Stack direction="row" alignItems="center" spacing={1}>
                    <CloudDoneIcon fontSize="small" sx={{ color: ACCENT }} />
                    <Typography variant="subtitle2" sx={{ fontWeight: 800 }}>
                      Assembled commercetools tree
                    </Typography>
                  </Stack>
                  <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                    {(
                      [
                        ["Product", "tb-product-type", 1, ACCENT],
                        ["Variants", "tb-variant-sku-type", counts?.variant ?? 0, "#7b5bff"],
                        ["SKUs", "CT variants", counts?.sku ?? 0, "#2f6bff"],
                      ] as [string, string, number, string][]
                    ).map(([k, sub, n, color]) => (
                      <Chip
                        key={k}
                        size="small"
                        label={
                          <span>
                            {k} <span style={{ opacity: 0.7 }}>· {sub}</span>
                            <b style={{ marginLeft: 6 }}>{n}</b>
                          </span>
                        }
                        sx={{ fontWeight: 600, bgcolor: `${color}14`, color, border: `1px solid ${color}33` }}
                      />
                    ))}
                  </Stack>
                </Box>
              </Stack>
            </Paper>

            {/* Product */}
            <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
              <Stack spacing={1.5}>
                <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
                  <SectionHeader label="Product · tb-product-type" count={1} color={ACCENT} />
                  <Box sx={{ flex: 1 }} />
                  <PublishedChip published={result.product.published} />
                </Stack>
                <FieldGrid
                  fields={[
                    ["productId (key)", result.product.key ?? undefined],
                    ["name", result.product.name ?? undefined],
                    ["version", String(result.product.version)],
                    ["id", result.product.id ?? undefined],
                    ["description", result.product.description ?? undefined],
                  ]}
                />
                {result.product.categories?.length > 0 && (
                  <>
                    <Divider textAlign="left">
                      <Typography variant="caption" sx={{ fontWeight: 700 }}>
                        categories ({result.product.categories.length})
                      </Typography>
                    </Divider>
                    <CategoryChips ids={result.product.categories} lookup={result.categoriesById} />
                  </>
                )}
                {Object.keys(result.product.attributes || {}).length > 0 && (
                  <>
                    <Divider textAlign="left">
                      <Typography variant="caption" sx={{ fontWeight: 700 }}>
                        attributes
                      </Typography>
                    </Divider>
                    <AttrGrid attributes={result.product.attributes} />
                  </>
                )}
                <JsonBlock label="Product document" obj={result.product.raw} />
              </Stack>
            </Paper>

            {/* Variants */}
            <Box>
              <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                  Variants
                </Typography>
                <Chip size="small" label={result.variants?.length ?? 0} sx={{ fontWeight: 700 }} />
              </Stack>
              <Stack spacing={1.25}>
                {(result.variants || []).map((v, idx) => {
                  const vId = v.variantId || v.id || `variant-${idx}`;
                  return (
                    <Accordion
                      key={vId}
                      disableGutters
                      defaultExpanded={idx === 0}
                      sx={{ borderRadius: 2, "&:before": { display: "none" }, border: "1px solid", borderColor: "divider" }}
                    >
                      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                        <Stack direction="row" alignItems="center" spacing={1} sx={{ width: "100%" }} flexWrap="wrap" useFlexGap>
                          <Typography sx={{ fontFamily: "monospace", fontWeight: 700 }}>{vId}</Typography>
                          {v.name && <Chip size="small" variant="outlined" label={v.name} />}
                          <PublishedChip published={v.published} />
                          <Box sx={{ flex: 1 }} />
                          <Chip size="small" variant="outlined" label={`${v.skus.length} SKU${v.skus.length === 1 ? "" : "s"}`} />
                        </Stack>
                      </AccordionSummary>
                      <AccordionDetails>
                        <Stack spacing={1.5}>
                          <SectionHeader label="Variant · tb-variant-sku-type" count={1} color="#7b5bff" />
                          <JsonBlock label={`Variant document · ${vId}`} obj={v.raw} />

                          <Divider />
                          <SectionHeader label="SKUs · CT variants" count={v.skus.length} color="#2f6bff" />
                          <SkuTable skus={v.skus} categoriesById={result.categoriesById} />

                          <Divider />
                          <SectionHeader label="Prices" count={v.skus.reduce((n, s) => n + s.prices.length, 0)} color="#12b886" />
                          <Stack spacing={1}>
                            {v.skus.map((s, i) => (
                              <JsonBlock key={i} label={`Prices · ${s.sku ?? i}`} obj={s.prices} />
                            ))}
                          </Stack>
                        </Stack>
                      </AccordionDetails>
                    </Accordion>
                  );
                })}
              </Stack>
            </Box>
          </Stack>
        )}
      </Box>
    </Box>
  );
}

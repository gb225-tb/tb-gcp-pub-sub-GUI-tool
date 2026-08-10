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
import DescriptionOutlinedIcon from "@mui/icons-material/DescriptionOutlined";
import { api } from "../../api/client";
import type { HclDoc, HclProductResponse, HclStatus, HclVariant } from "../../api/types";
import { useAppState } from "../../app/AppState";
import { useUi } from "../../app/UiProvider";

const pulse = keyframes`
  0% { transform: scale(0.9); opacity: 0.75; }
  70% { transform: scale(1.9); opacity: 0; }
  100% { transform: scale(1.9); opacity: 0; }
`;

type BulbState = "checking" | "up" | "down";

const BULB: Record<BulbState, { color: string; label: string }> = {
  checking: { color: "#9aa3b2", label: "Checking VPN / DB2…" },
  up: { color: "#1aa564", label: "VPN up · DB2 reachable" },
  down: { color: "#e0413f", label: "VPN down · DB2 unreachable" },
};

function str(doc: HclDoc | null | undefined, key: string): string | undefined {
  if (!doc) return undefined;
  const v = doc[key];
  if (v === undefined || v === null) return undefined;
  return String(v);
}

function StatusChip({ doc }: { doc: HclDoc | null | undefined }) {
  const s = str(doc, "status");
  if (!s) return null;
  const active = s.toLowerCase() === "active";
  return (
    <Chip
      size="small"
      label={s}
      sx={{
        fontWeight: 600,
        bgcolor: active ? "#e6f4ec" : "#f3f4f6",
        color: active ? "#1aa564" : "#6b7280",
      }}
    />
  );
}

function SourceChip({ doc }: { doc: HclDoc | null | undefined }) {
  const s = str(doc, "source");
  if (!s) return null;
  return <Chip size="small" variant="outlined" label={`source: ${s}`} sx={{ fontWeight: 600 }} />;
}

/** Collapsible, syntax-lite JSON block reusing the Mongo Compare monospace styling. */
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
        sx={{
          px: 1.25,
          py: 0.75,
          cursor: "pointer",
          bgcolor: "grey.50",
          "&:hover": { bgcolor: "grey.100" },
        }}
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

function SectionHeader({ collection, count, color }: { collection?: string; count: number; color: string }) {
  return (
    <Stack direction="row" alignItems="center" spacing={1}>
      <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: color }} />
      <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
        {collection || "—"}
      </Typography>
      <Chip size="small" label={count} sx={{ height: 20, fontWeight: 700 }} />
    </Stack>
  );
}

/** Prominent "read-only" highlight — the tool builds document formats but never writes. */
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

const ACCENT = "#0e9aa7";

function SkuTable({ skus, collections }: { skus: HclVariant["skus"]; collections?: Record<string, string> }) {
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
          <TableCell sx={{ fontWeight: 700 }}>Status</TableCell>
          <TableCell sx={{ fontWeight: 700 }}>Size</TableCell>
          <TableCell align="right" sx={{ fontWeight: 700 }}>List</TableCell>
          <TableCell align="right" sx={{ fontWeight: 700 }}>Sale</TableCell>
          <TableCell align="right" sx={{ fontWeight: 700 }}>Promo</TableCell>
          <TableCell sx={{ fontWeight: 700 }}>UPC</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {skus.map((s, i) => {
          const size = s.sku["size"];
          const sizeText = Array.isArray(size) ? size.join(", ") : size !== undefined ? String(size) : undefined;
          const open = expanded.has(i);
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
                <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{str(s.sku, "sku")}</TableCell>
                <TableCell>
                  <StatusChip doc={s.sku} />
                </TableCell>
                <TableCell>{sizeText || "—"}</TableCell>
                <TableCell align="right">{str(s.price, "listPrice") ?? "—"}</TableCell>
                <TableCell align="right">{str(s.price, "salePrice") ?? "—"}</TableCell>
                <TableCell align="right">{str(s.price, "promoPrice") ?? "—"}</TableCell>
                <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{str(s.sku, "upc") ?? "—"}</TableCell>
              </TableRow>
              <TableRow>
                <TableCell colSpan={8} sx={{ py: 0, borderBottom: open ? undefined : "none" }}>
                  <Collapse in={open} unmountOnExit>
                    <Stack spacing={1} sx={{ py: 1.5 }}>
                      <JsonBlock
                        label={`${collections?.sku ?? "SKU"} · ${str(s.sku, "sku") ?? ""}`}
                        obj={s.sku}
                        defaultOpen
                      />
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

export function HclView() {
  const { toast } = useUi();
  const { environments, activeEnv, setActiveEnv } = useAppState();

  const envNames = useMemo(
    () => (environments.length > 0 ? environments.map((e) => e.name) : ["Dev", "QA", "Perf"]),
    [environments]
  );
  const env = activeEnv || envNames[0];

  const [status, setStatus] = useState<HclStatus | null>(null);
  const [bulb, setBulb] = useState<BulbState>("checking");
  const [partNumber, setPartNumber] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<HclProductResponse | null>(null);

  const checkStatus = useCallback(async () => {
    if (!env) return;
    setBulb("checking");
    try {
      const s = await api<HclStatus>("/api/hcl/status", { params: { env } });
      setStatus(s);
      setBulb(s.up ? "up" : "down");
    } catch (e) {
      setStatus({ env, up: false, host: "", error: (e as Error).message });
      setBulb("down");
    }
  }, [env]);

  useEffect(() => {
    void checkStatus();
  }, [checkStatus]);

  const clear = () => {
    setPartNumber("");
    setResult(null);
  };

  const fetchProduct = async () => {
    const pn = partNumber.trim();
    if (!pn) {
      toast("Enter a product part number.", "error");
      return;
    }
    setLoading(true);
    setResult(null);
    try {
      const res = await api<HclProductResponse>("/api/hcl/product", { params: { env, productId: pn } });
      setResult(res);
      if (!res.found) {
        toast(res.reason || `No HCL product found for "${pn}".`, "info");
      }
    } catch (e) {
      toast((e as Error).message, "error", "HCL fetch failed");
    } finally {
      setLoading(false);
    }
  };

  const bulbInfo = BULB[bulb];
  const bulbTooltip =
    bulb === "down" && status?.error
      ? `${bulbInfo.label} — ${status.error}`
      : status?.host
      ? `${bulbInfo.label} · ${status.host}`
      : bulbInfo.label;

  const counts = result?.counts;
  const collections = result?.collections;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, p: 2, gap: 2 }}>
      <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
        <Stack direction="row" alignItems="flex-start" spacing={2} flexWrap="wrap" useFlexGap>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Stack direction="row" alignItems="center" spacing={1.5}>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>
                HCL Data Explorer
              </Typography>
              <Tooltip title={bulbTooltip}>
                <Stack direction="row" alignItems="center" spacing={0.75} sx={{ cursor: "default" }}>
                  <Box sx={{ position: "relative", width: 14, height: 14, display: "grid", placeItems: "center" }}>
                    {bulb === "up" && (
                      <Box
                        sx={{
                          position: "absolute",
                          inset: 0,
                          borderRadius: "50%",
                          bgcolor: bulbInfo.color,
                          animation: `${pulse} 1.8s ease-out infinite`,
                        }}
                      />
                    )}
                    <Box
                      sx={{
                        width: 12,
                        height: 12,
                        borderRadius: "50%",
                        bgcolor: bulbInfo.color,
                        boxShadow: `0 0 8px ${bulbInfo.color}`,
                        zIndex: 1,
                      }}
                    />
                  </Box>
                  <Typography variant="caption" sx={{ fontWeight: 600, color: bulbInfo.color }}>
                    {bulb === "checking" ? "checking" : bulb === "up" ? "VPN up" : "VPN down"}
                  </Typography>
                </Stack>
              </Tooltip>
              <Tooltip title="Re-check VPN / DB2">
                <IconButton size="small" onClick={() => void checkStatus()}>
                  <RefreshIcon fontSize="small" />
                </IconButton>
              </Tooltip>
              <ReadOnlyBadge />
            </Stack>
            <Typography variant="caption" color="text.secondary">
              Reads the HCL Commerce DB2 subtree for a part number and builds the exact Config-Catalog
              document formats. DB2 requires VPN.
            </Typography>
          </Box>

          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <FormControl size="small" sx={{ minWidth: 130 }}>
              <InputLabel>Environment</InputLabel>
              <Select
                label="Environment"
                value={env}
                onChange={(e) => setActiveEnv(String(e.target.value))}
              >
                {envNames.map((name) => (
                  <MenuItem key={name} value={name}>
                    {name}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <TextField
              size="small"
              label="Product part number"
              value={partNumber}
              onChange={(e) => setPartNumber(e.target.value)}
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
              sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#0b8290" } }}
            >
              Fetch
            </Button>
            <Button
              variant="outlined"
              color="inherit"
              startIcon={<ClearIcon />}
              onClick={clear}
              disabled={loading || (!partNumber && !result)}
            >
              Clear
            </Button>
          </Stack>
        </Stack>
        {bulb === "down" && (
          <Box sx={{ mt: 1.5, p: 1, borderRadius: 2, bgcolor: "#fdeaea", border: "1px solid #f5c2c0" }}>
            <Typography variant="caption" sx={{ color: "#a12b29" }}>
              DB2 is unreachable{status?.host ? ` at ${status.host}` : ""} — connect to the VPN. Fetches will
              fail until the bulb turns green.
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
                Reading HCL DB2 and building documents…
              </Typography>
            </Stack>
          </Box>
        )}

        {!loading && !result && (
          <Box sx={{ display: "grid", placeItems: "center", py: 8, textAlign: "center" }}>
            <Stack alignItems="center" spacing={1}>
              <StorageEmptyIcon />
              <Typography variant="body2" color="text.secondary">
                Enter a product part number and Fetch to preview the HCL migration output.
              </Typography>
            </Stack>
          </Box>
        )}

        {!loading && result && !result.found && (
          <Paper variant="outlined" sx={{ p: 3, borderRadius: 3, textAlign: "center" }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
              No HCL product found
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {result.reason || `No ProductBean matched "${result.productId}".`}
            </Typography>
          </Paper>
        )}

        {!loading && result && result.found && (
          <Stack spacing={2}>
            {/* Summary bar */}
            <Paper
              variant="outlined"
              sx={{
                p: 2,
                borderRadius: 3,
                background: `linear-gradient(180deg, ${ACCENT}0d 0%, transparent 60%)`,
              }}
            >
              <Stack spacing={1.5}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Chip
                    label={`${result.productId}`}
                    sx={{ fontWeight: 700, bgcolor: "#e6f7f8", color: ACCENT, fontFamily: "monospace" }}
                  />
                  <Chip size="small" variant="outlined" label={`env: ${result.env}`} sx={{ fontWeight: 600 }} />
                  {result.catEntryId !== undefined && (
                    <Chip size="small" variant="outlined" label={`CATENTRY_ID: ${result.catEntryId}`} />
                  )}
                  <Box sx={{ flex: 1 }} />
                  <ReadOnlyBadge size="small" />
                </Stack>

                <Divider />

                <Box>
                  <Stack direction="row" alignItems="center" spacing={1}>
                    <DescriptionOutlinedIcon fontSize="small" sx={{ color: ACCENT }} />
                    <Typography variant="subtitle2" sx={{ fontWeight: 800 }}>
                      These are the document formats in Catalog
                    </Typography>
                  </Stack>
                  <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                    {counts &&
                      (
                        [
                          ["product", collections?.product, ACCENT],
                          ["rating", collections?.rating, "#f59e0b"],
                          ["variant", collections?.variant, ACCENT],
                          ["enrichedProduct", collections?.enrichedProduct, "#7b5bff"],
                          ["sku", collections?.sku, "#2f6bff"],
                          ["price", collections?.price, "#2f6bff"],
                          ["item", collections?.item, "#2f6bff"],
                        ] as [string, string | undefined, string][]
                      ).map(([k, coll, color]) => (
                        <Chip
                          key={k}
                          size="small"
                          label={
                            <span>
                              {coll || k}
                              <b style={{ marginLeft: 6 }}>{counts[k] ?? 0}</b>
                            </span>
                          }
                          sx={{
                            fontWeight: 600,
                            bgcolor: `${color}14`,
                            color,
                            border: `1px solid ${color}33`,
                          }}
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
                  <SectionHeader collection={collections?.product} count={counts?.product ?? 1} color={ACCENT} />
                  <Box sx={{ flex: 1 }} />
                  <StatusChip doc={result.product} />
                  <SourceChip doc={result.product} />
                </Stack>
                <FieldGrid
                  fields={[
                    ["productId", str(result.product, "productId")],
                    ["productName", str(result.product, "productName")],
                    ["banner", str(result.product, "banner")],
                    ["division", str(result.product, "division")],
                    ["divisionDescription", str(result.product, "divisionDescription")],
                    ["seoUrl", str(result.product, "seoUrl")],
                    ["startDate", str(result.product, "startDate")],
                    ["publishedAt", str(result.product, "publishedAt")],
                    ["endDate", str(result.product, "endDate")],
                  ]}
                />
                <JsonBlock label={`${collections?.product ?? "Product"} document`} obj={result.product} />
              </Stack>
            </Paper>

            {/* Rating (optional) */}
            {result.rating && (
              <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
                <Stack spacing={1.5}>
                  <SectionHeader collection={collections?.rating} count={counts?.rating ?? 1} color="#f59e0b" />
                  <FieldGrid
                    fields={[
                      ["averageRating", str(result.rating, "averageRating")],
                      ["averageRoundedRating", str(result.rating, "averageRoundedRating")],
                      ["reviewCount", str(result.rating, "reviewCount")],
                      ["ratingAndReview", str(result.rating, "ratingAndReview")],
                    ]}
                  />
                  <JsonBlock label={`${collections?.rating ?? "Rating"} document`} obj={result.rating} />
                </Stack>
              </Paper>
            )}

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
                  const vId = str(v.variant, "variantId") || str(v.variant, "_id") || `variant-${idx}`;
                  return (
                    <Accordion key={vId} disableGutters defaultExpanded={idx === 0} sx={{ borderRadius: 2, "&:before": { display: "none" }, border: "1px solid", borderColor: "divider" }}>
                      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                        <Stack direction="row" alignItems="center" spacing={1} sx={{ width: "100%" }} flexWrap="wrap" useFlexGap>
                          <Typography sx={{ fontFamily: "monospace", fontWeight: 700 }}>{vId}</Typography>
                          {str(v.variant, "color") && (
                            <Chip size="small" variant="outlined" label={str(v.variant, "color")} />
                          )}
                          <StatusChip doc={v.variant} />
                          <SourceChip doc={v.variant} />
                          <Chip
                            size="small"
                            label={v.enrichedPublishReady ? "publish-ready" : "not publish-ready"}
                            sx={{
                              fontWeight: 600,
                              bgcolor: v.enrichedPublishReady ? "#e6f4ec" : "#f3f4f6",
                              color: v.enrichedPublishReady ? "#1aa564" : "#6b7280",
                            }}
                          />
                          <Box sx={{ flex: 1 }} />
                          <Chip size="small" variant="outlined" label={`${v.skus.length} SKU${v.skus.length === 1 ? "" : "s"}`} />
                        </Stack>
                      </AccordionSummary>
                      <AccordionDetails>
                        <Stack spacing={1.5}>
                          <SectionHeader collection={collections?.variant} count={1} color={ACCENT} />
                          <JsonBlock label={`${collections?.variant ?? "Variant"} document`} obj={v.variant} />

                          <Divider />
                          <SectionHeader
                            collection={collections?.enrichedProduct}
                            count={v.enrichedPublishReady ? 1 : 0}
                            color="#7b5bff"
                          />
                          {v.enrichedProduct ? (
                            <JsonBlock
                              label={`${collections?.enrichedProduct ?? "EnrichedProduct"} document`}
                              obj={v.enrichedProduct}
                            />
                          ) : (
                            <Typography variant="caption" color="text.secondary">
                              Not publish-ready (missing productName / productDescription / image) — this
                              variant's EnrichedProduct would be skipped by the migration.
                            </Typography>
                          )}

                          <Divider />
                          <SectionHeader collection={collections?.sku} count={v.skus.length} color="#2f6bff" />
                          <SkuTable skus={v.skus} collections={collections} />

                          <Divider />
                          <SectionHeader collection={collections?.price} count={v.skus.length} color="#12b886" />
                          <Stack spacing={1}>
                            {v.skus.map((s, i) => (
                              <JsonBlock
                                key={i}
                                label={`${collections?.price ?? "Price"} · ${str(s.price, "sku") ?? str(s.sku, "sku") ?? i}`}
                                obj={s.price}
                              />
                            ))}
                          </Stack>

                          <Divider />
                          <SectionHeader collection={collections?.item} count={v.skus.length} color="#e0413f" />
                          <Stack spacing={1}>
                            {v.skus.map((s, i) => (
                              <JsonBlock
                                key={i}
                                label={`${collections?.item ?? "Item"} · ${str(s.item, "sku") ?? str(s.sku, "sku") ?? i}`}
                                obj={s.item}
                              />
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

function StorageEmptyIcon() {
  return (
    <Box
      sx={{
        width: 56,
        height: 56,
        borderRadius: 3,
        display: "grid",
        placeItems: "center",
        bgcolor: "#e6f7f8",
        color: ACCENT,
      }}
    >
      <CodeIcon fontSize="large" />
    </Box>
  );
}

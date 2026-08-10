import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Tab from "@mui/material/Tab";
import Tabs from "@mui/material/Tabs";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import SearchIcon from "@mui/icons-material/Search";
import ClearIcon from "@mui/icons-material/Clear";
import DeleteSweepIcon from "@mui/icons-material/DeleteSweep";
import StorageIcon from "@mui/icons-material/Storage";
import ShoppingBagIcon from "@mui/icons-material/ShoppingBag";
import { api } from "../../api/client";
import type {
  CleanupDeleteResponse,
  CleanupScanResponse,
  CtCleanupDeleteResponse,
  CtCleanupScanResponse,
  MongoConfig,
} from "../../api/types";
import { useUi } from "../../app/UiProvider";

interface CollState {
  name: string;
  count: number | null;
  checked: boolean;
}
interface GroupState {
  label: string;
  database: string;
  collections: CollState[];
}

export function CleanupView() {
  const { withBusy, toast, confirm } = useUi();
  const [config, setConfig] = useState<MongoConfig | null>(null);
  const [tab, setTab] = useState<"mongo" | "ct">("mongo");
  const [env, setEnv] = useState("");
  const [productId, setProductId] = useState("");

  // ── MongoDB cleanup state ──
  const [groups, setGroups] = useState<GroupState[]>([]);
  const [scanBadge, setScanBadge] = useState<{ text: string; kind: "ok" | "warn" } | null>(null);
  const [deleteBadges, setDeleteBadges] = useState<{ total: number; perColl: { collection: string; deleted: number }[] } | null>(
    null
  );

  // ── CommerceTools cleanup state ──
  const [ctScan, setCtScan] = useState<CtCleanupScanResponse | null>(null);
  const [ctDeleteProduct, setCtDeleteProduct] = useState(true);
  const [ctVariantSel, setCtVariantSel] = useState<Set<string>>(new Set());
  const [ctResult, setCtResult] = useState<CtCleanupDeleteResponse | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const cfg = await api<MongoConfig>("/api/mongo/config");
        setConfig(cfg);
        setGroups(
          (cfg.productCleanup || []).map((g) => ({
            label: g.label,
            database: g.database,
            collections: g.collections.map((c) => ({ name: c, count: null, checked: false })),
          }))
        );
      } catch (e) {
        toast((e as Error).message, "error", "Failed to load Mongo config");
      }
    })();
  }, [toast]);

  const targets = useMemo(
    () =>
      groups.flatMap((g) => g.collections.filter((c) => c.checked).map((c) => ({ database: g.database, collection: c.name }))),
    [groups]
  );

  const setColl = (dbName: string, collName: string, patch: Partial<CollState>) =>
    setGroups((prev) =>
      prev.map((g) =>
        g.database === dbName
          ? { ...g, collections: g.collections.map((c) => (c.name === collName ? { ...c, ...patch } : c)) }
          : g
      )
    );

  const setGroupChecked = (dbName: string, checked: boolean) =>
    setGroups((prev) =>
      prev.map((g) =>
        g.database === dbName ? { ...g, collections: g.collections.map((c) => ({ ...c, checked })) } : g
      )
    );

  const requireInputs = (): string | null => {
    if (!env) {
      toast("Select an environment.", "error");
      return null;
    }
    const pid = productId.trim();
    if (!pid) {
      toast("Enter a productId.", "error");
      return null;
    }
    return pid;
  };

  const scanMongo = (pid: string) =>
    withBusy("Scanning collections…", async () => {
      setDeleteBadges(null);
      try {
        const res = await api<CleanupScanResponse>("/api/mongo/cleanup/scan", { params: { env, productId: pid } });
        const byKey: Record<string, number> = {};
        (res.groups || []).forEach((g) => g.collections.forEach((c) => (byKey[`${g.database}::${c.name}`] = c.count)));
        setGroups((prev) =>
          prev.map((g) => ({
            ...g,
            collections: g.collections.map((c) => {
              const n = byKey[`${g.database}::${c.name}`] ?? 0;
              return { ...c, count: n, checked: n > 0 };
            }),
          }))
        );
        setScanBadge(
          (res.total || 0) === 0
            ? { text: "productId not found in any collection", kind: "warn" }
            : { text: `${res.total} document(s) found`, kind: "ok" }
        );
      } catch (e) {
        toast((e as Error).message, "error", "Scan failed");
      }
    });

  const scanCt = (pid: string) =>
    withBusy("Scanning commercetools…", async () => {
      setCtResult(null);
      try {
        const res = await api<CtCleanupScanResponse>("/api/ct/cleanup/scan", { params: { env, productId: pid } });
        setCtScan(res);
        setCtDeleteProduct(res.found);
        setCtVariantSel(new Set((res.variants || []).map((v) => v.id)));
      } catch (e) {
        setCtScan(null);
        toast((e as Error).message, "error", "CT scan failed");
      }
    });

  const scan = () => {
    const pid = requireInputs();
    if (!pid) return;
    if (tab === "mongo") void scanMongo(pid);
    else void scanCt(pid);
  };

  const clear = () => {
    setProductId("");
    if (tab === "mongo") {
      setScanBadge(null);
      setDeleteBadges(null);
      setGroups((prev) => prev.map((g) => ({ ...g, collections: g.collections.map((c) => ({ ...c, count: null, checked: false })) })));
    } else {
      setCtScan(null);
      setCtResult(null);
      setCtVariantSel(new Set());
      setCtDeleteProduct(true);
    }
  };

  const delMongo = () => {
    const pid = productId.trim();
    if (!env || !pid) {
      toast("Select an environment and productId.", "error");
      return;
    }
    if (!targets.length) {
      toast("Select at least one collection.", "error");
      return;
    }
    confirm({
      title: "Delete product data",
      danger: true,
      confirmLabel: `Delete from ${targets.length} collection(s)`,
      busyMessage: "Deleting product from collections…",
      body: (
        <Box>
          <Typography variant="body2" color="text.secondary">
            Permanently delete ALL documents with productId "{pid}" in {env} from these collections? This cannot
            be undone.
          </Typography>
          <Box component="ul" sx={{ mt: 1, pl: 2.5 }}>
            {targets.map((t) => (
              <li key={`${t.database}/${t.collection}`}>
                {t.database} / {t.collection}
              </li>
            ))}
          </Box>
        </Box>
      ),
      onConfirm: async () => {
        const res = await api<CleanupDeleteResponse>("/api/mongo/cleanup/delete", {
          method: "POST",
          body: { env, productId: pid, targets },
        });
        setDeleteBadges({
          total: res.totalDeleted || 0,
          perColl: (res.results || []).filter((r) => r.deleted > 0),
        });
        (res.results || []).forEach((r) => {
          if (r.deleted > 0) setColl(r.database, r.collection, { count: 0, checked: false });
        });
        const t = res.totalDeleted || 0;
        toast(`Deleted ${t} document(s) for productId "${pid}".`, t > 0 ? "success" : "info");
      },
    });
  };

  const ctSelectedCount = (ctDeleteProduct ? 1 : 0) + ctVariantSel.size;

  const toggleVariant = (id: string, checked: boolean) =>
    setCtVariantSel((prev) => {
      const next = new Set(prev);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });

  const delCt = () => {
    const pid = productId.trim();
    if (!env || !pid) {
      toast("Select an environment and productId.", "error");
      return;
    }
    if (!ctScan?.found) {
      toast("Scan a productId first.", "error");
      return;
    }
    if (ctSelectedCount === 0) {
      toast("Select the product and/or at least one variant.", "error");
      return;
    }
    const variantIds = Array.from(ctVariantSel);
    const variantLabels = (ctScan.variants || [])
      .filter((v) => ctVariantSel.has(v.id))
      .map((v) => v.variantId);
    confirm({
      title: "Delete commercetools product tree",
      danger: true,
      confirmLabel: `Delete ${ctSelectedCount} product(s)`,
      busyMessage: "Deleting from commercetools…",
      body: (
        <Box>
          <Typography variant="body2" color="text.secondary">
            Permanently delete the following from commercetools in {env} for productId "{pid}"? Published products
            are unpublished first. This cannot be undone.
          </Typography>
          <Box component="ul" sx={{ mt: 1, pl: 2.5 }}>
            {ctDeleteProduct && ctScan.product && (
              <li>
                Product <b>{ctScan.product.key}</b> (tb-product-type)
              </li>
            )}
            {variantLabels.map((v) => (
              <li key={v}>Variant {v} (tb-variant-sku-type)</li>
            ))}
          </Box>
        </Box>
      ),
      onConfirm: async () => {
        const res = await api<CtCleanupDeleteResponse>("/api/ct/cleanup/delete", {
          method: "POST",
          body: { env, productId: pid, deleteProduct: ctDeleteProduct, variantIds },
        });
        setCtResult(res);
        const t = res.totalDeleted || 0;
        toast(`Deleted ${t} commercetools product(s) for "${pid}".`, t > 0 ? "success" : "info");
        // Re-scan to reflect the new state.
        void scanCt(pid);
      },
    });
  };

  if (!config) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", height: "100%" }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ height: "100%", minHeight: 0, overflow: "auto", p: 2 }}>
      <Stack spacing={2} sx={{ maxWidth: 1100, mx: "auto" }}>
        <Typography variant="h6">Product Clean Up</Typography>

        <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
          <Tabs
            value={tab}
            onChange={(_, v) => setTab(v)}
            sx={{ px: 1, borderBottom: "1px solid", borderColor: "divider" }}
          >
            <Tab value="mongo" icon={<StorageIcon fontSize="small" />} iconPosition="start" label="MongoDB" />
            <Tab value="ct" icon={<ShoppingBagIcon fontSize="small" />} iconPosition="start" label="CommerceTools" />
          </Tabs>
          <Box sx={{ p: 2 }}>
            <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
              <FormControl sx={{ minWidth: 160 }}>
                <InputLabel>Environment</InputLabel>
                <Select label="Environment" value={env} onChange={(e) => setEnv(String(e.target.value))}>
                  {config.environments.map((e) => (
                    <MenuItem key={e.name} value={e.name}>
                      {e.name}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
              <TextField
                label="productId"
                value={productId}
                onChange={(e) => setProductId(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") scan();
                }}
                sx={{ flex: 1, minWidth: 200 }}
              />
              <Button variant="contained" startIcon={<SearchIcon />} onClick={scan}>
                Scan
              </Button>
              <Button variant="outlined" color="inherit" startIcon={<ClearIcon />} onClick={clear}>
                Clear
              </Button>
              {tab === "mongo" && scanBadge && (
                <Chip
                  label={scanBadge.text}
                  color={scanBadge.kind === "warn" ? "warning" : "primary"}
                  variant={scanBadge.kind === "warn" ? "outlined" : "filled"}
                />
              )}
              {tab === "ct" && ctScan && (
                <Chip
                  label={
                    ctScan.found
                      ? `${ctScan.counts?.variant ?? 0} variant(s), ${ctScan.counts?.sku ?? 0} SKU(s)`
                      : "productId not found in commercetools"
                  }
                  color={ctScan.found ? "primary" : "warning"}
                  variant={ctScan.found ? "filled" : "outlined"}
                />
              )}
            </Stack>
          </Box>
        </Paper>

        {tab === "mongo" && (
          <>
            <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2 }}>
              {groups.map((g) => {
                const allChecked = g.collections.length > 0 && g.collections.every((c) => c.checked);
                return (
                  <Paper key={g.database} variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
                    <Stack
                      direction="row"
                      alignItems="center"
                      justifyContent="space-between"
                      sx={{ px: 2, py: 1, borderBottom: "1px solid", borderColor: "divider", bgcolor: "action.hover" }}
                    >
                      <FormControlLabel
                        control={
                          <Checkbox checked={allChecked} onChange={(e) => setGroupChecked(g.database, e.target.checked)} />
                        }
                        label={<Typography variant="subtitle2">{g.label}</Typography>}
                      />
                      <Chip label={g.database} size="small" variant="outlined" className="mono" />
                    </Stack>
                    <Box sx={{ p: 1 }}>
                      {g.collections.map((c) => (
                        <Stack
                          key={c.name}
                          direction="row"
                          alignItems="center"
                          spacing={1}
                          sx={{ px: 1, py: 0.5, borderRadius: 1.5, "&:hover": { bgcolor: "action.hover" } }}
                        >
                          <Checkbox
                            checked={c.checked}
                            onChange={(e) => setColl(g.database, c.name, { checked: e.target.checked })}
                          />
                          <Typography variant="body2" sx={{ flex: 1 }}>
                            {c.name}
                          </Typography>
                          <Chip
                            label={c.count === null ? "—" : String(c.count)}
                            size="small"
                            variant={c.count && c.count > 0 ? "filled" : "outlined"}
                            color={c.count && c.count > 0 ? "primary" : "default"}
                          />
                        </Stack>
                      ))}
                    </Box>
                  </Paper>
                );
              })}
            </Box>

            <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
              <Button
                variant="contained"
                color="error"
                startIcon={<DeleteSweepIcon />}
                disabled={targets.length === 0}
                onClick={delMongo}
              >
                Delete selected
              </Button>
              {deleteBadges && (
                <>
                  <Chip color="success" label={`${deleteBadges.total} deleted`} />
                  {deleteBadges.perColl.map((r) => (
                    <Chip key={r.collection} label={`${r.collection}: ${r.deleted}`} variant="outlined" />
                  ))}
                </>
              )}
            </Stack>
          </>
        )}

        {tab === "ct" && (
          <>
            {ctScan?.found && ctScan.product ? (
              <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
                <Stack
                  direction="row"
                  alignItems="center"
                  justifyContent="space-between"
                  sx={{ px: 2, py: 1, borderBottom: "1px solid", borderColor: "divider", bgcolor: "action.hover" }}
                >
                  <FormControlLabel
                    control={
                      <Checkbox checked={ctDeleteProduct} onChange={(e) => setCtDeleteProduct(e.target.checked)} />
                    }
                    label={
                      <Stack direction="row" spacing={1} alignItems="center">
                        <Typography variant="subtitle2">Product</Typography>
                        <Chip size="small" variant="outlined" className="mono" label={ctScan.product.key} />
                        <Chip
                          size="small"
                          label={ctScan.product.published ? "published" : "staged only"}
                          color={ctScan.product.published ? "success" : "default"}
                          variant={ctScan.product.published ? "filled" : "outlined"}
                        />
                      </Stack>
                    }
                  />
                  <Chip label="tb-product-type" size="small" variant="outlined" className="mono" />
                </Stack>

                <Box sx={{ px: 2, py: 1 }}>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                    <Typography variant="subtitle2">Color variants</Typography>
                    <Chip size="small" label={ctScan.variants?.length ?? 0} />
                    <Box sx={{ flex: 1 }} />
                    <Chip label="tb-variant-sku-type" size="small" variant="outlined" className="mono" />
                  </Stack>
                  <Divider sx={{ mb: 0.5 }} />
                  {(ctScan.variants || []).length === 0 && (
                    <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
                      No color-variant products referenced.
                    </Typography>
                  )}
                  {(ctScan.variants || []).map((v) => (
                    <Stack
                      key={v.id}
                      direction="row"
                      alignItems="center"
                      spacing={1}
                      sx={{ px: 1, py: 0.5, borderRadius: 1.5, "&:hover": { bgcolor: "action.hover" } }}
                    >
                      <Checkbox checked={ctVariantSel.has(v.id)} onChange={(e) => toggleVariant(v.id, e.target.checked)} />
                      <Typography variant="body2" className="mono" sx={{ flex: 1 }}>
                        {v.variantId}
                      </Typography>
                      {v.published && <Chip size="small" color="success" label="published" />}
                      <Chip size="small" variant="outlined" label={`${v.skuCount} SKU${v.skuCount === 1 ? "" : "s"}`} />
                    </Stack>
                  ))}
                </Box>
              </Paper>
            ) : (
              <Paper variant="outlined" sx={{ p: 3, borderRadius: 3, textAlign: "center" }}>
                <Typography variant="body2" color="text.secondary">
                  {ctScan && !ctScan.found
                    ? ctScan.reason || "productId not found in commercetools."
                    : "Enter a productId and Scan to see the commercetools product tree."}
                </Typography>
              </Paper>
            )}

            <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
              <Button
                variant="contained"
                color="error"
                startIcon={<DeleteSweepIcon />}
                disabled={!ctScan?.found || ctSelectedCount === 0}
                onClick={delCt}
              >
                Delete selected
              </Button>
              {ctResult && (
                <>
                  <Chip color="success" label={`${ctResult.totalDeleted} deleted`} />
                  {(ctResult.results || [])
                    .filter((r) => !r.deleted)
                    .map((r) => (
                      <Chip key={r.id} color="error" variant="outlined" label={`${r.label}: failed`} />
                    ))}
                </>
              )}
            </Stack>
          </>
        )}
      </Stack>
    </Box>
  );
}

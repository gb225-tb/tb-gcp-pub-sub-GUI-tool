import { useCallback, useEffect, useMemo, useState } from "react";
import { keyframes } from "@mui/system";
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
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import RefreshIcon from "@mui/icons-material/Refresh";
import SearchIcon from "@mui/icons-material/Search";
import ClearIcon from "@mui/icons-material/Clear";
import CheckIcon from "@mui/icons-material/Check";
import CodeIcon from "@mui/icons-material/Code";
import StorageIcon from "@mui/icons-material/Storage";
import ShoppingBagIcon from "@mui/icons-material/ShoppingBag";
import TravelExploreIcon from "@mui/icons-material/TravelExplore";
import CategoryIcon from "@mui/icons-material/Category";
import Inventory2Icon from "@mui/icons-material/Inventory2";
import HubIcon from "@mui/icons-material/Hub";
import { api } from "../../api/client";
import type {
  CategoryCatalogBlock,
  CategoryCatalogResponse,
  CategoryConstructorResponse,
  CategoryHclResponse,
  CategoryReconcileResponse,
  CategoryReconcileRow,
  MongoDocument,
} from "../../api/types";
import { useAppState } from "../../app/AppState";
import { useUi } from "../../app/UiProvider";

const ACCENT = "#c026d3";

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

/** Async slot: undefined = not started, null = loading. */
interface Slot<T> {
  loading: boolean;
  error?: string;
  data?: T;
}

function emptySlot<T>(): Slot<T> {
  return { loading: false };
}

function JsonBlock({ label, text, defaultOpen = false }: { label: string; text: string; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
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
        <Typography variant="caption" sx={{ fontWeight: 700, flex: 1, wordBreak: "break-all" }}>
          {label}
        </Typography>
        <ExpandMoreIcon
          fontSize="small"
          sx={{ transform: open ? "rotate(180deg)" : "none", transition: "transform .15s", color: "text.secondary" }}
        />
      </Stack>
      <Collapse in={open} unmountOnExit>
        <Box sx={{ p: 1, maxHeight: 320, overflow: "auto", bgcolor: "#fff", fontFamily: "monospace", fontSize: 12 }}>
          {text.split("\n").map((line, i) => (
            <div key={i} style={{ whiteSpace: "pre" }}>
              {line.length ? line : " "}
            </div>
          ))}
        </Box>
      </Collapse>
    </Box>
  );
}

function BigCount({ value, label, color }: { value: number | undefined; label: string; color: string }) {
  return (
    <Stack alignItems="flex-start" spacing={0}>
      <Typography sx={{ fontSize: 40, fontWeight: 800, lineHeight: 1, color }}>{value ?? "—"}</Typography>
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, textTransform: "uppercase", letterSpacing: 0.5 }}>
        {label}
      </Typography>
    </Stack>
  );
}

/** A source panel with a coloured header, a status/count area and a collapsible "view data" body. */
function SourcePanel({
  icon,
  title,
  subtitle,
  color,
  slot,
  onReload,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  subtitle: string;
  color: string;
  slot: Slot<unknown>;
  onReload?: () => void;
  children?: React.ReactNode;
}) {
  return (
    <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden", display: "flex", flexDirection: "column" }}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={1}
        sx={{ px: 2, py: 1.25, bgcolor: `${color}14`, borderBottom: "1px solid", borderColor: "divider" }}
      >
        <Box sx={{ width: 30, height: 30, borderRadius: 2, display: "grid", placeItems: "center", bgcolor: color, color: "#fff" }}>
          {icon}
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 800, color }}>
            {title}
          </Typography>
          <Typography variant="caption" color="text.secondary" noWrap>
            {subtitle}
          </Typography>
        </Box>
        {slot.loading && <CircularProgress size={16} sx={{ color }} />}
        {onReload && !slot.loading && (
          <Tooltip title="Reload this source">
            <IconButton size="small" onClick={onReload}>
              <RefreshIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
      </Stack>
      <Box sx={{ p: 2, flex: 1, minWidth: 0 }}>
        {slot.error ? (
          <Box sx={{ p: 1.25, borderRadius: 2, bgcolor: "#fdeaea", border: "1px solid #f5c2c0" }}>
            <Typography variant="caption" sx={{ color: "#a12b29" }}>
              {slot.error}
            </Typography>
          </Box>
        ) : (
          children
        )}
      </Box>
    </Paper>
  );
}

function DocList({ label, docs }: { label: string; docs: MongoDocument[] | undefined }) {
  if (!docs || docs.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary">
        No {label.toLowerCase()} to show.
      </Typography>
    );
  }
  return (
    <Stack spacing={1}>
      {docs.map((d, i) => (
        <JsonBlock key={i} label={`${label} · ${d.id}`} text={d.json} defaultOpen={i === 0 && docs.length === 1} />
      ))}
    </Stack>
  );
}

function CatalogDbBlock({ block, color }: { block: CategoryCatalogBlock; color: string }) {
  const [showData, setShowData] = useState(false);
  if (!block.available) {
    return (
      <Box>
        <Typography variant="subtitle2" sx={{ fontWeight: 700, textTransform: "capitalize" }}>
          {block.database}
        </Typography>
        <Typography variant="caption" sx={{ color: "#a12b29" }}>
          {block.error || "Unavailable."}
        </Typography>
      </Box>
    );
  }
  const c = block.counts;
  return (
    <Box>
      <Stack direction="row" alignItems="baseline" spacing={1} flexWrap="wrap" useFlexGap>
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
          {block.database}
        </Typography>
        {block.category?.name && <Chip size="small" variant="outlined" label={block.category.name} />}
        {block.categoryFound === false && (
          <Chip size="small" label="category not found" sx={{ bgcolor: "#fff3e0", color: "#b45309", fontWeight: 600 }} />
        )}
      </Stack>
      <Stack direction="row" spacing={3} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
        <BigCount value={c?.activeProducts} label="active products" color={color} />
        <BigCount value={c?.activeVariants} label="active variants" color="#7b5bff" />
        <BigCount value={c?.totalActive} label="active total" color="#2f6bff" />
      </Stack>
      {c && (
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5 }}>
          {c.total} association{c.total === 1 ? "" : "s"} in all statuses
          {block.category?.id ? ` · categoryId ${block.category.id}` : ""}
        </Typography>
      )}
      <Button
        size="small"
        variant="text"
        onClick={() => setShowData((s) => !s)}
        sx={{ mt: 1, color }}
        startIcon={<CodeIcon fontSize="small" />}
      >
        {showData ? "Hide data" : `View data (${block.associationsShown ?? 0})`}
      </Button>
      <Collapse in={showData} unmountOnExit>
        <Stack spacing={1} sx={{ mt: 1 }}>
          {block.categoryJson && <JsonBlock label={`${block.database} · Category`} text={block.categoryJson} />}
          <DocList label="CategoryProductAssociation" docs={block.associations} />
        </Stack>
      </Collapse>
    </Box>
  );
}

/** A single membership cell: filled green tick when present, hollow ring when absent. */
function Flag({ on, color = "#1aa564" }: { on: boolean; color?: string }) {
  return on ? (
    <Box sx={{ width: 18, height: 18, borderRadius: "50%", bgcolor: color, display: "grid", placeItems: "center", mx: "auto" }}>
      <CheckIcon sx={{ fontSize: 13, color: "#fff" }} />
    </Box>
  ) : (
    <Box sx={{ width: 18, height: 18, borderRadius: "50%", border: "1.5px solid", borderColor: "grey.300", mx: "auto" }} />
  );
}

/** A compact stat tile used in the summary header. */
function StatTile({ value, label, color, hint }: { value: number | string; label: string; color: string; hint?: string }) {
  return (
    <Tooltip title={hint || ""} disableHoverListener={!hint}>
      <Paper
        variant="outlined"
        sx={{ px: 1.75, py: 1, borderRadius: 2, minWidth: 118, borderColor: `${color}55`, bgcolor: `${color}0d` }}
      >
        <Typography sx={{ fontSize: 26, fontWeight: 800, lineHeight: 1, color }}>{value}</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, textTransform: "uppercase", letterSpacing: 0.4 }}>
          {label}
        </Typography>
      </Paper>
    </Tooltip>
  );
}

type MatrixFilter = "all" | "common" | "missHcl" | "missCatalog" | "missCtor" | "inStockNotCtor" | "ctorNotStock";

function matchesFilter(r: CategoryReconcileRow, f: MatrixFilter): boolean {
  switch (f) {
    case "common":
      return r.hcl && r.catalog && r.constructor;
    case "missHcl":
      return !r.hcl && (r.catalog || r.constructor);
    case "missCatalog":
      return !r.catalog && (r.hcl || r.constructor);
    case "missCtor":
      return !r.constructor && (r.hcl || r.catalog);
    case "inStockNotCtor":
      return r.inStock && !r.constructor;
    case "ctorNotStock":
      return r.constructor && !r.inStock;
    default:
      return true;
  }
}

/** The headline reconciliation card: aggregate stats, source coverage and a filterable membership matrix. */
function ReconcileSummary({ data, loading, error }: { data?: CategoryReconcileResponse; loading: boolean; error?: string }) {
  const [filter, setFilter] = useState<MatrixFilter>("all");

  const rows = useMemo(() => {
    if (!data) return [];
    return data.summary.matrix.filter((r) => matchesFilter(r, filter));
  }, [data, filter]);

  const header = (
    <Stack
      direction="row"
      alignItems="center"
      spacing={1}
      sx={{ px: 2, py: 1.25, bgcolor: `${ACCENT}12`, borderBottom: "1px solid", borderColor: "divider" }}
    >
      <Box sx={{ width: 30, height: 30, borderRadius: 2, display: "grid", placeItems: "center", bgcolor: ACCENT, color: "#fff" }}>
        <HubIcon fontSize="small" />
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 800, color: ACCENT }}>
          Reconciliation summary
        </Typography>
        <Typography variant="caption" color="text.secondary">
          Products shared across HCL, Catalog & Constructor and the ones each source is missing. Constructor only lists
          <b> in-stock</b> products (runtime Inventory <code>totalQuantity&nbsp;&gt;&nbsp;0</code>).
        </Typography>
      </Box>
      {loading && <CircularProgress size={16} sx={{ color: ACCENT }} />}
    </Stack>
  );

  return (
    <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
      {header}
      <Box sx={{ p: 2 }}>
        {error ? (
          <Box sx={{ p: 1.25, borderRadius: 2, bgcolor: "#fdeaea", border: "1px solid #f5c2c0" }}>
            <Typography variant="caption" sx={{ color: "#a12b29" }}>{error}</Typography>
          </Box>
        ) : !data ? (
          <Typography variant="body2" color="text.secondary">
            {loading ? "Reconciling sources…" : "No reconciliation yet."}
          </Typography>
        ) : (
          <Stack spacing={2}>
            {/* aggregate stats */}
            <Stack direction="row" spacing={1.25} flexWrap="wrap" useFlexGap>
              <StatTile value={data.summary.commonAllCount} label="common (all 3)" color="#1aa564" hint="Present in HCL, Catalog and Constructor" />
              <StatTile value={data.summary.unionCount} label="total distinct" color={ACCENT} hint="Union of product ids across the sources" />
              <StatTile value={data.hcl.count ?? "—"} label="HCL" color="#0e9aa7" hint={data.hcl.available ? undefined : data.hcl.error || data.hcl.reason} />
              <StatTile value={data.catalog.count ?? "—"} label="Catalog" color={ACCENT} hint={data.catalog.available ? undefined : data.catalog.error} />
              <StatTile value={data.constructor.count ?? "—"} label="Constructor" color="#f5871f" hint={data.constructor.available ? undefined : data.constructor.reason || data.constructor.error} />
              <StatTile
                value={data.inventory.available ? data.inventory.inStockProductCount ?? "—" : "—"}
                label="in stock"
                color="#2f6bff"
                hint={data.inventory.available
                  ? `${data.inventory.inStockSkuCount ?? 0} in-stock SKUs across ${data.inventory.skuCount ?? 0} SKUs (inventory-runtime)`
                  : data.inventory.error}
              />
            </Stack>

            {/* inventory reconciliation callout */}
            {data.inventory.available && (
              <Box sx={{ p: 1.25, borderRadius: 2, bgcolor: "#eef4ff", border: "1px solid #cadcff" }}>
                <Stack direction="row" spacing={1} alignItems="flex-start">
                  <Inventory2Icon fontSize="small" sx={{ color: "#2f6bff", mt: 0.25 }} />
                  <Typography variant="caption" sx={{ color: "#274b8f" }}>
                    <b>{data.inventory.inStockProductCount ?? 0}</b> products have in-stock inventory vs{" "}
                    <b>{data.constructor.count ?? 0}</b> in Constructor.{" "}
                    {data.summary.inStockNotInConstructorCount > 0 && (
                      <>
                        <b>{data.summary.inStockNotInConstructorCount}</b> are in stock but missing from Constructor.{" "}
                      </>
                    )}
                    {data.summary.constructorNotInStockCount > 0 && (
                      <>
                        <b>{data.summary.constructorNotInStockCount}</b> are in Constructor but show no in-stock SKU.
                      </>
                    )}
                    {data.summary.inStockNotInConstructorCount === 0 && data.summary.constructorNotInStockCount === 0 && (
                      <>Constructor matches the in-stock set exactly.</>
                    )}
                  </Typography>
                </Stack>
              </Box>
            )}

            {/* filter chips */}
            <ToggleButtonGroup
              size="small"
              value={filter}
              exclusive
              onChange={(_, v) => v && setFilter(v)}
              sx={{ flexWrap: "wrap", "& .MuiToggleButton-root": { textTransform: "none", px: 1.25, py: 0.4 } }}
            >
              <ToggleButton value="all">All ({data.summary.unionCount})</ToggleButton>
              <ToggleButton value="common">Common ({data.summary.commonAllCount})</ToggleButton>
              <ToggleButton value="missHcl">Not in HCL ({data.summary.missingFromHclCount})</ToggleButton>
              <ToggleButton value="missCatalog">Not in Catalog ({data.summary.missingFromCatalogCount})</ToggleButton>
              <ToggleButton value="missCtor">Not in Constructor ({data.summary.missingFromConstructorCount})</ToggleButton>
              <ToggleButton value="inStockNotCtor">In stock · not in Constructor ({data.summary.inStockNotInConstructorCount})</ToggleButton>
              <ToggleButton value="ctorNotStock">Constructor · out of stock ({data.summary.constructorNotInStockCount})</ToggleButton>
            </ToggleButtonGroup>

            {/* membership matrix */}
            <Box sx={{ maxHeight: 360, overflow: "auto", border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
              <Table size="small" stickyHeader sx={{ "& td, & th": { borderColor: "divider" } }}>
                <TableHead>
                  <TableRow>
                    <TableCell sx={{ fontWeight: 700 }}>Product id</TableCell>
                    <TableCell align="center" sx={{ fontWeight: 700, color: "#0e9aa7" }}>HCL</TableCell>
                    <TableCell align="center" sx={{ fontWeight: 700, color: ACCENT }}>Catalog</TableCell>
                    <TableCell align="center" sx={{ fontWeight: 700, color: "#f5871f" }}>Constructor</TableCell>
                    <TableCell align="center" sx={{ fontWeight: 700, color: "#2f6bff" }}>In stock</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((r) => (
                    <TableRow key={r.id} hover>
                      <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{r.id}</TableCell>
                      <TableCell align="center"><Flag on={r.hcl} color="#0e9aa7" /></TableCell>
                      <TableCell align="center"><Flag on={r.catalog} color={ACCENT} /></TableCell>
                      <TableCell align="center"><Flag on={r.constructor} color="#f5871f" /></TableCell>
                      <TableCell align="center"><Flag on={r.inStock} color="#2f6bff" /></TableCell>
                    </TableRow>
                  ))}
                  {rows.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={5}>
                        <Typography variant="caption" color="text.secondary">No products match this filter.</Typography>
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </Box>
            {data.summary.matrixShown < data.summary.unionCount && (
              <Typography variant="caption" color="text.secondary">
                Showing first {data.summary.matrixShown} of {data.summary.unionCount} products.
              </Typography>
            )}
          </Stack>
        )}
      </Box>
    </Paper>
  );
}

export function CategoriesView() {
  const { toast } = useUi();
  const { environments, activeEnv, setActiveEnv } = useAppState();

  const envNames = useMemo(
    () => (environments.length > 0 ? environments.map((e) => e.name) : ["Dev", "QA", "Perf"]),
    [environments]
  );
  const env = activeEnv || envNames[0];

  const [bulb, setBulb] = useState<BulbState>("checking");
  const [bulbHost, setBulbHost] = useState("");
  const [bulbError, setBulbError] = useState<string | undefined>();
  const [categoryId, setCategoryId] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);

  const [hcl, setHcl] = useState<Slot<CategoryHclResponse>>(emptySlot);
  const [catalog, setCatalog] = useState<Slot<CategoryCatalogResponse>>(emptySlot);
  const [constructor, setConstructor] = useState<Slot<CategoryConstructorResponse>>(emptySlot);
  const [reconcile, setReconcile] = useState<Slot<CategoryReconcileResponse>>(emptySlot);

  const checkStatus = useCallback(async () => {
    if (!env) return;
    setBulb("checking");
    try {
      const s = await api<{ up: boolean; host: string; error?: string }>("/api/hcl/status", { params: { env } });
      setBulb(s.up ? "up" : "down");
      setBulbHost(s.host || "");
      setBulbError(s.error);
    } catch (e) {
      setBulb("down");
      setBulbError((e as Error).message);
    }
  }, [env]);

  useEffect(() => {
    void checkStatus();
  }, [checkStatus]);

  const loadHcl = useCallback(
    async (cat: string) => {
      setHcl({ loading: true });
      try {
        const data = await api<CategoryHclResponse>("/api/category/hcl", { params: { env, categoryId: cat } });
        setHcl({ loading: false, data });
      } catch (e) {
        setHcl({ loading: false, error: (e as Error).message });
      }
    },
    [env]
  );

  const loadCatalog = useCallback(
    async (cat: string) => {
      setCatalog({ loading: true });
      try {
        const data = await api<CategoryCatalogResponse>("/api/category/catalog", { params: { env, categoryId: cat } });
        setCatalog({ loading: false, data });
      } catch (e) {
        setCatalog({ loading: false, error: (e as Error).message });
      }
    },
    [env]
  );

  const loadConstructor = useCallback(
    async (cat: string) => {
      setConstructor({ loading: true });
      try {
        const data = await api<CategoryConstructorResponse>("/api/category/constructor", {
          params: { env, categoryId: cat },
        });
        setConstructor({ loading: false, data });
      } catch (e) {
        setConstructor({ loading: false, error: (e as Error).message });
      }
    },
    [env]
  );

  const loadReconcile = useCallback(
    async (cat: string) => {
      setReconcile({ loading: true });
      try {
        const data = await api<CategoryReconcileResponse>("/api/category/reconcile", {
          params: { env, categoryId: cat },
        });
        setReconcile({ loading: false, data });
      } catch (e) {
        setReconcile({ loading: false, error: (e as Error).message });
      }
    },
    [env]
  );

  const fetchAll = () => {
    const cat = categoryId.trim();
    if (!cat) {
      toast("Enter a category id.", "error");
      return;
    }
    setSubmitted(cat);
    void loadHcl(cat);
    void loadCatalog(cat);
    void loadConstructor(cat);
    void loadReconcile(cat);
  };

  const clear = () => {
    setCategoryId("");
    setSubmitted(null);
    setHcl(emptySlot());
    setCatalog(emptySlot());
    setConstructor(emptySlot());
    setReconcile(emptySlot());
  };

  const bulbInfo = BULB[bulb];
  const bulbTooltip =
    bulb === "down" && bulbError ? `${bulbInfo.label} — ${bulbError}` : bulbHost ? `${bulbInfo.label} · ${bulbHost}` : bulbInfo.label;

  const hclData = hcl.data;
  const ctorData = constructor.data;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, p: 2, gap: 2 }}>
      <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
        <Stack direction="row" alignItems="flex-start" spacing={2} flexWrap="wrap" useFlexGap>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Stack direction="row" alignItems="center" spacing={1.5}>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>
                Categories
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
            </Stack>
            <Typography variant="caption" color="text.secondary">
              Enter a category id (HCL <b>catGroupId</b> / Mongo <b>hclCategoryId</b>) to see how many products it holds
              in HCL, the Catalog collections and Constructor — side by side. Read-only. HCL requires VPN.
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
              label="Category id"
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") fetchAll();
              }}
              sx={{ minWidth: 220 }}
            />
            <Button
              variant="contained"
              startIcon={<SearchIcon />}
              onClick={fetchAll}
              sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#a21caf" } }}
            >
              Fetch
            </Button>
            <Button
              variant="outlined"
              color="inherit"
              startIcon={<ClearIcon />}
              onClick={clear}
              disabled={!categoryId && !submitted}
            >
              Clear
            </Button>
          </Stack>
        </Stack>
      </Paper>

      <Box sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
        {!submitted ? (
          <Box sx={{ display: "grid", placeItems: "center", py: 8, textAlign: "center" }}>
            <Stack alignItems="center" spacing={1}>
              <Box sx={{ width: 56, height: 56, borderRadius: 3, display: "grid", placeItems: "center", bgcolor: `${ACCENT}1a`, color: ACCENT }}>
                <CategoryIcon fontSize="large" />
              </Box>
              <Typography variant="body2" color="text.secondary">
                Enter a category id and Fetch to compare product counts across HCL, Catalog and Constructor.
              </Typography>
            </Stack>
          </Box>
        ) : (
          <Stack spacing={2}>
            <ReconcileSummary data={reconcile.data} loading={reconcile.loading} error={reconcile.error} />
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: { xs: "1fr", lg: "repeat(3, 1fr)" },
              gap: 2,
              alignItems: "start",
            }}
          >
            {/* HCL */}
            <SourcePanel
              icon={<StorageIcon fontSize="small" />}
              title="HCL"
              subtitle="DB2 · CATGPENREL → products"
              color="#0e9aa7"
              slot={hcl}
              onReload={submitted ? () => void loadHcl(submitted) : undefined}
            >
              {hclData && hclData.found === false ? (
                <Typography variant="body2" color="text.secondary">
                  {hclData.reason || "Category not found in HCL."}
                </Typography>
              ) : (
                <Stack spacing={1}>
                  <BigCount value={hclData?.count} label="products in category" color="#0e9aa7" />
                  {hclData && (
                    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                      {hclData.catGroupId !== undefined && (
                        <Chip size="small" variant="outlined" label={`CATGROUP_ID ${hclData.catGroupId}`} />
                      )}
                      {hclData.identifier && <Chip size="small" variant="outlined" label={hclData.identifier} />}
                    </Stack>
                  )}
                  {hclData && hclData.products && hclData.products.length > 0 && (
                    <Box sx={{ maxHeight: 320, overflow: "auto", border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
                      <Table size="small" stickyHeader sx={{ "& td, & th": { borderColor: "divider" } }}>
                        <TableHead>
                          <TableRow>
                            <TableCell sx={{ fontWeight: 700 }}>Part #</TableCell>
                            <TableCell sx={{ fontWeight: 700 }}>Name</TableCell>
                            <TableCell sx={{ fontWeight: 700 }}>Pub</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {hclData.products.map((p) => (
                            <TableRow key={p.catEntryId} hover>
                              <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{p.partNumber || "—"}</TableCell>
                              <TableCell>{p.name || "—"}</TableCell>
                              <TableCell>{p.published ?? "—"}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </Box>
                  )}
                  {hclData && (hclData.productsShown ?? 0) < (hclData.count ?? 0) && (
                    <Typography variant="caption" color="text.secondary">
                      Showing first {hclData.productsShown} of {hclData.count}.
                    </Typography>
                  )}
                </Stack>
              )}
            </SourcePanel>

            {/* Catalog collections */}
            <SourcePanel
              icon={<CategoryIcon fontSize="small" />}
              title="Catalog collections"
              subtitle="Mongo · CategoryProductAssociation"
              color={ACCENT}
              slot={catalog}
              onReload={submitted ? () => void loadCatalog(submitted) : undefined}
            >
              {catalog.data && (
                <Stack spacing={1.5} divider={<Divider flexItem />}>
                  <CatalogDbBlock block={catalog.data.config} color={ACCENT} />
                  <CatalogDbBlock block={catalog.data.runtime} color={ACCENT} />
                </Stack>
              )}
            </SourcePanel>

            {/* Constructor */}
            <SourcePanel
              icon={<ShoppingBagIcon fontSize="small" />}
              title="Constructor"
              subtitle="Browse API · total_num_results"
              color="#f5871f"
              slot={constructor}
              onReload={submitted ? () => void loadConstructor(submitted) : undefined}
            >
              {ctorData && ctorData.configured === false ? (
                <Box sx={{ p: 1.25, borderRadius: 2, bgcolor: "#fff8e1", border: "1px solid #ffe0a3" }}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <TravelExploreIcon fontSize="small" sx={{ color: "#b45309" }} />
                    <Typography variant="caption" sx={{ color: "#8a5a00" }}>
                      {ctorData.reason || "Constructor is not configured for this environment."}
                    </Typography>
                  </Stack>
                </Box>
              ) : ctorData && ctorData.ok === false ? (
                <Typography variant="body2" sx={{ color: "#a12b29" }}>
                  {ctorData.reason || "Constructor request failed."}
                </Typography>
              ) : (
                ctorData && (
                  <Stack spacing={1}>
                    <BigCount value={ctorData.count} label="products in category" color="#f5871f" />
                    {ctorData.resolvedFromCategoryId && ctorData.groupId && (
                      <Typography variant="caption" color="text.secondary">
                        Resolved category {ctorData.resolvedFromCategoryId}
                        {ctorData.categoryName ? ` (${ctorData.categoryName})` : ""} → Constructor group_id{" "}
                        <Box component="span" sx={{ fontFamily: "monospace" }}>{ctorData.groupId}</Box>
                      </Typography>
                    )}
                    {ctorData.results && ctorData.results.length > 0 && (
                      <Box sx={{ maxHeight: 320, overflow: "auto", border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
                        <Table size="small" stickyHeader sx={{ "& td, & th": { borderColor: "divider" } }}>
                          <TableHead>
                            <TableRow>
                              <TableCell sx={{ fontWeight: 700 }}>id</TableCell>
                              <TableCell sx={{ fontWeight: 700 }}>value</TableCell>
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {ctorData.results.map((r, i) => (
                              <TableRow key={i} hover>
                                <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{r.id || "—"}</TableCell>
                                <TableCell>{r.value || "—"}</TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </Box>
                    )}
                    {ctorData.count !== undefined && (ctorData.resultsShown ?? 0) < ctorData.count && (
                      <Typography variant="caption" color="text.secondary">
                        Showing first {ctorData.resultsShown} of {ctorData.count}.
                      </Typography>
                    )}
                  </Stack>
                )
              )}
            </SourcePanel>
          </Box>
          </Stack>
        )}
      </Box>
    </Box>
  );
}

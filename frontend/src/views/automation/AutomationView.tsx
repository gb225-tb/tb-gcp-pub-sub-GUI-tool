import { useCallback, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Collapse from "@mui/material/Collapse";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import IconButton from "@mui/material/IconButton";
import InputLabel from "@mui/material/InputLabel";
import ListItemText from "@mui/material/ListItemText";
import ListSubheader from "@mui/material/ListSubheader";
import MenuItem from "@mui/material/MenuItem";
import OutlinedInput from "@mui/material/OutlinedInput";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Tab from "@mui/material/Tab";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Tabs from "@mui/material/Tabs";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import Checkbox from "@mui/material/Checkbox";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import ClearIcon from "@mui/icons-material/Clear";
import DownloadIcon from "@mui/icons-material/Download";
import AutoAwesomeIcon from "@mui/icons-material/AutoAwesome";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import FactCheckIcon from "@mui/icons-material/FactCheck";
import RocketLaunchIcon from "@mui/icons-material/RocketLaunch";
import CompareArrowsIcon from "@mui/icons-material/CompareArrows";
import { ScenariosView } from "../scenarios/ScenariosView";
import { api } from "../../api/client";
import type {
  AutomationAiResponse,
  AutomationAiStatus,
  AutomationCatalog,
  AutomationRunSummary,
  AutomationScenario,
  AutomationScenarioResult,
  CheckStatus,
  HclCompareType,
  HclRawCompareResponse,
} from "../../api/types";
import { useAppState } from "../../app/AppState";
import { useUi } from "../../app/UiProvider";

const ACCENT = "#0284c7";

const STATUS_META: Record<CheckStatus, { color: string; bg: string; label: string }> = {
  PASS: { color: "#0f7b3f", bg: "#e7f6ec", label: "Pass" },
  FAIL: { color: "#a12b29", bg: "#fdeaea", label: "Fail" },
  SKIP: { color: "#6b7280", bg: "#eef0f3", label: "Skip" },
  NA: { color: "#8a5a00", bg: "#fff6e5", label: "N/A" },
  ERROR: { color: "#7c3aed", bg: "#f1e9fe", label: "Error" },
};

/** Per-verdict chip colors, shared by the run-results table and the raw-compare panel. */
const VERDICT_META: Record<string, { color: string; bg: string }> = {
  MATCH: { color: "#0f7b3f", bg: "#e7f6ec" },
  INFO: { color: "#0369a1", bg: "#e8f3ff" },
  XFORM: { color: "#7c3aed", bg: "#f1e9fe" },
  EXTRA: { color: "#8a5a00", bg: "#fff6e5" },
  GAP: { color: "#8a5a00", bg: "#fff6e5" },
};

function verdictStyle(verdict: string): { color: string; bg: string } {
  return VERDICT_META[verdict] ?? { color: "#a12b29", bg: "#fdeaea" };
}

function StatusChip({ status }: { status: CheckStatus }) {
  const m = STATUS_META[status];
  return (
    <Box
      component="span"
      sx={{
        px: 1,
        py: 0.25,
        borderRadius: 999,
        fontSize: 11,
        fontWeight: 800,
        color: m.color,
        bgcolor: m.bg,
        letterSpacing: 0.4,
        textTransform: "uppercase",
        whiteSpace: "nowrap",
      }}
    >
      {m.label}
    </Box>
  );
}

function SummaryCard({ value, label, color }: { value: number; label: string; color: string }) {
  return (
    <Paper variant="outlined" sx={{ px: 2, py: 1.25, borderRadius: 3, minWidth: 92, flex: "0 0 auto" }}>
      <Typography sx={{ fontSize: 28, fontWeight: 800, lineHeight: 1, color }}>{value}</Typography>
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, textTransform: "uppercase", letterSpacing: 0.5 }}>
        {label}
      </Typography>
    </Paper>
  );
}

/** One expandable result row: status, scenario, message, and the expected/actual + diffs detail. */
function ResultRow({
  item,
  onAnalyze,
  analyzing,
  analysis,
}: {
  item: AutomationScenarioResult;
  onAnalyze: () => void;
  analyzing: boolean;
  analysis?: string;
}) {
  const [open, setOpen] = useState(false);
  const { scenario, result } = item;
  // Every row is expandable: at minimum it shows the test-plan scenario summary.
  const hasDetail = true;
  const failing = result.status === "FAIL" || result.status === "ERROR";
  return (
    <>
      <TableRow hover sx={{ "& td": { borderBottom: hasDetail && open ? "none" : undefined } }}>
        <TableCell sx={{ width: 40 }}>
          {hasDetail && (
            <IconButton size="small" onClick={() => setOpen((o) => !o)}>
              <ExpandMoreIcon
                fontSize="small"
                sx={{ transform: open ? "rotate(180deg)" : "none", transition: "transform .15s" }}
              />
            </IconButton>
          )}
        </TableCell>
        <TableCell sx={{ fontFamily: "monospace", fontSize: 12, fontWeight: 700 }}>{scenario.id}</TableCell>
        <TableCell>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>{scenario.title}</Typography>
          <Typography variant="caption" color="text.secondary">{scenario.category} · {scenario.priority}</Typography>
        </TableCell>
        <TableCell><StatusChip status={result.status} /></TableCell>
        <TableCell>
          <Typography variant="body2" color="text.secondary">{result.message}</Typography>
        </TableCell>
        <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
          {result.checked > 0 && (
            <Typography variant="caption" color="text.secondary">
              {result.failed}/{result.checked}
            </Typography>
          )}
        </TableCell>
        <TableCell align="right" sx={{ width: 48 }}>
          {failing && (
            <Tooltip title="Analyze this failure with AI">
              <span>
                <IconButton size="small" onClick={onAnalyze} disabled={analyzing} sx={{ color: ACCENT }}>
                  {analyzing ? <CircularProgress size={16} /> : <AutoAwesomeIcon fontSize="small" />}
                </IconButton>
              </span>
            </Tooltip>
          )}
        </TableCell>
      </TableRow>
      {hasDetail && (
        <TableRow>
          <TableCell colSpan={7} sx={{ py: 0 }}>
            <Collapse in={open} unmountOnExit>
              <Box sx={{ p: 1.5, bgcolor: "grey.50", borderRadius: 2, mb: 1 }}>
                <Box sx={{ mb: 1, p: 1, borderRadius: 1.5, bgcolor: "#eef6ff", border: "1px solid #d6e8fb" }}>
                  <Typography variant="caption" sx={{ fontWeight: 800, color: "#0369a1", display: "block", mb: 0.25 }}>
                    Scenario summary (test plan)
                  </Typography>
                  <Typography variant="body2">{scenario.spec}</Typography>
                </Box>
                {result.expected != null && (
                  <Detail label="Expected" value={result.expected} />
                )}
                {result.actual != null && <Detail label="Actual" value={result.actual} />}
                {(result.diffs?.length ?? 0) > 0 && (
                  <Box sx={{ mt: 1, border: "1px solid", borderColor: "divider", borderRadius: 2, overflow: "hidden" }}>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell sx={{ fontWeight: 700 }}>Field</TableCell>
                          <TableCell sx={{ fontWeight: 700 }}>HCL (expected)</TableCell>
                          <TableCell sx={{ fontWeight: 700 }}>Streaming (actual)</TableCell>
                          <TableCell sx={{ fontWeight: 700 }}>Verdict</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {result.diffs.map((d, i) => (
                          <TableRow key={i}>
                            <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{d.docType}.{d.field}</TableCell>
                            <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{d.expected ?? "—"}</TableCell>
                            <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{d.actual ?? "—"}</TableCell>
                            <TableCell>
                              <Chip
                                size="small"
                                label={d.verdict}
                                sx={{ height: 20, fontSize: 11, fontWeight: 700, ...verdictStyle(d.verdict) }}
                              />
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </Box>
                )}
                {(result.sampleIds?.length ?? 0) > 0 && (
                  <Box sx={{ mt: 1 }}>
                    <Typography variant="caption" sx={{ fontWeight: 700 }}>Sample ids</Typography>
                    <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, mt: 0.5 }}>
                      {result.sampleIds.map((s) => (
                        <Chip key={s} size="small" label={s} sx={{ fontFamily: "monospace", fontSize: 11 }} />
                      ))}
                    </Box>
                  </Box>
                )}
                {analysis && (
                  <Box sx={{ mt: 1.5, p: 1.25, borderRadius: 2, bgcolor: "#f1f8ff", border: "1px solid #cfe6fb" }}>
                    <Stack direction="row" alignItems="center" spacing={0.75} sx={{ mb: 0.5 }}>
                      <AutoAwesomeIcon fontSize="small" sx={{ color: ACCENT }} />
                      <Typography variant="caption" sx={{ fontWeight: 800, color: ACCENT }}>AI analysis</Typography>
                    </Stack>
                    <Typography variant="body2" component="pre" sx={{ whiteSpace: "pre-wrap", fontFamily: "inherit", m: 0 }}>
                      {analysis}
                    </Typography>
                  </Box>
                )}
              </Box>
            </Collapse>
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <Typography variant="body2" sx={{ mb: 0.25 }}>
      <Box component="span" sx={{ fontWeight: 700 }}>{label}: </Box>
      <Box component="span" sx={{ fontFamily: "monospace", fontSize: 12 }}>{value}</Box>
    </Typography>
  );
}

const ALL_TAB = "ALL";

export function AutomationView() {
  const { toast } = useUi();
  const { environments, activeEnv, setActiveEnv } = useAppState();

  const envNames = useMemo(
    () => (environments.length > 0 ? environments.map((e) => e.name) : ["Dev", "QA", "Perf", "Prod"]),
    [environments]
  );
  const env = activeEnv || envNames[0];

  const [mode, setMode] = useState<"validate" | "scenarios" | "hclcompare">("validate");
  const [catalog, setCatalog] = useState<AutomationCatalog | null>(null);
  const [tab, setTab] = useState<string>(ALL_TAB);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [productId, setProductId] = useState("");
  const [sampleSize, setSampleSize] = useState(200);
  const [running, setRunning] = useState(false);
  const [summary, setSummary] = useState<AutomationRunSummary | null>(null);

  const [refOpen, setRefOpen] = useState(false);
  const [aiStatus, setAiStatus] = useState<AutomationAiStatus | null>(null);
  const [analysisById, setAnalysisById] = useState<Record<string, string>>({});
  const [analyzingId, setAnalyzingId] = useState<string | null>(null);
  const [analyzingAll, setAnalyzingAll] = useState(false);

  // Raw HCL <-> Catalog single-document compare.
  const [hclType, setHclType] = useState<HclCompareType>("PRODUCT");
  const [hclProductId, setHclProductId] = useState("");
  const [hclRunning, setHclRunning] = useState(false);
  const [hclResult, setHclResult] = useState<HclRawCompareResponse | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setCatalog(await api<AutomationCatalog>("/api/automation/scenarios"));
      } catch (e) {
        toast((e as Error).message, "error", "Failed to load scenarios");
      }
      try {
        setAiStatus(await api<AutomationAiStatus>("/api/automation/ai/status"));
      } catch {
        /* AI status is best-effort */
      }
    })();
  }, [toast]);

  // Scenarios visible under the active top-menu tab.
  const tabScenarios = useMemo<AutomationScenario[]>(() => {
    if (!catalog) return [];
    if (tab === ALL_TAB) return catalog.scenarios;
    return catalog.scenarios.filter((s) => s.group === tab);
  }, [catalog, tab]);

  const requiresProductId = useMemo(
    () => tabScenarios.some((s) => s.requiresProductId && (selectedIds.length === 0 || selectedIds.includes(s.id))),
    [tabScenarios, selectedIds]
  );

  const run = useCallback(async () => {
    if (!env) {
      toast("Select an environment.", "error");
      return;
    }
    setRunning(true);
    setAnalysisById({});
    try {
      const body = {
        env,
        group: tab === ALL_TAB ? undefined : tab,
        scenarioIds: selectedIds.length > 0 ? selectedIds : undefined,
        all: selectedIds.length === 0,
        productId: productId.trim() || undefined,
        sampleSize,
      };
      const data = await api<AutomationRunSummary>("/api/automation/run", { method: "POST", body });
      setSummary(data);
    } catch (e) {
      toast((e as Error).message, "error", "Run failed");
    } finally {
      setRunning(false);
    }
  }, [env, tab, selectedIds, productId, sampleSize, toast]);

  const runHclCompare = useCallback(async () => {
    if (!env) {
      toast("Select an environment.", "error");
      return;
    }
    if (!hclProductId.trim()) {
      toast("Enter a product part number.", "error");
      return;
    }
    setHclRunning(true);
    setHclResult(null);
    try {
      const body = { env, productId: hclProductId.trim(), type: hclType };
      const data = await api<HclRawCompareResponse>("/api/automation/hcl-compare", { method: "POST", body });
      setHclResult(data);
    } catch (e) {
      toast((e as Error).message, "error", "Compare failed");
    } finally {
      setHclRunning(false);
    }
  }, [env, hclProductId, hclType, toast]);

  const analyzeFailures = useCallback(
    async (failures: AutomationScenarioResult[], scopeKey: string) => {
      if (failures.length === 0) {
        toast("No failures to analyze.", "info");
        return;
      }
      if (scopeKey === "ALL") setAnalyzingAll(true);
      else setAnalyzingId(scopeKey);
      try {
        const payload = {
          env,
          failures: failures.map((f) => ({
            scenarioId: f.scenario.id,
            group: f.scenario.group,
            title: f.scenario.title,
            priority: f.scenario.priority,
            note: f.scenario.note,
            status: f.result.status,
            message: f.result.message,
            expected: f.result.expected,
            actual: f.result.actual,
            sampleIds: f.result.sampleIds,
            diffs: f.result.diffs,
          })),
        };
        const res = await api<AutomationAiResponse>("/api/automation/analyze", { method: "POST", body: payload });
        setAnalysisById((prev) => {
          const next = { ...prev };
          if (failures.length === 1) {
            next[failures[0].scenario.id] = res.analysis;
          } else {
            next.__ALL__ = res.analysis;
          }
          return next;
        });
      } catch (e) {
        toast((e as Error).message, "error", "AI analysis failed");
      } finally {
        setAnalyzingAll(false);
        setAnalyzingId(null);
      }
    },
    [env, toast]
  );

  const clear = () => {
    setSelectedIds([]);
    setProductId("");
    setSummary(null);
    setAnalysisById({});
    setTab(ALL_TAB);
  };

  const exportReport = () => {
    if (!summary) return;
    const blob = new Blob([JSON.stringify(summary, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `automation-${summary.env}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const exportCsv = () => {
    if (!summary) return;
    const rows = [["scenarioId", "group", "title", "priority", "status", "checked", "failed", "message"]];
    for (const r of summary.results) {
      rows.push([
        r.scenario.id,
        r.scenario.group,
        r.scenario.title,
        r.scenario.priority,
        r.result.status,
        String(r.result.checked),
        String(r.result.failed),
        (r.result.message || "").replace(/"/g, "'"),
      ]);
    }
    const csv = rows.map((row) => row.map((c) => `"${c}"`).join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `automation-${summary.env}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Results filtered to the active tab (so the tab both scopes selection and the shown results).
  const shownResults = useMemo<AutomationScenarioResult[]>(() => {
    if (!summary) return [];
    if (tab === ALL_TAB) return summary.results;
    return summary.results.filter((r) => r.scenario.group === tab);
  }, [summary, tab]);

  const failures = useMemo(
    () => shownResults.filter((r) => r.result.status === "FAIL" || r.result.status === "ERROR"),
    [shownResults]
  );

  const groups = catalog?.groups ?? [];

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, p: 2, gap: 2 }}>
      {/* Header / controls */}
      <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
        <Stack direction="row" alignItems="center" spacing={1.5} sx={{ mb: 1.5 }}>
          <Box sx={{ width: 34, height: 34, borderRadius: 2, bgcolor: ACCENT, color: "#fff", display: "grid", placeItems: "center" }}>
            <FactCheckIcon fontSize="small" />
          </Box>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 800, lineHeight: 1.1 }}>
              Automation — Catalog &amp; Inventory
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {mode === "validate"
                ? "Read-only checks of the ingestion scenarios against live Dev/QA/Perf data. No writes."
                : mode === "hclcompare"
                ? "Pick a document type — fetch one raw HCL DB record and diff it against the streaming Catalog doc. Read-only; VPN + Product Id."
                : "Inject a controlled input into Perf, wait, then verify the outcome. Perf only."}
            </Typography>
          </Box>
          <Tooltip
            title={
              aiStatus?.llmConfigured
                ? `Failure analysis uses ${aiStatus.effectiveProvider}${aiStatus.model ? ` (${aiStatus.model})` : ""}. Run scenarios, then click the ✨ on a failed row (or "Analyze failures") for an explanation.`
                : "Failure analysis uses the built-in offline rule-based analyzer (no credentials needed). Run scenarios, then click the ✨ on a failed row (or \"Analyze failures\") for a root-cause explanation. Set AUTOMATION_AI_PROVIDER=openai with a base-url/api-key for LLM analysis."
            }
          >
            <Chip
              size="small"
              icon={<AutoAwesomeIcon />}
              label={aiStatus?.llmConfigured ? `AI: ${aiStatus.effectiveProvider}${aiStatus.model ? ` · ${aiStatus.model}` : ""}` : "AI: rule-based"}
              variant="outlined"
              sx={{ fontWeight: 600 }}
            />
          </Tooltip>
        </Stack>

        <ToggleButtonGroup
          exclusive
          size="small"
          value={mode}
          onChange={(_, v) => { if (v) setMode(v); }}
          sx={{
            mb: mode === "scenarios" ? 0 : 1.75,
            "& .MuiToggleButton-root": { textTransform: "none", fontWeight: 700, px: 2, gap: 0.75 },
            "& .Mui-selected": { color: `${ACCENT} !important`, bgcolor: `${ACCENT}14 !important` },
          }}
        >
          <ToggleButton value="validate"><FactCheckIcon fontSize="small" /> Read-only validation</ToggleButton>
          <ToggleButton value="hclcompare"><CompareArrowsIcon fontSize="small" /> HCL ↔ Catalog (raw)</ToggleButton>
          <ToggleButton value="scenarios"><RocketLaunchIcon fontSize="small" /> Scenario runner</ToggleButton>
        </ToggleButtonGroup>

        {mode === "validate" && (
        <Stack direction="row" spacing={1.5} rowGap={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <FormControl size="small" sx={{ minWidth: 150 }}>
            <InputLabel>Environment</InputLabel>
            <Select label="Environment" value={env} onChange={(e) => setActiveEnv(String(e.target.value))}>
              {envNames.map((n) => (
                <MenuItem key={n} value={n}>{n}</MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 220, maxWidth: 260 }}>
            <InputLabel>Scenarios</InputLabel>
            <Select
              multiple
              value={selectedIds}
              onChange={(e) => setSelectedIds(typeof e.target.value === "string" ? e.target.value.split(",") : e.target.value)}
              input={<OutlinedInput label="Scenarios" />}
              renderValue={(sel) => (sel.length === 0 ? "All in tab" : `${sel.length} selected`)}
              MenuProps={{ PaperProps: { sx: { maxHeight: 380, width: 340 } }, autoFocus: false }}
            >
              <Box
                onClick={(e) => e.stopPropagation()}
                sx={{ display: "flex", gap: 1, px: 1.5, py: 0.5, position: "sticky", top: 0, zIndex: 1, bgcolor: "background.paper", borderBottom: "1px solid", borderColor: "divider" }}
              >
                <Button size="small" onClick={() => setSelectedIds(tabScenarios.map((s) => s.id))}>Select all</Button>
                <Button size="small" onClick={() => setSelectedIds([])}>Clear</Button>
              </Box>
              {(tab === ALL_TAB ? groups : groups.filter((g) => g.id === tab)).flatMap((g) => {
                const items = tabScenarios.filter((s) => s.group === g.id);
                if (items.length === 0) return [];
                return [
                  <ListSubheader key={`h-${g.id}`} sx={{ lineHeight: "28px", fontWeight: 800, color: "text.secondary", bgcolor: "grey.50" }}>
                    {g.label}
                  </ListSubheader>,
                  ...items.map((s) => (
                    <MenuItem key={s.id} value={s.id} dense sx={{ pl: 2 }}>
                      <Checkbox size="small" checked={selectedIds.includes(s.id)} sx={{ py: 0 }} />
                      <ListItemText
                        primary={s.id}
                        secondary={s.feasibility === "NOT_APPLICABLE" ? `${s.title} · N/A` : s.title}
                        primaryTypographyProps={{ noWrap: true, fontFamily: "monospace", fontWeight: 700, fontSize: 12 }}
                        secondaryTypographyProps={{ noWrap: true, variant: "caption" }}
                      />
                    </MenuItem>
                  )),
                ];
              })}
            </Select>
          </FormControl>

          <TextField
            size="small"
            label="Product Id (part number)"
            value={productId}
            onChange={(e) => setProductId(e.target.value)}
            error={requiresProductId && !productId.trim()}
            helperText={requiresProductId && !productId.trim() ? "Required for HCL cross-verify" : undefined}
            sx={{ minWidth: 220, "& .MuiFormHelperText-root": { position: "absolute", bottom: -20 } }}
          />

          <TextField
            size="small"
            label="Sample size"
            type="number"
            value={sampleSize}
            onChange={(e) => setSampleSize(Math.max(1, Math.min(2000, Number(e.target.value) || 200)))}
            sx={{ width: 120 }}
          />

          <Button variant="contained" onClick={() => void run()} disabled={running} startIcon={running ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />} sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#0369a1" } }}>
            {running ? "Running…" : "Run"}
          </Button>
          <Button variant="outlined" onClick={clear} startIcon={<ClearIcon />}>Clear</Button>
        </Stack>
        )}

        {mode === "hclcompare" && (
        <Stack direction="row" spacing={1.5} rowGap={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <FormControl size="small" sx={{ minWidth: 150 }}>
            <InputLabel>Environment</InputLabel>
            <Select label="Environment" value={env} onChange={(e) => setActiveEnv(String(e.target.value))}>
              {envNames.map((n) => (
                <MenuItem key={n} value={n}>{n}</MenuItem>
              ))}
            </Select>
          </FormControl>

          <TextField
            size="small"
            label="Product Id (part number)"
            value={hclProductId}
            onChange={(e) => setHclProductId(e.target.value)}
            error={!hclProductId.trim()}
            helperText={!hclProductId.trim() ? "Required" : undefined}
            sx={{ minWidth: 220, "& .MuiFormHelperText-root": { position: "absolute", bottom: -20 } }}
          />

          <FormControl size="small" sx={{ minWidth: 180 }}>
            <InputLabel>Document type</InputLabel>
            <Select
              label="Document type"
              value={hclType}
              onChange={(e) => setHclType(e.target.value as HclCompareType)}
            >
              <MenuItem value="PRODUCT">Product</MenuItem>
              <MenuItem value="VARIANT">Variant</MenuItem>
              <MenuItem value="SKU">SKU</MenuItem>
              <MenuItem value="PRICE">Price</MenuItem>
              <MenuItem value="ENRICHED">EnrichedProduct</MenuItem>
            </Select>
          </FormControl>

          <Button variant="contained" onClick={() => void runHclCompare()} disabled={hclRunning} startIcon={hclRunning ? <CircularProgress size={16} color="inherit" /> : <CompareArrowsIcon />} sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#0369a1" } }}>
            {hclRunning ? "Comparing…" : "Fetch & compare"}
          </Button>
          <Button variant="outlined" onClick={() => { setHclResult(null); setHclProductId(""); }} startIcon={<ClearIcon />}>Clear</Button>
        </Stack>
        )}
      </Paper>

      {mode === "scenarios" ? (
        <Box sx={{ flex: 1, minWidth: 0, minHeight: 0 }}>
          <ScenariosView embedded />
        </Box>
      ) : mode === "hclcompare" ? (
        <Box sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
          {!hclResult ? (
            <Paper variant="outlined" sx={{ p: 4, borderRadius: 3, textAlign: "center", color: "text.secondary" }}>
              <CompareArrowsIcon sx={{ fontSize: 40, color: "#cbd5e1", mb: 1 }} />
              <Typography variant="body2">
                Enter a product part number, pick a document type, then Fetch &amp; compare.
              </Typography>
              <Typography variant="caption" color="text.secondary">
                One raw HCL DB record is read (untransformed) and diffed against the streaming Catalog document.
                <b> XFORM</b> = the pipeline transformed the value (expected); <b>MISSING</b> = the source value
                was dropped; <b>createdBy</b> shows the ingesting processor. Requires VPN.
              </Typography>
            </Paper>
          ) : (
            <Stack spacing={1.5}>
              <Paper variant="outlined" sx={{ p: 1.75, borderRadius: 3 }}>
                <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
                  <StatusChip status={hclResult.status} />
                  <Typography variant="body2" sx={{ fontWeight: 700, fontFamily: "monospace" }}>
                    {hclResult.docType ?? hclResult.type}{hclResult.docId ? `[${hclResult.docId}]` : ""}
                  </Typography>
                  {hclResult.collection && (
                    <Chip size="small" variant="outlined" label={`item-config · ${hclResult.collection}`} sx={{ fontFamily: "monospace", fontSize: 11 }} />
                  )}
                  <Box sx={{ flex: 1 }} />
                  {hclResult.checked > 0 && (
                    <Typography variant="caption" color="text.secondary">
                      {hclResult.failed} differing / {hclResult.checked} fields
                    </Typography>
                  )}
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
                  env={hclResult.env} · {hclResult.message}
                </Typography>
              </Paper>

              {(hclResult.diffs?.length ?? 0) > 0 && (
                <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
                  <Table size="small" stickyHeader sx={{ "& td, & th": { borderColor: "divider" } }}>
                    <TableHead>
                      <TableRow>
                        <TableCell sx={{ fontWeight: 700 }}>Field</TableCell>
                        <TableCell sx={{ fontWeight: 700 }}>Raw HCL (source)</TableCell>
                        <TableCell sx={{ fontWeight: 700 }}>Streaming (Catalog)</TableCell>
                        <TableCell sx={{ fontWeight: 700 }}>Verdict</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {hclResult.diffs.map((d, i) => (
                        <TableRow key={i} hover>
                          <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{d.field}</TableCell>
                          <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{d.expected ?? "—"}</TableCell>
                          <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{d.actual ?? "—"}</TableCell>
                          <TableCell>
                            <Chip size="small" label={d.verdict} sx={{ height: 20, fontSize: 11, fontWeight: 700, ...verdictStyle(d.verdict) }} />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Paper>
              )}
            </Stack>
          )}
        </Box>
      ) : (
      <>
      {/* Top-menu group tabs */}
      <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
        <Tabs
          value={tab}
          onChange={(_, v) => { setTab(v); setSelectedIds([]); }}
          variant="scrollable"
          scrollButtons="auto"
          sx={{ px: 1, "& .MuiTab-root": { fontWeight: 700, textTransform: "none" }, "& .Mui-selected": { color: ACCENT }, "& .MuiTabs-indicator": { bgcolor: ACCENT } }}
        >
          <Tab value={ALL_TAB} label="All" />
          {groups.map((g) => (
            <Tab key={g.id} value={g.id} label={g.label} />
          ))}
        </Tabs>
        <Divider />
        <Box sx={{ px: 1.5, py: 0.5 }}>
          <Button
            size="small"
            variant="text"
            onClick={() => setRefOpen((o) => !o)}
            startIcon={<ExpandMoreIcon sx={{ transform: refOpen ? "rotate(180deg)" : "none", transition: "transform .15s" }} />}
            sx={{ color: ACCENT, textTransform: "none", fontWeight: 700 }}
          >
            {refOpen ? "Hide" : "Show"} scenario summaries from test plan ({tabScenarios.length})
          </Button>
          <Collapse in={refOpen} unmountOnExit>
            <Stack spacing={0.75} sx={{ px: 0.5, pb: 1 }}>
              {(() => {
                const currentGroup = tab === ALL_TAB ? null : groups.find((g) => g.id === tab);
                return currentGroup ? (
                  <Typography variant="caption" color="text.secondary">{currentGroup.description}</Typography>
                ) : null;
              })()}
              {tabScenarios.map((s) => (
                <Box
                  key={s.id}
                  sx={{ p: 1, borderRadius: 1.5, border: "1px solid", borderColor: "divider", bgcolor: "grey.50" }}
                >
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.25 }} flexWrap="wrap" useFlexGap>
                    <Box component="span" sx={{ fontFamily: "monospace", fontWeight: 800, fontSize: 12 }}>{s.id}</Box>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{s.title}</Typography>
                    <Chip size="small" label={s.priority} sx={{ height: 18, fontSize: 10 }} />
                    {s.feasibility === "NOT_APPLICABLE" && (
                      <Chip size="small" label="N/A · needs injection" sx={{ height: 18, fontSize: 10, bgcolor: "#fff6e5", color: "#8a5a00" }} />
                    )}
                  </Stack>
                  <Typography variant="body2" color="text.secondary">{s.spec}</Typography>
                </Box>
              ))}
            </Stack>
          </Collapse>
        </Box>
      </Paper>

      {/* Results */}
      <Box sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
        {!summary ? (
          <Paper variant="outlined" sx={{ p: 4, borderRadius: 3, textAlign: "center", color: "text.secondary" }}>
            <FactCheckIcon sx={{ fontSize: 40, color: "#cbd5e1", mb: 1 }} />
            <Typography variant="body2">
              Pick an environment and a tab, then Run to validate the ingestion scenarios against live data.
            </Typography>
            <Typography variant="caption" color="text.secondary">
              HCL vs Streaming checks require a Product Id and VPN connectivity.
            </Typography>
          </Paper>
        ) : (
          <Stack spacing={1.5}>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
              <SummaryCard value={summary.total} label="Total" color="#334155" />
              <SummaryCard value={summary.passed} label="Passed" color="#0f7b3f" />
              <SummaryCard value={summary.failed} label="Failed" color="#a12b29" />
              <SummaryCard value={summary.skipped} label="Skipped" color="#6b7280" />
              <SummaryCard value={summary.notApplicable} label="N/A" color="#8a5a00" />
              <SummaryCard value={summary.errored} label="Errors" color="#7c3aed" />
              <Box sx={{ flex: 1 }} />
              <Tooltip title="Analyze all failures with AI">
                <span>
                  <Button
                    variant="outlined"
                    size="small"
                    onClick={() => void analyzeFailures(failures, "ALL")}
                    disabled={analyzingAll || failures.length === 0}
                    startIcon={analyzingAll ? <CircularProgress size={16} /> : <AutoAwesomeIcon />}
                    sx={{ color: ACCENT, borderColor: ACCENT }}
                  >
                    Analyze failures ({failures.length})
                  </Button>
                </span>
              </Tooltip>
              <Button variant="outlined" size="small" onClick={exportReport} startIcon={<DownloadIcon />}>JSON</Button>
              <Button variant="outlined" size="small" onClick={exportCsv} startIcon={<DownloadIcon />}>CSV</Button>
            </Stack>

            <Typography variant="caption" color="text.secondary">
              env={summary.env}{summary.productId ? ` · productId=${summary.productId}` : ""} · sample={summary.sampleSize} · {summary.durationMs} ms
            </Typography>

            {analysisById.__ALL__ && (
              <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 2, bgcolor: "#f1f8ff", borderColor: "#cfe6fb" }}>
                <Stack direction="row" alignItems="center" spacing={0.75} sx={{ mb: 0.5 }}>
                  <AutoAwesomeIcon fontSize="small" sx={{ color: ACCENT }} />
                  <Typography variant="caption" sx={{ fontWeight: 800, color: ACCENT }}>AI analysis — all failures</Typography>
                </Stack>
                <Typography variant="body2" component="pre" sx={{ whiteSpace: "pre-wrap", fontFamily: "inherit", m: 0 }}>
                  {analysisById.__ALL__}
                </Typography>
              </Paper>
            )}

            <Divider />

            <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
              <Table size="small" stickyHeader sx={{ "& td, & th": { borderColor: "divider" } }}>
                <TableHead>
                  <TableRow>
                    <TableCell />
                    <TableCell sx={{ fontWeight: 700 }}>ID</TableCell>
                    <TableCell sx={{ fontWeight: 700 }}>Scenario</TableCell>
                    <TableCell sx={{ fontWeight: 700 }}>Status</TableCell>
                    <TableCell sx={{ fontWeight: 700 }}>Result</TableCell>
                    <TableCell sx={{ fontWeight: 700 }} align="right">Fail/Chk</TableCell>
                    <TableCell />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {shownResults.map((item) => (
                    <ResultRow
                      key={item.scenario.id}
                      item={item}
                      analyzing={analyzingId === item.scenario.id}
                      analysis={analysisById[item.scenario.id]}
                      onAnalyze={() => void analyzeFailures([item], item.scenario.id)}
                    />
                  ))}
                </TableBody>
              </Table>
            </Paper>
          </Stack>
        )}
      </Box>
      </>
      )}
    </Box>
  );
}

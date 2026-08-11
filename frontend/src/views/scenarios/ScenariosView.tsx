import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Collapse from "@mui/material/Collapse";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogContentText from "@mui/material/DialogContentText";
import DialogTitle from "@mui/material/DialogTitle";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import InputLabel from "@mui/material/InputLabel";
import ListItemText from "@mui/material/ListItemText";
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
import LockIcon from "@mui/icons-material/Lock";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import ClearIcon from "@mui/icons-material/Clear";
import DownloadIcon from "@mui/icons-material/Download";
import UploadFileIcon from "@mui/icons-material/UploadFile";
import RocketLaunchIcon from "@mui/icons-material/RocketLaunch";
import AutoAwesomeIcon from "@mui/icons-material/AutoAwesome";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ErrorIcon from "@mui/icons-material/Error";
import RadioButtonUncheckedIcon from "@mui/icons-material/RadioButtonUnchecked";
import RemoveCircleOutlineIcon from "@mui/icons-material/RemoveCircleOutline";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import CodeIcon from "@mui/icons-material/Code";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { api } from "../../api/client";
import { JsonTree } from "../../components/JsonTree";
import type {
  AutomationAiResponse,
  CheckStatus,
  PhaseStatus,
  ScenarioCatalog,
  ScenarioRunPhase,
  ScenarioRunState,
  ScenarioSample,
  ScenarioSpec,
} from "../../api/types";
import { useUi } from "../../app/UiProvider";

const ACCENT = "#db2777";

const STATUS_META: Record<CheckStatus, { color: string; bg: string; label: string }> = {
  PASS: { color: "#0f7b3f", bg: "#e7f6ec", label: "Pass" },
  FAIL: { color: "#a12b29", bg: "#fdeaea", label: "Fail" },
  SKIP: { color: "#6b7280", bg: "#eef0f3", label: "Skip" },
  NA: { color: "#8a5a00", bg: "#fff6e5", label: "N/A" },
  ERROR: { color: "#7c3aed", bg: "#f1e9fe", label: "Error" },
};

const OVERALL_META: Record<string, { color: string; bg: string; label: string }> = {
  RUNNING: { color: "#0369a1", bg: "#e8f3ff", label: "Running" },
  PASS: { color: "#0f7b3f", bg: "#e7f6ec", label: "Pass" },
  FAIL: { color: "#a12b29", bg: "#fdeaea", label: "Fail" },
  ERROR: { color: "#7c3aed", bg: "#f1e9fe", label: "Error" },
};

function PhaseIcon({ status }: { status: PhaseStatus }) {
  if (status === "DONE") return <CheckCircleIcon fontSize="small" sx={{ color: "#0f7b3f" }} />;
  if (status === "FAILED") return <ErrorIcon fontSize="small" sx={{ color: "#a12b29" }} />;
  if (status === "SKIPPED") return <RemoveCircleOutlineIcon fontSize="small" sx={{ color: "#8a5a00" }} />;
  if (status === "RUNNING") return <CircularProgress size={16} />;
  return <RadioButtonUncheckedIcon fontSize="small" sx={{ color: "#c7cdd6" }} />;
}

function StatusChip({ status }: { status: CheckStatus }) {
  const m = STATUS_META[status];
  return (
    <Box
      component="span"
      sx={{ px: 1, py: 0.25, borderRadius: 999, fontSize: 11, fontWeight: 800, color: m.color, bgcolor: m.bg, letterSpacing: 0.4, textTransform: "uppercase", whiteSpace: "nowrap" }}
    >
      {m.label}
    </Box>
  );
}

/** Collapsible header wrapping the foldable JSON tree viewer. */
function JsonPanel({
  label,
  data,
  defaultOpen = false,
  autoOpenDepth = 1,
}: {
  label: string;
  data: unknown;
  defaultOpen?: boolean;
  autoOpenDepth?: number;
}) {
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
        <Typography variant="caption" sx={{ fontWeight: 700, flex: 1 }}>{label}</Typography>
        <ExpandMoreIcon
          fontSize="small"
          sx={{ transform: open ? "rotate(180deg)" : "none", transition: "transform .15s", color: "text.secondary" }}
        />
      </Stack>
      <Collapse in={open} unmountOnExit>
        <JsonTree data={data} autoOpenDepth={autoOpenDepth} />
      </Collapse>
    </Box>
  );
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result || "");
      const comma = result.indexOf(",");
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

export function ScenariosView({ embedded = false }: { embedded?: boolean } = {}) {
  const { toast } = useUi();

  const [catalog, setCatalog] = useState<ScenarioCatalog | null>(null);
  const [category, setCategory] = useState<string>("");
  const [scenarioId, setScenarioId] = useState<string>("");

  const [payload, setPayload] = useState<string>("");
  const [version, setVersion] = useState<string>("");
  const [file, setFile] = useState<{ name: string; base64: string } | null>(null);
  const [defaultFileName, setDefaultFileName] = useState<string>("");

  const [confirmOpen, setConfirmOpen] = useState(false);
  const [running, setRunning] = useState(false);
  const [run, setRun] = useState<ScenarioRunState | null>(null);
  const [analysis, setAnalysis] = useState<string>("");
  const [analyzing, setAnalyzing] = useState(false);
  const [inputOpen, setInputOpen] = useState(true);
  const [timelineOpen, setTimelineOpen] = useState(true);
  const pollRef = useRef<number | null>(null);

  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Once a run exists, collapse the input/config panel so the results surface without scrolling.
  const runId = run?.runId;
  useEffect(() => {
    setInputOpen(!runId);
  }, [runId]);

  // Keep the phase timeline open while a run is in progress; auto-fold it once the run finishes.
  const runDone = run?.done ?? false;
  useEffect(() => {
    if (runId) setTimelineOpen(!runDone);
  }, [runId, runDone]);

  useEffect(() => {
    void (async () => {
      try {
        const c = await api<ScenarioCatalog>("/api/scenario/catalog");
        setCatalog(c);
        if (c.categories.length > 0) setCategory((prev) => prev || c.categories[0].id);
      } catch (e) {
        toast((e as Error).message, "error", "Failed to load scenarios");
      }
    })();
  }, [toast]);

  // Stop polling on unmount.
  useEffect(() => () => {
    if (pollRef.current) window.clearInterval(pollRef.current);
  }, []);

  const scenariosInCategory = useMemo<ScenarioSpec[]>(
    () => (catalog ? catalog.scenarios.filter((s) => s.category === category) : []),
    [catalog, category]
  );

  const spec = useMemo<ScenarioSpec | null>(
    () => scenariosInCategory.find((s) => s.id === scenarioId) ?? null,
    [scenariosInCategory, scenarioId]
  );

  // Pick a default scenario when the category changes.
  useEffect(() => {
    if (scenariosInCategory.length === 0) {
      setScenarioId("");
      return;
    }
    if (!scenariosInCategory.some((s) => s.id === scenarioId)) {
      const firstEnabled = scenariosInCategory.find((s) => s.enabled) ?? scenariosInCategory[0];
      setScenarioId(firstEnabled.id);
    }
  }, [scenariosInCategory, scenarioId]);

  // Load the bundled sample whenever the scenario changes.
  useEffect(() => {
    if (!spec) return;
    setFile(null);
    void (async () => {
      try {
        const s = await api<ScenarioSample>(`/api/scenario/sample/${spec.id}`);
        if (s.kind === "STREAMING") {
          setPayload(s.content);
        } else {
          setPayload("");
          setDefaultFileName(s.fileName || spec.defaultFileName || "sample.dat");
        }
      } catch {
        /* sample is best-effort */
      }
    })();
  }, [spec]);

  const githubConfigured = catalog?.github?.configured ?? false;
  const perfEnv = catalog?.perfEnv ?? "Perf";

  // Parsed streaming payload for the foldable preview (undefined when not valid JSON).
  const parsedPayload = useMemo<unknown>(() => {
    if (spec?.kind !== "STREAMING" || !payload.trim()) return undefined;
    try {
      return JSON.parse(payload);
    } catch {
      return undefined;
    }
  }, [spec, payload]);

  const canRun = useMemo(() => {
    if (!spec || !spec.enabled || running) return false;
    if (spec.kind === "STREAMING") return payload.trim().length > 0;
    // batch
    return version.trim().length > 0 && githubConfigured;
  }, [spec, running, payload, version, githubConfigured]);

  const stopPolling = () => {
    if (pollRef.current) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  const startRun = useCallback(async () => {
    if (!spec) return;
    setConfirmOpen(false);
    setRunning(true);
    setAnalysis("");
    setRun(null);
    try {
      const body = {
        env: perfEnv,
        scenarioId: spec.id,
        payloadOverride: spec.kind === "STREAMING" ? payload : undefined,
        version: spec.kind === "BATCH" ? version.trim() : undefined,
        fileName: spec.kind === "BATCH" ? file?.name || defaultFileName : undefined,
        fileBase64: spec.kind === "BATCH" ? file?.base64 : undefined,
      };
      const initial = await api<ScenarioRunState>("/api/scenario/run", { method: "POST", body });
      setRun(initial);
      stopPolling();
      pollRef.current = window.setInterval(async () => {
        try {
          const state = await api<ScenarioRunState>(`/api/scenario/run/${initial.runId}`);
          setRun(state);
          if (state.done) {
            stopPolling();
            setRunning(false);
          }
        } catch {
          stopPolling();
          setRunning(false);
        }
      }, 3000);
    } catch (e) {
      toast((e as Error).message, "error", "Run failed to start");
      setRunning(false);
    }
  }, [spec, perfEnv, payload, version, file, defaultFileName, toast]);

  const onPickFile = async (f: File | null) => {
    if (!f) return;
    try {
      const base64 = await readFileAsBase64(f);
      setFile({ name: f.name, base64 });
    } catch (e) {
      toast((e as Error).message, "error", "Could not read file");
    }
  };

  const failures = useMemo(
    () => (run?.verify?.results ?? []).filter((r) => r.result.status === "FAIL" || r.result.status === "ERROR"),
    [run]
  );

  const analyzeFailures = useCallback(async () => {
    if (!run || failures.length === 0) {
      toast("No failures to analyze.", "info");
      return;
    }
    setAnalyzing(true);
    try {
      const payloadBody = {
        env: run.env,
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
      const res = await api<AutomationAiResponse>("/api/scenario/analyze", { method: "POST", body: payloadBody });
      setAnalysis(res.analysis);
    } catch (e) {
      toast((e as Error).message, "error", "AI analysis failed");
    } finally {
      setAnalyzing(false);
    }
  }, [run, failures, toast]);

  const clear = () => {
    stopPolling();
    setRun(null);
    setAnalysis("");
    setVersion("");
    setFile(null);
    setRunning(false);
  };

  const exportReport = () => {
    if (!run) return;
    const blob = new Blob([JSON.stringify(run, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `scenario-${run.scenarioId}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const overall = run ? OVERALL_META[run.status] ?? OVERALL_META.RUNNING : null;
  const githubRunUrl = run?.injection?.githubRunUrl as string | undefined;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, p: embedded ? 0 : 2, gap: 2 }}>
      {/* Header + controls */}
      <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
        {!embedded && (
          <Stack direction="row" alignItems="center" spacing={1.5} sx={{ mb: 1.5 }}>
            <Box sx={{ width: 34, height: 34, borderRadius: 2, bgcolor: ACCENT, color: "#fff", display: "grid", placeItems: "center" }}>
              <RocketLaunchIcon fontSize="small" />
            </Box>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="subtitle1" sx={{ fontWeight: 800, lineHeight: 1.1 }}>
                Scenarios — inject &amp; verify
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Publish a streaming message or upload a batch file for a Catalog/Inventory job, wait, then verify the outcome.
              </Typography>
            </Box>
          </Stack>
        )}

        <Stack direction="row" spacing={1.5} rowGap={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <Chip
            icon={<LockIcon />}
            label={`${perfEnv} only`}
            size="small"
            sx={{ fontWeight: 700, bgcolor: "#fdf2f8", color: ACCENT, border: `1px solid #f7cfe4`, "& .MuiChip-icon": { color: ACCENT } }}
          />

          <FormControl size="small" sx={{ minWidth: 200 }}>
            <InputLabel>Category</InputLabel>
            <Select label="Category" value={category} onChange={(e) => setCategory(String(e.target.value))}>
              {(catalog?.categories ?? []).map((c) => (
                <MenuItem key={c.id} value={c.id}>{c.label}</MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 240 }}>
            <InputLabel>Scenario</InputLabel>
            <Select label="Scenario" value={scenarioId} onChange={(e) => setScenarioId(String(e.target.value))}>
              {scenariosInCategory.map((s) => (
                <MenuItem key={s.id} value={s.id} disabled={!s.enabled}>
                  <ListItemText
                    primary={s.shortName}
                    secondary={s.kind === "BATCH" ? "Batch · upload + dispatch" : "Streaming · publish"}
                  />
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          {spec?.kind === "BATCH" && (
            <TextField
              size="small"
              label="Version"
              value={version}
              onChange={(e) => setVersion(e.target.value)}
              placeholder="e.g. 1.2.3 or a commit sha"
              error={version.trim().length === 0}
              helperText={!githubConfigured ? "GitHub token not configured" : version.trim().length === 0 ? "Required to dispatch" : " "}
              sx={{ width: 220, "& .MuiFormHelperText-root": { position: "absolute", bottom: -20 } }}
            />
          )}

          <Box sx={{ flex: 1 }} />
          <Button
            variant="contained"
            onClick={() => setConfirmOpen(true)}
            disabled={!canRun}
            startIcon={running ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />}
            sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#be185d" } }}
          >
            {running ? "Running…" : "Run scenario"}
          </Button>
          <Button variant="outlined" onClick={clear} startIcon={<ClearIcon />}>Clear</Button>
        </Stack>
      </Paper>

      {/* What will be injected (collapsible; auto-folds once a run starts) */}
      {spec && (
        <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
          <Stack
            direction="row"
            spacing={1}
            alignItems="center"
            flexWrap="wrap"
            useFlexGap
            onClick={() => setInputOpen((o) => !o)}
            sx={{ cursor: "pointer" }}
          >
            <Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{spec.shortName}</Typography>
            <Chip size="small" label={spec.kind} sx={{ fontWeight: 700 }} />
            <Chip size="small" variant="outlined" label={spec.processor} sx={{ fontFamily: "monospace" }} />
            {!spec.enabled && <Chip size="small" label="disabled" sx={{ bgcolor: "#fff6e5", color: "#8a5a00" }} />}
            <Box sx={{ flex: 1 }} />
            <Typography variant="caption" color="text.secondary">{inputOpen ? "Hide" : "Edit input"}</Typography>
            <ExpandMoreIcon fontSize="small" sx={{ transform: inputOpen ? "rotate(180deg)" : "none", transition: "transform .15s", color: "text.secondary" }} />
          </Stack>

          <Collapse in={inputOpen} unmountOnExit>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1, mb: 1 }}>{spec.description}</Typography>
            <Box sx={{ p: 1, borderRadius: 1.5, bgcolor: "grey.50", border: "1px solid", borderColor: "divider" }}>
              <Typography variant="caption" sx={{ fontWeight: 800 }}>Target </Typography>
              <Typography variant="caption" component="span" sx={{ fontFamily: "monospace" }}>{spec.target}</Typography>
            </Box>

            {spec.kind === "STREAMING" ? (
              <>
                <TextField
                  label="Message payload (JSON) — editable"
                  value={payload}
                  onChange={(e) => setPayload(e.target.value)}
                  multiline
                  minRows={8}
                  maxRows={18}
                  fullWidth
                  sx={{ mt: 1.5, "& textarea": { fontFamily: "monospace", fontSize: 12 } }}
                />
                {parsedPayload !== undefined && (
                  <Box sx={{ mt: 1 }}>
                    <JsonPanel label="Formatted preview (foldable)" data={parsedPayload} autoOpenDepth={2} />
                  </Box>
                )}
              </>
            ) : (
              <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mt: 1.5 }} flexWrap="wrap" useFlexGap>
                <Button variant="outlined" component="label" startIcon={<UploadFileIcon />}>
                  Choose file
                  <input
                    ref={fileInputRef}
                    hidden
                    type="file"
                    onChange={(e) => void onPickFile(e.target.files?.[0] ?? null)}
                  />
                </Button>
                <Typography variant="body2" color="text.secondary">
                  {file ? file.name : `Using bundled sample (${defaultFileName})`}
                </Typography>
              </Stack>
            )}
          </Collapse>
        </Paper>
      )}

      {/* Run timeline + results */}
      <Box sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
        {!run ? (
          <Paper variant="outlined" sx={{ p: 4, borderRadius: 3, textAlign: "center", color: "text.secondary" }}>
            <RocketLaunchIcon sx={{ fontSize: 40, color: "#f0abcf", mb: 1 }} />
            <Typography variant="body2">
              Pick a category and scenario, review the target, then Run to inject into {perfEnv} and verify.
            </Typography>
          </Paper>
        ) : (
          <Stack spacing={1.5}>
            {/* Status header (always at the top) */}
            <Paper variant="outlined" sx={{ p: 1.75, borderRadius: 3 }}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{run.shortName}</Typography>
                {overall && (
                  <Box component="span" sx={{ px: 1.25, py: 0.4, borderRadius: 999, fontSize: 12, fontWeight: 800, color: overall.color, bgcolor: overall.bg, textTransform: "uppercase", letterSpacing: 0.5, display: "inline-flex", alignItems: "center", gap: 0.5 }}>
                    {running && !run.done && <CircularProgress size={11} sx={{ color: overall.color }} />}
                    {overall.label}
                  </Box>
                )}
                <Typography variant="caption" color="text.secondary">{run.message}</Typography>
                <Box sx={{ flex: 1 }} />
                {githubRunUrl && (
                  <Button size="small" variant="text" endIcon={<OpenInNewIcon />} href={githubRunUrl} target="_blank" rel="noreferrer" sx={{ color: ACCENT }}>
                    GitHub run
                  </Button>
                )}
                <Button size="small" variant="outlined" onClick={exportReport} startIcon={<DownloadIcon />}>JSON</Button>
              </Stack>
            </Paper>

            {/* Verify results — surfaced right below the status so they are visible without scrolling */}
            {run.verify && (
              <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
                <Stack direction="row" spacing={1} alignItems="center" sx={{ p: 1.5 }} flexWrap="wrap" useFlexGap>
                  <Typography variant="subtitle2" sx={{ fontWeight: 800 }}>Verification</Typography>
                  <Chip size="small" label={`${run.verify.passed} pass`} sx={{ bgcolor: "#e7f6ec", color: "#0f7b3f", fontWeight: 700 }} />
                  <Chip size="small" label={`${run.verify.failed} fail`} sx={{ bgcolor: "#fdeaea", color: "#a12b29", fontWeight: 700 }} />
                  {run.verify.errored > 0 && <Chip size="small" label={`${run.verify.errored} error`} sx={{ bgcolor: "#f1e9fe", color: "#7c3aed", fontWeight: 700 }} />}
                  <Typography variant="caption" color="text.secondary">{run.verify.durationMs} ms</Typography>
                  <Box sx={{ flex: 1 }} />
                  <Tooltip title="Explain failures with AI">
                    <span>
                      <Button size="small" variant="outlined" onClick={() => void analyzeFailures()} disabled={analyzing || failures.length === 0} startIcon={analyzing ? <CircularProgress size={16} /> : <AutoAwesomeIcon />} sx={{ color: ACCENT, borderColor: ACCENT }}>
                        Analyze failures ({failures.length})
                      </Button>
                    </span>
                  </Tooltip>
                </Stack>
                <Divider />
                <Table size="small" sx={{ "& td, & th": { borderColor: "divider" } }}>
                  <TableHead>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 700 }}>Check</TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>Status</TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>Result</TableCell>
                      <TableCell sx={{ fontWeight: 700 }} align="right">Fail/Chk</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {run.verify.results.map((r) => (
                      <TableRow key={r.scenario.id} hover>
                        <TableCell>
                          <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.scenario.title}</Typography>
                          <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace" }}>{r.scenario.id}</Typography>
                        </TableCell>
                        <TableCell><StatusChip status={r.result.status} /></TableCell>
                        <TableCell><Typography variant="body2" color="text.secondary">{r.result.message}</Typography></TableCell>
                        <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                          {r.result.checked > 0 && (
                            <Typography variant="caption" color="text.secondary">{r.result.failed}/{r.result.checked}</Typography>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Paper>
            )}

            {analysis && (
              <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 2, bgcolor: "#fdf2f8", borderColor: "#f7cfe4" }}>
                <Stack direction="row" alignItems="center" spacing={0.75} sx={{ mb: 0.5 }}>
                  <AutoAwesomeIcon fontSize="small" sx={{ color: ACCENT }} />
                  <Typography variant="caption" sx={{ fontWeight: 800, color: ACCENT }}>AI analysis</Typography>
                </Stack>
                <Typography variant="body2" component="pre" sx={{ whiteSpace: "pre-wrap", fontFamily: "inherit", m: 0 }}>{analysis}</Typography>
              </Paper>
            )}

            {/* Phase timeline (collapsible; open while running, auto-folds when done) */}
            <Paper variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
              <Stack
                direction="row"
                alignItems="center"
                spacing={1}
                onClick={() => setTimelineOpen((o) => !o)}
                sx={{ px: 1.5, py: 1, cursor: "pointer", bgcolor: "grey.50", "&:hover": { bgcolor: "grey.100" } }}
              >
                <Typography variant="caption" sx={{ fontWeight: 800, flex: 1 }}>
                  Progress timeline ({run.phases.length} step{run.phases.length === 1 ? "" : "s"})
                </Typography>
                <ExpandMoreIcon fontSize="small" sx={{ transform: timelineOpen ? "rotate(180deg)" : "none", transition: "transform .15s", color: "text.secondary" }} />
              </Stack>
              <Collapse in={timelineOpen} unmountOnExit>
                <Stack spacing={0.75} sx={{ p: 1.5 }}>
                  {run.phases.map((p: ScenarioRunPhase, i) => (
                    <Stack key={i} direction="row" spacing={1} alignItems="flex-start">
                      <Box sx={{ mt: 0.25 }}><PhaseIcon status={p.status} /></Box>
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>{p.name}</Typography>
                        {p.detail && (
                          <Typography variant="caption" color="text.secondary" sx={{ wordBreak: "break-word" }}>{p.detail}</Typography>
                        )}
                      </Box>
                    </Stack>
                  ))}
                </Stack>
              </Collapse>
            </Paper>

            {/* Injection details + full run result — foldable JSON, collapsed by default */}
            {run.injection && Object.keys(run.injection).length > 0 && (
              <JsonPanel label="Injection details" data={run.injection} autoOpenDepth={2} />
            )}
            <JsonPanel label="Full run result (JSON)" data={run} autoOpenDepth={1} />
          </Stack>
        )}
      </Box>

      {/* Confirm dialog */}
      <Dialog open={confirmOpen} onClose={() => setConfirmOpen(false)}>
        <DialogTitle>Confirm injection into {perfEnv}</DialogTitle>
        <DialogContent>
          <DialogContentText component="div">
            {spec?.kind === "STREAMING" ? (
              <>Publish the edited message to <b>{spec?.topicId}</b> (project {catalog?.projectId}).</>
            ) : (
              <>
                Upload <b>{file?.name || defaultFileName}</b> to <b>gs://{spec?.gcsBucket}/{spec?.gcsObjectPrefix}</b>
                {" "}and dispatch <b>{spec?.workflowFile}</b> ({spec?.githubRepo}) with processor <b>{spec?.processor}</b>, environment <b>perf</b>, version <b>{version}</b>.
              </>
            )}
            <Box sx={{ mt: 1 }}>
              <Chip size="small" icon={<LockIcon />} label={`${perfEnv} only — no other environment`} sx={{ bgcolor: "#fdf2f8", color: ACCENT }} />
            </Box>
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => void startRun()} sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#be185d" } }}>
            Inject &amp; verify
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

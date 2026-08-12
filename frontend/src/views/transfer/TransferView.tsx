import { useCallback, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import InputLabel from "@mui/material/InputLabel";
import ListSubheader from "@mui/material/ListSubheader";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import Alert from "@mui/material/Alert";
import SyncAltIcon from "@mui/icons-material/SyncAlt";
import VisibilityIcon from "@mui/icons-material/Visibility";
import SendIcon from "@mui/icons-material/Send";
import { api } from "../../api/client";
import type { PubSubEnvironment, SubscriptionInfo, TransferRequest, TransferResult } from "../../api/types";
import { useAppState } from "../../app/AppState";
import { useUi } from "../../app/UiProvider";
import { MessageCard } from "../pubsub/MessageCard";

const ACCENT = "#4f46e5";

/** Locate a topic within an environment: which group and index inside that group. */
function locateTopic(env: PubSubEnvironment, topicId: string): { groupIdx: number; topicIdx: number } | null {
  for (let g = 0; g < env.topicGroups.length; g++) {
    const idx = env.topicGroups[g].topics.indexOf(topicId);
    if (idx >= 0) return { groupIdx: g, topicIdx: idx };
  }
  return null;
}

/** Map a source topic to the equivalent topic in the target env by group-name + index (prefix differs per env). */
function mapTopic(source: PubSubEnvironment, target: PubSubEnvironment, sourceTopicId: string): string {
  const loc = locateTopic(source, sourceTopicId);
  if (!loc) return "";
  const sourceGroup = source.topicGroups[loc.groupIdx];
  // Prefer a group with the same name; fall back to the same group index.
  const targetGroup =
    target.topicGroups.find((g) => g.name === sourceGroup.name) || target.topicGroups[loc.groupIdx];
  if (targetGroup && loc.topicIdx < targetGroup.topics.length) {
    return targetGroup.topics[loc.topicIdx];
  }
  return "";
}

/** Render grouped <MenuItem>s for an environment's topics (with group subheaders). */
function topicMenuItems(env: PubSubEnvironment | undefined) {
  if (!env) return null;
  return env.topicGroups.flatMap((g) => [
    <ListSubheader key={`h-${g.name}`} sx={{ fontWeight: 800, color: "text.secondary", bgcolor: "grey.50" }}>
      {g.name}
    </ListSubheader>,
    ...g.topics.map((t) => (
      <MenuItem key={t} value={t} sx={{ fontFamily: "monospace", fontSize: 12 }}>
        {t}
      </MenuItem>
    )),
  ]);
}

export function TransferView() {
  const { environments } = useAppState();
  const { toast, confirm } = useUi();

  // Prod is intentionally excluded from transfers.
  const envs = useMemo(() => environments.filter((e) => e.name.toLowerCase() !== "prod"), [environments]);

  const [sourceEnvName, setSourceEnvName] = useState("");
  const [targetEnvName, setTargetEnvName] = useState("");
  const [sourceTopic, setSourceTopic] = useState("");
  const [targetTopic, setTargetTopic] = useState("");
  const [subs, setSubs] = useState<SubscriptionInfo[] | null>(null);
  const [subsLoading, setSubsLoading] = useState(false);
  const [sourceSub, setSourceSub] = useState("");
  const [max, setMax] = useState(100);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<TransferResult | null>(null);

  const sourceEnv = useMemo(() => envs.find((e) => e.name === sourceEnvName), [envs, sourceEnvName]);
  const targetEnv = useMemo(() => envs.find((e) => e.name === targetEnvName), [envs, targetEnvName]);

  // Seed defaults once environments are available.
  useEffect(() => {
    if (envs.length === 0) return;
    setSourceEnvName((prev) => prev || envs[0].name);
    setTargetEnvName((prev) => prev || (envs.find((e) => e.name !== envs[0].name)?.name ?? envs[0].name));
  }, [envs]);

  // Fetch the source topic's subscriptions (from the SOURCE project) whenever it changes.
  useEffect(() => {
    setSubs(null);
    setSourceSub("");
    if (!sourceEnv || !sourceTopic) return;
    let cancelled = false;
    setSubsLoading(true);
    void (async () => {
      try {
        const data = await api<SubscriptionInfo[]>(
          `/api/topics/${encodeURIComponent(sourceTopic)}/subscriptions`,
          { params: { project: sourceEnv.projectId } }
        );
        if (cancelled) return;
        setSubs(data);
        if (data.length === 1) setSourceSub(data[0].id);
      } catch (e) {
        if (!cancelled) {
          setSubs([]);
          toast((e as Error).message, "error", "Failed to load subscriptions");
        }
      } finally {
        if (!cancelled) setSubsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sourceEnv, sourceTopic, toast]);

  // Suggest the equivalent target topic when the source topic or target env changes.
  useEffect(() => {
    if (sourceEnv && targetEnv && sourceTopic) {
      setTargetTopic(mapTopic(sourceEnv, targetEnv, sourceTopic));
    }
  }, [sourceEnv, targetEnv, sourceTopic]);

  const sameTopic =
    !!sourceEnv &&
    !!targetEnv &&
    sourceEnv.projectId === targetEnv.projectId &&
    !!sourceTopic &&
    sourceTopic === targetTopic;

  const needsSubChoice = !!subs && subs.length > 1 && !sourceSub;
  const noSubs = !!subs && subs.length === 0;

  const canRun =
    !!sourceEnv &&
    !!targetEnv &&
    !!sourceTopic &&
    !!targetTopic &&
    !sameTopic &&
    !!subs &&
    subs.length > 0 &&
    !needsSubChoice &&
    !running;

  const doTransfer = useCallback(
    async (dryRun: boolean) => {
      if (!sourceEnv || !targetEnv) return;
      const body: TransferRequest = {
        sourceEnv: sourceEnv.name,
        targetEnv: targetEnv.name,
        sourceTopicId: sourceTopic,
        targetTopicId: targetTopic,
        sourceSubscriptionId: sourceSub || undefined,
        max,
        dryRun,
      };
      setRunning(true);
      try {
        const data = await api<TransferResult>("/api/transfer/topic", { method: "POST", body });
        setResult(data);
        if (!dryRun) {
          toast(`Copied ${data.published}/${data.read} message(s) to ${data.targetEnv}`, "success");
        }
      } catch (e) {
        toast((e as Error).message, "error", dryRun ? "Preview failed" : "Transfer failed");
      } finally {
        setRunning(false);
      }
    },
    [sourceEnv, targetEnv, sourceTopic, targetTopic, sourceSub, max, toast]
  );

  const confirmTransfer = () =>
    confirm({
      title: "Copy messages between environments",
      confirmLabel: "Transfer",
      busyMessage: "Copying messages…",
      body: (
        <Typography variant="body2" color="text.secondary">
          Copy up to {max} unacknowledged message(s) from <b>{sourceEnvName}</b> · <code>{sourceTopic}</code> to{" "}
          <b>{targetEnvName}</b> · <code>{targetTopic}</code>? The source backlog is read non-destructively (peek)
          and left untouched; matching messages are published to the target topic. Repeating a transfer may create
          duplicates on the target.
        </Typography>
      ),
      onConfirm: async () => {
        await doTransfer(false);
      },
    });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, p: 2, gap: 2 }}>
      <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
        <Stack direction="row" alignItems="center" spacing={1.5} sx={{ mb: 1.5 }}>
          <Box sx={{ width: 34, height: 34, borderRadius: 2, bgcolor: ACCENT, color: "#fff", display: "grid", placeItems: "center" }}>
            <SyncAltIcon fontSize="small" />
          </Box>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 800, lineHeight: 1.1 }}>
              Message Transfer — between environments
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Copy a topic's unacknowledged messages to the same topic in another environment (e.g. Dev to QA).
              Non-destructive peek on the source; Prod is excluded.
            </Typography>
          </Box>
        </Stack>

        <Stack direction="row" spacing={1.5} rowGap={1.5} alignItems="flex-start" flexWrap="wrap" useFlexGap>
          <FormControl size="small" sx={{ minWidth: 130 }}>
            <InputLabel>Source env</InputLabel>
            <Select label="Source env" value={sourceEnvName} onChange={(e) => { setSourceEnvName(String(e.target.value)); setSourceTopic(""); setResult(null); }}>
              {envs.map((e) => (
                <MenuItem key={e.name} value={e.name}>{e.name}</MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 300 }}>
            <InputLabel>Source topic</InputLabel>
            <Select label="Source topic" value={sourceTopic} onChange={(e) => { setSourceTopic(String(e.target.value)); setResult(null); }}>
              {topicMenuItems(sourceEnv)}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 260 }} disabled={!subs || subs.length <= 1} error={needsSubChoice}>
            <InputLabel>Source subscription</InputLabel>
            <Select
              label="Source subscription"
              value={sourceSub}
              onChange={(e) => setSourceSub(String(e.target.value))}
              renderValue={(v) =>
                subsLoading ? "Loading…" : v || (subs && subs.length === 1 ? subs[0].id : "Select…")
              }
            >
              {(subs || []).map((s) => (
                <MenuItem key={s.id} value={s.id} sx={{ fontFamily: "monospace", fontSize: 12 }}>{s.id}</MenuItem>
              ))}
            </Select>
          </FormControl>
        </Stack>

        <Divider sx={{ my: 1.75 }}>
          <Chip icon={<SyncAltIcon />} label="to" size="small" sx={{ fontWeight: 700 }} />
        </Divider>

        <Stack direction="row" spacing={1.5} rowGap={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <FormControl size="small" sx={{ minWidth: 130 }}>
            <InputLabel>Target env</InputLabel>
            <Select label="Target env" value={targetEnvName} onChange={(e) => { setTargetEnvName(String(e.target.value)); setResult(null); }}>
              {envs.map((e) => (
                <MenuItem key={e.name} value={e.name}>{e.name}</MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 300 }} error={sameTopic}>
            <InputLabel>Target topic</InputLabel>
            <Select label="Target topic" value={targetTopic} onChange={(e) => setTargetTopic(String(e.target.value))}>
              {topicMenuItems(targetEnv)}
            </Select>
          </FormControl>

          <TextField
            size="small"
            label="Max messages"
            type="number"
            value={max}
            onChange={(e) => setMax(Math.max(1, Math.min(1000, Number(e.target.value) || 1)))}
            sx={{ width: 130 }}
            inputProps={{ min: 1, max: 1000 }}
          />

          <Button
            variant="outlined"
            startIcon={running ? <CircularProgress size={16} /> : <VisibilityIcon />}
            disabled={!canRun}
            onClick={() => void doTransfer(true)}
          >
            Preview
          </Button>
          <Button
            variant="contained"
            startIcon={<SendIcon />}
            disabled={!canRun}
            onClick={confirmTransfer}
            sx={{ bgcolor: ACCENT, "&:hover": { bgcolor: "#4338ca" } }}
          >
            Transfer
          </Button>
        </Stack>

        {noSubs && (
          <Alert severity="warning" sx={{ mt: 1.5 }}>
            The source topic has no subscription, so it has no unacknowledged backlog to copy.
          </Alert>
        )}
        {needsSubChoice && (
          <Alert severity="info" sx={{ mt: 1.5 }}>
            This topic has multiple subscriptions — pick the one whose backlog you want to copy.
          </Alert>
        )}
        {sameTopic && (
          <Alert severity="error" sx={{ mt: 1.5 }}>
            Source and target resolve to the same topic. Choose a different target environment or topic.
          </Alert>
        )}
      </Paper>

      <Box sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
        {!result ? (
          <Paper variant="outlined" sx={{ p: 4, borderRadius: 3, textAlign: "center", color: "text.secondary" }}>
            <SyncAltIcon sx={{ fontSize: 40, color: "#cbd5e1", mb: 1 }} />
            <Typography variant="body2">
              Choose a source topic and target environment, then Preview to see what would copy, or Transfer to copy.
            </Typography>
          </Paper>
        ) : (
          <Stack spacing={1.5}>
            <Paper variant="outlined" sx={{ p: 1.75, borderRadius: 3 }}>
              <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
                <Chip
                  label={result.dryRun ? "Preview (dry run)" : "Transferred"}
                  size="small"
                  sx={{ fontWeight: 800, bgcolor: result.dryRun ? "#eef2ff" : "#e7f6ec", color: result.dryRun ? ACCENT : "#0f7b3f" }}
                />
                <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
                  {result.sourceEnv}·{result.sourceTopicId} → {result.targetEnv}·{result.targetTopicId}
                </Typography>
                <Box sx={{ flex: 1 }} />
                <Typography variant="caption" color="text.secondary">
                  read {result.read} · published {result.published} · failed {result.failed} · sub {result.sourceSubscriptionId}
                </Typography>
              </Stack>
              {result.dryRun && result.read > 0 && (
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
                  Nothing was published. Click Transfer to copy these {result.read} message(s).
                </Typography>
              )}
              {result.errors.length > 0 && (
                <Alert severity="error" sx={{ mt: 1 }}>
                  {result.errors.map((er, i) => (
                    <div key={i}>{er}</div>
                  ))}
                </Alert>
              )}
            </Paper>

            {result.messages.length > 0 && (
              <Box>
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 0.5 }}>
                  Preview of peeked messages (up to {result.messages.length} shown){result.read > result.messages.length ? ` of ${result.read}` : ""}:
                </Typography>
                {result.messages.map((m, i) => (
                  <MessageCard key={`${m.messageId}-${i}`} message={m} />
                ))}
              </Box>
            )}
          </Stack>
        )}
      </Box>
    </Box>
  );
}

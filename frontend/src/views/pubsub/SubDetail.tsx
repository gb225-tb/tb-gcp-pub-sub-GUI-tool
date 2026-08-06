import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import RefreshIcon from "@mui/icons-material/Refresh";
import DeleteForeverIcon from "@mui/icons-material/DeleteForever";
import VisibilityIcon from "@mui/icons-material/Visibility";
import { api } from "../../api/client";
import type { MessageView, SubscriptionCounts, SubscriptionInfo } from "../../api/types";
import { useUi } from "../../app/UiProvider";
import { Section } from "../../components/Section";
import { CountCards } from "./CountCards";
import { LiveTail } from "./LiveTail";
import { MessageCard } from "./MessageCard";

interface Props {
  subId: string;
  sub?: SubscriptionInfo;
  onGoToTopic: (topicId: string) => void;
}

function Meta({ k, v, onClick }: { k: string; v: string; onClick?: () => void }) {
  return (
    <Paper variant="outlined" sx={{ p: 1.25, borderRadius: 2, minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary" display="block">
        {k}
      </Typography>
      {onClick ? (
        <Link component="button" underline="hover" onClick={onClick} sx={{ textAlign: "left" }}>
          {v}
        </Link>
      ) : (
        <Typography variant="body2" noWrap title={v}>
          {v}
        </Typography>
      )}
    </Paper>
  );
}

export function SubDetail({ subId, sub, onGoToTopic }: Props) {
  const { withBusy, toast, confirm } = useUi();
  const [counts, setCounts] = useState<SubscriptionCounts | null>(null);
  const [countsError, setCountsError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [max, setMax] = useState(10);
  const [messages, setMessages] = useState<MessageView[] | null>(null);
  const [msgHint, setMsgHint] = useState("Peek messages to view them here (non-destructive).");

  useEffect(() => {
    let cancelled = false;
    setCounts(null);
    setCountsError(null);
    (async () => {
      try {
        const c = await api<SubscriptionCounts>(`/api/subscriptions/${encodeURIComponent(subId)}/counts`);
        if (!cancelled) setCounts(c);
      } catch (e) {
        if (!cancelled) setCountsError((e as Error).message);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [subId, reloadKey]);

  const doPeek = (n: number) =>
    withBusy(`Peeking messages from ${subId}…`, async () => {
      try {
        const msgs = await api<MessageView[]>(`/api/subscriptions/${encodeURIComponent(subId)}/peek`, {
          method: "POST",
          params: { max: String(n || 10) },
        });
        setMessages(msgs || []);
        setMsgHint(
          msgs && msgs.length
            ? `${msgs.length} message(s) peeked (not consumed).`
            : "No messages currently available on this subscription."
        );
      } catch (e) {
        setMessages([]);
        setMsgHint((e as Error).message);
        toast((e as Error).message, "error", "Peek failed");
      }
    });

  const purge = () =>
    confirm({
      title: "Purge subscription",
      danger: true,
      confirmLabel: "Purge",
      busyMessage: `Purging ${subId}… (draining backlog)`,
      body: (
        <Typography variant="body2" color="text.secondary">
          Drain and discard ALL messages from "{subId}"? This acknowledges every message, so any consumers
          sharing this subscription will NOT receive them. This cannot be undone.
        </Typography>
      ),
      onConfirm: async () => {
        const res = await api<{ purged: number }>(`/api/subscriptions/${encodeURIComponent(subId)}/purge`, {
          method: "POST",
        });
        toast(`Purged ${res.purged} message(s) from "${subId}"`, "success");
      },
    });

  return (
    <Stack spacing={2}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
        <Stack direction="row" alignItems="center" spacing={1.5} sx={{ minWidth: 0 }}>
          <Chip label="Subscription" color="secondary" size="small" />
          <Typography variant="h6" noWrap title={subId}>
            {subId}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1}>
          <Button startIcon={<RefreshIcon />} onClick={() => setReloadKey((k) => k + 1)}>
            Refresh
          </Button>
          <Button color="error" variant="outlined" startIcon={<DeleteForeverIcon />} onClick={purge}>
            Purge
          </Button>
        </Stack>
      </Stack>

      {sub && (
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fill, minmax(170px, 1fr))",
            gap: 1.5,
          }}
        >
          <Meta k="Topic" v={sub.topicId} onClick={() => onGoToTopic(sub.topicId)} />
          <Meta k="Ack deadline" v={`${sub.ackDeadlineSeconds}s`} />
          <Meta k="Delivery" v={sub.hasPush ? "Push" : "Pull"} />
          <Meta k="Retention" v={sub.messageRetentionDuration || "—"} />
          {sub.hasPush && <Meta k="Push endpoint" v={sub.pushEndpoint} />}
        </Box>
      )}

      <Section title="Message counts">
        {countsError ? (
          <Typography variant="body2" color="error">
            Error: {countsError}
          </Typography>
        ) : counts ? (
          <CountCards counts={counts} />
        ) : (
          <CircularProgress size={22} />
        )}
      </Section>

      <Section title="Messages" hint="Peek is non-destructive — messages are not consumed.">
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" sx={{ mb: 1.5 }}>
          <TextField
            label="Max"
            type="number"
            value={max}
            onChange={(e) => setMax(Math.max(1, Math.min(parseInt(e.target.value, 10) || 1, 1000)))}
            sx={{ width: 100 }}
            inputProps={{ min: 1, max: 1000 }}
          />
          <Button variant="contained" startIcon={<VisibilityIcon />} onClick={() => doPeek(max)}>
            View messages (peek)
          </Button>
          <Button onClick={() => doPeek(1)}>View latest</Button>
        </Stack>
        {messages === null ? (
          <Typography variant="body2" color="text.secondary">
            {msgHint}
          </Typography>
        ) : messages.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {msgHint}
          </Typography>
        ) : (
          <>
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 1 }}>
              {msgHint}
            </Typography>
            {messages.map((m, i) => (
              <MessageCard key={`${m.messageId}-${i}`} message={m} />
            ))}
          </>
        )}
      </Section>

      <Section title="Live tail">
        <LiveTail
          path={`api/subscriptions/${encodeURIComponent(subId)}/tail`}
          name={subId}
          title="Live tail"
          hint="Observes this subscription in real time and releases every message (no ACK). If a consumer (e.g. Dataflow) is actively draining it, messages may not appear here — tail the whole topic from the topic view instead."
        />
      </Section>

      <Box sx={{ height: 8 }} />
    </Stack>
  );
}

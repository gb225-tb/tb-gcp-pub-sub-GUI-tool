import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import RefreshIcon from "@mui/icons-material/Refresh";
import DeleteForeverIcon from "@mui/icons-material/DeleteForever";
import { api } from "../../api/client";
import type { MessageView, SubscriptionInfo, TopicCounts } from "../../api/types";
import { useUi } from "../../app/UiProvider";
import { Section } from "../../components/Section";
import { CountCards } from "./CountCards";
import { PublishPanel } from "./PublishPanel";
import { LiveTail } from "./LiveTail";
import { MessageCard } from "./MessageCard";

interface Props {
  topicId: string;
  onSelectSub: (sub: SubscriptionInfo) => void;
}

export function TopicDetail({ topicId, onSelectSub }: Props) {
  const { withBusy, toast, confirm } = useUi();
  const [counts, setCounts] = useState<TopicCounts | null>(null);
  const [countsError, setCountsError] = useState<string | null>(null);
  const [subs, setSubs] = useState<SubscriptionInfo[] | null>(null);
  const [subsError, setSubsError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [latest, setLatest] = useState<{ subId: string; messages: MessageView[] } | null>(null);

  useEffect(() => {
    let cancelled = false;
    setCounts(null);
    setCountsError(null);
    setSubs(null);
    setSubsError(null);
    (async () => {
      try {
        const c = await api<TopicCounts>(`/api/topics/${encodeURIComponent(topicId)}/counts`);
        if (!cancelled) setCounts(c);
      } catch (e) {
        if (!cancelled) setCountsError((e as Error).message);
      }
      try {
        const s = await api<SubscriptionInfo[]>(`/api/topics/${encodeURIComponent(topicId)}/subscriptions`);
        if (!cancelled) setSubs(s);
      } catch (e) {
        if (!cancelled) setSubsError((e as Error).message);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [topicId, reloadKey]);

  const viewLatest = useCallback(
    (subId: string) =>
      withBusy(`Fetching latest message from ${subId}…`, async () => {
        try {
          const msgs = await api<MessageView[]>(`/api/subscriptions/${encodeURIComponent(subId)}/latest`, {
            method: "POST",
          });
          setLatest({ subId, messages: msgs || [] });
        } catch (e) {
          toast((e as Error).message, "error", "View failed");
        }
      }),
    [withBusy, toast]
  );

  const purgeSub = (subId: string) =>
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

  const purgeTopic = () =>
    confirm({
      title: "Purge all subscriptions",
      danger: true,
      confirmLabel: "Purge",
      busyMessage: `Purging all subscriptions on ${topicId}…`,
      body: (
        <Typography variant="body2" color="text.secondary">
          Drain and discard ALL messages from EVERY subscription on topic "{topicId}"? Consumers on those
          subscriptions will NOT receive the discarded messages. This cannot be undone.
        </Typography>
      ),
      onConfirm: async () => {
        const res = await api<{ totalPurged: number; perSubscription: Record<string, number> }>(
          `/api/topics/${encodeURIComponent(topicId)}/purge`,
          { method: "POST" }
        );
        toast(
          `Purged ${res.totalPurged} message(s) across ${Object.keys(res.perSubscription || {}).length} subscription(s)`,
          "success"
        );
        setReloadKey((k) => k + 1);
      },
    });

  const countById: Record<string, TopicCounts["subscriptions"][number]> = {};
  counts?.subscriptions?.forEach((c) => (countById[c.subscriptionId] = c));

  return (
    <Stack spacing={2}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
        <Stack direction="row" alignItems="center" spacing={1.5} sx={{ minWidth: 0 }}>
          <Chip label="Topic" color="primary" size="small" />
          <Typography variant="h6" noWrap title={topicId}>
            {topicId}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1}>
          <Button startIcon={<RefreshIcon />} onClick={() => setReloadKey((k) => k + 1)}>
            Refresh
          </Button>
          <Button color="error" variant="outlined" startIcon={<DeleteForeverIcon />} onClick={purgeTopic}>
            Purge all
          </Button>
        </Stack>
      </Stack>

      <Section title="Message counts (all subscriptions)">
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

      <Section title="Subscriptions">
        {subsError ? (
          <Typography variant="body2" color="error">
            Error: {subsError}
          </Typography>
        ) : !subs ? (
          <CircularProgress size={22} />
        ) : subs.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No subscriptions on this topic — there are no messages to view or count.
          </Typography>
        ) : (
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Subscription</TableCell>
                <TableCell align="right">Total</TableCell>
                <TableCell align="right">ACK (24h)</TableCell>
                <TableCell align="right">Non-ACK</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {subs.map((s) => {
                const c = countById[s.id];
                return (
                  <TableRow key={s.id} hover>
                    <TableCell>
                      <Link component="button" underline="hover" onClick={() => onSelectSub(s)}>
                        {s.id}
                      </Link>
                    </TableCell>
                    <TableCell align="right" className="mono">
                      {c && c.available ? c.total : "—"}
                    </TableCell>
                    <TableCell align="right" className="mono" sx={{ color: "success.main" }}>
                      {c && c.available ? c.ack : "—"}
                    </TableCell>
                    <TableCell align="right" className="mono" sx={{ color: "warning.main" }}>
                      {c && c.available ? c.nonAck : "—"}
                    </TableCell>
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        <Button onClick={() => viewLatest(s.id)}>View latest</Button>
                        <Button color="error" onClick={() => purgeSub(s.id)}>
                          Purge
                        </Button>
                      </Stack>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </Section>

      <Section
        title="Publish a message"
        hint="Send a message to this topic — start the live tail below to watch it arrive."
      >
        <PublishPanel topicId={topicId} />
      </Section>

      <Section
        title="Live tail — whole topic"
        hint="Creates a temporary subscription (auto-deleted on stop). Sees every published message even when other subscriptions are actively consumed (e.g. by Dataflow)."
      >
        <LiveTail
          path={`api/topics/${encodeURIComponent(topicId)}/tail`}
          name={topicId}
          title="Live tail (new subscription)"
          hint="A dedicated temporary subscription receives its own copy of every message published to this topic, so nothing is taken from real consumers."
        />
      </Section>

      <Section
        title="Live tail — per existing subscription"
        hint="Observes each existing subscription without ACK (messages released). A subscription actively drained by its consumer may show little or nothing here — use the whole-topic tail above instead."
      >
        {!subs ? (
          <CircularProgress size={22} />
        ) : subs.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No subscriptions to tail on this topic.
          </Typography>
        ) : (
          <Stack spacing={1.5}>
            {subs.map((s) => (
              <LiveTail
                key={s.id}
                path={`api/subscriptions/${encodeURIComponent(s.id)}/tail`}
                name={s.id}
                mono={s.id}
                compact
              />
            ))}
          </Stack>
        )}
      </Section>

      <Dialog open={!!latest} onClose={() => setLatest(null)} maxWidth="md" fullWidth>
        <DialogTitle>Latest message · {latest?.subId}</DialogTitle>
        <DialogContent dividers>
          {latest && latest.messages.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              No messages currently available on this subscription.
            </Typography>
          ) : (
            <>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                Non-destructive peek — the message stays in the subscription.
              </Typography>
              {latest?.messages.map((m, i) => (
                <MessageCard key={`${m.messageId}-${i}`} message={m} defaultExpanded />
              ))}
            </>
          )}
        </DialogContent>
      </Dialog>

      <Box sx={{ height: 8 }} />
    </Stack>
  );
}

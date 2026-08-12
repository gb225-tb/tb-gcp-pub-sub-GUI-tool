import { useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import StopIcon from "@mui/icons-material/Stop";
import FiberManualRecordIcon from "@mui/icons-material/FiberManualRecord";
import DownloadIcon from "@mui/icons-material/Download";
import { apiUrl } from "../../api/client";
import type { MessageView } from "../../api/types";
import { useUi } from "../../app/UiProvider";
import { MessageCard } from "./MessageCard";
import { downloadMessages } from "./download";

interface Props {
  path: string;
  name: string;
  title?: string;
  mono?: string;
  hint?: string;
  compact?: boolean;
}

const MAX_MESSAGES = 200;

export function LiveTail({ path, name, title, mono, hint, compact }: Props) {
  const { toast } = useUi();
  const esRef = useRef<EventSource | null>(null);
  const [messages, setMessages] = useState<MessageView[]>([]);
  const [status, setStatus] = useState<"idle" | "connecting" | "live" | "stopped">("idle");

  const stop = () => {
    if (esRef.current) {
      try {
        esRef.current.close();
      } catch {
        /* ignore */
      }
      esRef.current = null;
    }
    setStatus((s) => (s === "idle" ? "idle" : "stopped"));
  };

  useEffect(() => {
    // Stop and reset when the target changes or on unmount.
    stop();
    setMessages([]);
    setStatus("idle");
    return stop;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  const start = () => {
    stop();
    setMessages([]);
    setStatus("connecting");
    const url = apiUrl(path);
    const es = new EventSource(url.toString());
    esRef.current = es;
    es.onopen = () => setStatus("live");
    es.onmessage = (e) => {
      if (!e.data) return;
      let m: MessageView;
      try {
        m = JSON.parse(e.data);
      } catch {
        return;
      }
      setMessages((prev) => [m, ...prev].slice(0, MAX_MESSAGES));
    };
    es.onerror = () => {
      if (esRef.current === es) {
        toast(`Live tail disconnected${name ? ` (${name})` : ""}`, "error");
        stop();
      }
    };
  };

  const running = status === "connecting" || status === "live";
  const statusText =
    status === "live"
      ? "Live — streaming messages."
      : status === "connecting"
        ? "Connecting…"
        : status === "stopped"
          ? "Stopped."
          : "Not listening.";

  return (
    <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 2 }}>
      <Stack
        direction="row"
        alignItems="center"
        justifyContent="space-between"
        spacing={1}
        flexWrap="wrap"
      >
        <Stack direction="row" alignItems="center" spacing={1} sx={{ minWidth: 0 }}>
          {mono ? (
            <Typography variant="body2" className="mono" noWrap>
              {mono}
            </Typography>
          ) : (
            <Typography variant="subtitle2">{title || "Live tail"}</Typography>
          )}
          {status === "live" && (
            <FiberManualRecordIcon sx={{ color: "success.main", fontSize: 12 }} />
          )}
          <Typography variant="caption" color="text.secondary">
            {statusText}
          </Typography>
        </Stack>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Chip label={`${messages.length} received`} size="small" variant="outlined" />
          <Button
            startIcon={<DownloadIcon />}
            onClick={() => downloadMessages(messages, name)}
            disabled={messages.length === 0}
          >
            Download
          </Button>
          <Button
            color="success"
            variant="contained"
            startIcon={<PlayArrowIcon />}
            onClick={start}
            disabled={running}
          >
            Start
          </Button>
          <Button startIcon={<StopIcon />} onClick={stop} disabled={!running}>
            Stop
          </Button>
        </Stack>
      </Stack>

      {!compact && hint && (
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
          {hint}
        </Typography>
      )}

      <Box className="tail-scroll" sx={{ mt: 1.5, maxHeight: 360, overflow: "auto" }}>
        {messages.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {running ? "Listening for messages…" : "Start the live tail to stream messages in real time."}
          </Typography>
        ) : (
          messages.map((m, i) => <MessageCard key={`${m.messageId}-${i}`} message={m} />)
        )}
      </Box>
    </Paper>
  );
}

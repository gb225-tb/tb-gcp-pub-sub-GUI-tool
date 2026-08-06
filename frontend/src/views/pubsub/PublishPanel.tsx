import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import IconButton from "@mui/material/IconButton";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import AddIcon from "@mui/icons-material/Add";
import CloseIcon from "@mui/icons-material/Close";
import SendIcon from "@mui/icons-material/Send";
import { api } from "../../api/client";
import type { PublishMessageRequest } from "../../api/types";
import { useUi } from "../../app/UiProvider";

interface AttrRow {
  key: string;
  value: string;
}

export function PublishPanel({ topicId }: { topicId: string }) {
  const { withBusy, toast } = useUi();
  const [data, setData] = useState("");
  const [orderingKey, setOrderingKey] = useState("");
  const [attrs, setAttrs] = useState<AttrRow[]>([]);
  const [burst, setBurst] = useState(1);

  const collect = (): PublishMessageRequest => {
    const attributes: Record<string, string> = {};
    attrs.forEach((a) => {
      const key = a.key.trim();
      if (key) attributes[key] = a.value;
    });
    return { data, attributes, orderingKey: orderingKey.trim() || null };
  };

  const doPublish = (times: number) =>
    withBusy(`Publishing ${times > 1 ? `${times} messages` : "message"} to ${topicId}…`, async () => {
      const body = collect();
      let last: { messageId: string } | undefined;
      try {
        for (let i = 0; i < times; i += 1) {
          last = await api(`/api/topics/${encodeURIComponent(topicId)}/publish`, { method: "POST", body });
        }
        toast(
          times > 1
            ? `Published ${times} messages (last id ${last?.messageId}).`
            : `Published · message id ${last?.messageId}.`,
          "success"
        );
      } catch (e) {
        toast((e as Error).message, "error", "Publish failed");
      }
    });

  return (
    <Stack spacing={2}>
      <TextField
        label="Data"
        placeholder="Message body (plain text or JSON)…"
        value={data}
        onChange={(e) => setData(e.target.value)}
        multiline
        minRows={3}
        fullWidth
      />

      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap", alignItems: "flex-start" }}>
        <Box sx={{ flex: 1, minWidth: 260 }}>
          <Typography variant="caption" color="text.secondary">
            Attributes
          </Typography>
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {attrs.map((a, i) => (
              <Stack key={i} direction="row" spacing={1} alignItems="center">
                <TextField
                  placeholder="key"
                  value={a.key}
                  onChange={(e) =>
                    setAttrs((prev) => prev.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))
                  }
                  sx={{ flex: 1 }}
                />
                <TextField
                  placeholder="value"
                  value={a.value}
                  onChange={(e) =>
                    setAttrs((prev) => prev.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))
                  }
                  sx={{ flex: 1 }}
                />
                <IconButton onClick={() => setAttrs((prev) => prev.filter((_, j) => j !== i))}>
                  <CloseIcon fontSize="small" />
                </IconButton>
              </Stack>
            ))}
            <Button
              startIcon={<AddIcon />}
              onClick={() => setAttrs((prev) => [...prev, { key: "", value: "" }])}
              sx={{ alignSelf: "flex-start" }}
            >
              Add attribute
            </Button>
          </Stack>
        </Box>

        <TextField
          label="Ordering key"
          placeholder="(optional)"
          value={orderingKey}
          onChange={(e) => setOrderingKey(e.target.value)}
          sx={{ width: 220 }}
        />
      </Box>

      <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap">
        <Button variant="contained" startIcon={<SendIcon />} onClick={() => doPublish(1)}>
          Publish message
        </Button>
        <Box sx={{ flex: 1 }} />
        <Typography variant="caption" color="text.secondary">
          Burst
        </Typography>
        <TextField
          type="number"
          value={burst}
          onChange={(e) => setBurst(Math.max(1, Math.min(parseInt(e.target.value, 10) || 1, 100)))}
          sx={{ width: 88 }}
          inputProps={{ min: 1, max: 100 }}
        />
        <Button variant="outlined" onClick={() => doPublish(burst)}>
          Publish ×N
        </Button>
      </Stack>
    </Stack>
  );
}

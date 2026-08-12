import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import IconButton from "@mui/material/IconButton";
import Stack from "@mui/material/Stack";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import DownloadIcon from "@mui/icons-material/Download";
import type { MessageView } from "../../api/types";
import { downloadMessage } from "./download";

function Meta({ k, v }: { k: string; v: string }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary" display="block">
        {k}
      </Typography>
      <Typography variant="body2" className="mono" noWrap title={v}>
        {v}
      </Typography>
    </Box>
  );
}

export function MessageCard({ message, defaultExpanded }: { message: MessageView; defaultExpanded?: boolean }) {
  const attrs = Object.entries(message.attributes || {});
  const preview = (message.data || "(empty)").replace(/\s+/g, " ").slice(0, 120);

  return (
    <Accordion
      defaultExpanded={defaultExpanded}
      disableGutters
      variant="outlined"
      sx={{ borderRadius: 2, "&:before": { display: "none" }, mb: 1 }}
    >
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ minWidth: 0, width: "100%" }}>
          <Chip label={`#${message.messageId || "?"}`} size="small" className="mono" />
          <Typography variant="body2" color="text.secondary" noWrap sx={{ flex: 1 }}>
            {preview}
          </Typography>
          <Tooltip title="Download this message (JSON, non-destructive — no ACK)">
            <IconButton
              size="small"
              component="span"
              onClick={(e) => {
                e.stopPropagation();
                downloadMessage(message);
              }}
              onFocus={(e) => e.stopPropagation()}
            >
              <DownloadIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        </Stack>
      </AccordionSummary>
      <AccordionDetails>
        <Typography variant="caption" color="text.secondary" display="block">
          Data
        </Typography>
        <Box
          className="mono"
          sx={{
            whiteSpace: "pre-wrap",
            wordBreak: "break-word",
            bgcolor: "action.hover",
            borderRadius: 1.5,
            p: 1.25,
            mt: 0.5,
            fontSize: 12,
            maxHeight: 300,
            overflow: "auto",
          }}
        >
          {message.data || "(empty)"}
        </Box>

        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fill, minmax(150px, 1fr))",
            gap: 1.5,
            mt: 1.5,
          }}
        >
          <Meta k="Message ID" v={message.messageId || "—"} />
          <Meta k="Publish time" v={message.publishTime || "—"} />
          <Meta k="Delivery attempt" v={String(message.deliveryAttempt || 0)} />
          {message.orderingKey && <Meta k="Ordering key" v={message.orderingKey} />}
          {message.source && <Meta k="Observed on" v={message.source} />}
        </Box>

        {attrs.length > 0 && (
          <Box sx={{ mt: 1.5 }}>
            <Typography variant="caption" color="text.secondary">
              Attributes
            </Typography>
            <Stack direction="row" flexWrap="wrap" gap={0.75} sx={{ mt: 0.5 }}>
              {attrs.map(([k, v]) => (
                <Chip key={k} size="small" variant="outlined" label={`${k}: ${v}`} />
              ))}
            </Stack>
          </Box>
        )}
      </AccordionDetails>
    </Accordion>
  );
}

import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";

interface CountsLike {
  total: number;
  ack: number;
  nonAck: number;
  available: boolean;
  note?: string | null;
}

function Card({ label, value, accent }: { label: string; value: number | null; accent?: string }) {
  return (
    <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 2, flex: 1, minWidth: 120 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, color: accent || "text.primary" }}>
        {value === null ? "—" : value.toLocaleString()}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
    </Paper>
  );
}

export function CountCards({ counts }: { counts: CountsLike | null }) {
  if (!counts || !counts.available) {
    return (
      <Box>
        <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap" }}>
          <Card label="Total" value={null} />
          <Card label="ACK · consumed (24h)" value={null} accent="#1aa564" />
          <Card label="Non-ACK · pending" value={null} accent="#c9760a" />
        </Box>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: "block" }}>
          {counts?.note || "Counts unavailable."}
        </Typography>
      </Box>
    );
  }
  return (
    <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap" }}>
      <Card label="Total" value={counts.total} />
      <Card label="ACK · consumed (24h)" value={counts.ack} accent="#1aa564" />
      <Card label="Non-ACK · pending" value={counts.nonAck} accent="#c9760a" />
    </Box>
  );
}

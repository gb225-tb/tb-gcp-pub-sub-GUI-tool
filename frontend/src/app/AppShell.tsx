import { useEffect, useState, type ReactNode } from "react";
import { alpha } from "@mui/material/styles";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import IconButton from "@mui/material/IconButton";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Toolbar from "@mui/material/Toolbar";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import ButtonBase from "@mui/material/ButtonBase";
import RefreshIcon from "@mui/icons-material/Refresh";
import PodcastsIcon from "@mui/icons-material/Podcasts";
import CompareArrowsIcon from "@mui/icons-material/CompareArrows";
import UploadFileIcon from "@mui/icons-material/UploadFile";
import DeleteSweepIcon from "@mui/icons-material/DeleteSweep";
import HubIcon from "@mui/icons-material/Hub";

import { useAppState } from "./AppState";
import { PubSubView } from "../views/pubsub/PubSubView";
import { MongoView } from "../views/mongo/MongoView";
import { BulkView } from "../views/bulk/BulkView";
import { CleanupView } from "../views/cleanup/CleanupView";

export type ViewId = "pubsub" | "mongo" | "bulk" | "cleanup";

interface NavItem {
  id: ViewId;
  label: string;
  icon: ReactNode;
  /** Accent color for the icon / active pill. */
  color: string;
}

const NAV: NavItem[] = [
  { id: "pubsub", label: "Pub/Sub", icon: <PodcastsIcon />, color: "#2f6bff" },
  { id: "mongo", label: "Compare", icon: <CompareArrowsIcon />, color: "#1aa564" },
  { id: "bulk", label: "Bulk Post", icon: <UploadFileIcon />, color: "#7b5bff" },
  { id: "cleanup", label: "Cleanup", icon: <DeleteSweepIcon />, color: "#e0413f" },
];

const RAIL_WIDTH = 92;

export function AppShell() {
  const { project, setProject, reload, emulator, emulatorHost, environments } = useAppState();
  const [view, setView] = useState<ViewId>("pubsub");
  const [projectDraft, setProjectDraft] = useState(project);

  // Keep the editable draft in sync when the project changes elsewhere
  // (e.g. switching environments in the Pub/Sub tab).
  useEffect(() => {
    setProjectDraft(project);
  }, [project]);

  const envDriven = environments.length > 0;
  const showProject = view === "pubsub" || view === "bulk";

  const commitProject = () => {
    if (projectDraft.trim() !== project) setProject(projectDraft);
  };

  return (
    <Box sx={{ height: "100%", display: "flex", flexDirection: "column", bgcolor: "background.default" }}>
      <AppBar position="static">
        <Toolbar variant="dense" sx={{ gap: 2 }}>
          <Stack direction="row" spacing={1.25} alignItems="center" sx={{ minWidth: 0 }}>
            <Box
              sx={{
                width: 34,
                height: 34,
                borderRadius: 2,
                bgcolor: "primary.main",
                color: "#fff",
                display: "grid",
                placeItems: "center",
                flexShrink: 0,
              }}
            >
              <HubIcon fontSize="small" />
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="subtitle2" noWrap sx={{ lineHeight: 1.1 }}>
                Catalog Tools
              </Typography>
              <Typography variant="caption" color="text.secondary" noWrap>
                Pub/Sub · Mongo · Bulk
              </Typography>
            </Box>
          </Stack>

          <Box sx={{ flex: 1 }} />

          {showProject &&
            (envDriven ? (
              <Chip
                label={`project · ${project || "—"}`}
                variant="outlined"
                size="small"
                sx={{ fontFamily: "monospace", maxWidth: 320 }}
              />
            ) : (
              <TextField
                label="Project"
                value={projectDraft}
                onChange={(e) => setProjectDraft(e.target.value)}
                onBlur={commitProject}
                onKeyDown={(e) => {
                  if (e.key === "Enter") commitProject();
                }}
                sx={{ width: 240 }}
              />
            ))}

          <Chip
            label={emulator ? `EMULATOR · ${emulatorHost}` : "REAL GCP"}
            color={emulator ? "warning" : "success"}
            variant="outlined"
            size="small"
          />

          <Tooltip title="Reload topics">
            <IconButton onClick={() => void reload()} color="primary">
              <RefreshIcon />
            </IconButton>
          </Tooltip>
        </Toolbar>
      </AppBar>

      <Box sx={{ flex: 1, display: "flex", minHeight: 0 }}>
        <Paper
          square
          elevation={0}
          sx={{
            width: RAIL_WIDTH,
            flexShrink: 0,
            borderRight: "1px solid",
            borderColor: "divider",
            py: 1.5,
            display: "flex",
            flexDirection: "column",
            gap: 0.5,
            alignItems: "center",
          }}
        >
          {NAV.map((item) => {
            const active = view === item.id;
            return (
              <ButtonBase
                key={item.id}
                onClick={() => setView(item.id)}
                sx={{
                  width: RAIL_WIDTH - 16,
                  py: 1,
                  borderRadius: 3,
                  display: "flex",
                  flexDirection: "column",
                  gap: 0.5,
                  color: active ? item.color : "text.secondary",
                  fontWeight: active ? 700 : 500,
                  transition: "color .15s",
                  "&:hover .nav-pill": {
                    bgcolor: active ? item.color : alpha(item.color, 0.16),
                  },
                }}
              >
                <Box
                  className="nav-pill"
                  sx={{
                    px: 2,
                    py: 0.5,
                    borderRadius: 999,
                    display: "grid",
                    placeItems: "center",
                    bgcolor: active ? item.color : alpha(item.color, 0.1),
                    color: active ? "#fff" : item.color,
                    boxShadow: active ? `0 4px 12px ${alpha(item.color, 0.35)}` : "none",
                    transition: "background-color .15s, box-shadow .15s",
                  }}
                >
                  {item.icon}
                </Box>
                <Typography variant="caption" sx={{ fontWeight: "inherit" }}>
                  {item.label}
                </Typography>
              </ButtonBase>
            );
          })}
        </Paper>

        <Box sx={{ flex: 1, minWidth: 0, minHeight: 0, display: "flex", flexDirection: "column" }}>
          {view === "pubsub" && <PubSubView />}
          {view === "mongo" && <MongoView />}
          {view === "bulk" && <BulkView />}
          {view === "cleanup" && <CleanupView />}
        </Box>
      </Box>
    </Box>
  );
}

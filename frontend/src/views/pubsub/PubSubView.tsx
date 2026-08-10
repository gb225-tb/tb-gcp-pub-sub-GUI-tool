import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Tab from "@mui/material/Tab";
import Tabs from "@mui/material/Tabs";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import PodcastsIcon from "@mui/icons-material/Podcasts";
import CloudQueueIcon from "@mui/icons-material/CloudQueue";
import ClearIcon from "@mui/icons-material/Clear";
import { useAppState } from "../../app/AppState";
import type { SubscriptionInfo } from "../../api/types";
import { TopicDetail } from "./TopicDetail";
import { SubDetail } from "./SubDetail";

type Selected = { type: "topic"; id: string } | { type: "sub"; id: string } | null;

export function PubSubView() {
  const { groups, environments, activeEnv, setActiveEnv, project } = useAppState();
  const [activeGroup, setActiveGroup] = useState("");
  const [selected, setSelected] = useState<Selected>(null);
  const [subById, setSubById] = useState<Record<string, SubscriptionInfo>>({});

  useEffect(() => {
    if (groups.length && !groups.some((g) => g.name === activeGroup)) {
      setActiveGroup(groups[0].name);
    }
  }, [groups, activeGroup]);

  // Clear any topic/sub selection when the environment changes so a stale
  // (wrong-project) topic detail is never shown.
  useEffect(() => {
    setSelected(null);
  }, [activeEnv]);

  const group = useMemo(() => groups.find((g) => g.name === activeGroup), [groups, activeGroup]);
  const selectedTopic = selected?.type === "topic" ? selected.id : "";

  const goToTopic = (topicId: string) => {
    const owner = groups.find((g) => g.topics.includes(topicId));
    if (owner) setActiveGroup(owner.name);
    setSelected({ type: "topic", id: topicId });
  };

  const selectSub = (sub: SubscriptionInfo) => {
    setSubById((prev) => ({ ...prev, [sub.id]: sub }));
    setSelected({ type: "sub", id: sub.id });
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <Paper
        square
        elevation={0}
        sx={{ borderBottom: "1px solid", borderColor: "divider", px: 2, pt: 1.5, pb: 1.5 }}
      >
        <Stack spacing={1.25}>
          {environments.length > 0 && (
            <Stack direction="row" alignItems="center" spacing={1.5} flexWrap="wrap" useFlexGap>
              <FormControl size="small" sx={{ minWidth: 180 }}>
                <InputLabel id="pubsub-env-label">Environment</InputLabel>
                <Select
                  labelId="pubsub-env-label"
                  label="Environment"
                  value={activeEnv}
                  onChange={(e) => setActiveEnv(String(e.target.value))}
                >
                  {environments.map((env) => (
                    <MenuItem key={env.name} value={env.name}>
                      {env.name}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
              <Chip
                icon={<CloudQueueIcon />}
                label={project || "no project"}
                variant="outlined"
                sx={{ fontFamily: "monospace", maxWidth: 360 }}
              />
              <Box sx={{ flex: 1 }} />
              <Typography variant="caption" color="text.secondary">
                {(group?.topics.length ?? 0)} topics in {activeGroup || "—"}
              </Typography>
            </Stack>
          )}
          {environments.length > 0 && <Divider />}
          <Tabs
            value={group ? activeGroup : false}
            onChange={(_, v) => {
              setActiveGroup(v);
              setSelected(null);
            }}
            variant="scrollable"
            scrollButtons="auto"
            allowScrollButtonsMobile
            sx={{ minHeight: 44 }}
          >
            {groups.map((g) => (
              <Tab
                key={g.name}
                value={g.name}
                sx={{ minHeight: 44, textTransform: "none" }}
                label={
                  <Stack direction="row" alignItems="center" spacing={0.75}>
                    <span>{g.name}</span>
                    <Box
                      component="span"
                      sx={{
                        minWidth: 18,
                        height: 18,
                        px: 0.5,
                        borderRadius: 999,
                        bgcolor: activeGroup === g.name ? "primary.main" : "action.selected",
                        color: activeGroup === g.name ? "primary.contrastText" : "text.secondary",
                        fontSize: 11,
                        fontWeight: 700,
                        lineHeight: "18px",
                        textAlign: "center",
                      }}
                    >
                      {g.topics.length}
                    </Box>
                  </Stack>
                }
              />
            ))}
          </Tabs>
          <Stack direction="row" alignItems="center" spacing={1} sx={{ width: "100%" }}>
            <FormControl size="small" sx={{ flex: 1, maxWidth: 480 }}>
              <InputLabel id="pubsub-topic-label">Topic</InputLabel>
              <Select
                labelId="pubsub-topic-label"
                label="Topic"
                value={group && selectedTopic && group.topics.includes(selectedTopic) ? selectedTopic : ""}
                onChange={(e) => e.target.value && setSelected({ type: "topic", id: String(e.target.value) })}
              >
                <MenuItem value="">
                  <em>Select a topic…</em>
                </MenuItem>
                {(group?.topics || []).map((t) => (
                  <MenuItem key={t} value={t} sx={{ fontFamily: "monospace", fontSize: 12 }}>
                    {t}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <Button
              size="small"
              color="inherit"
              startIcon={<ClearIcon />}
              onClick={() => setSelected(null)}
              disabled={!selected}
            >
              Clear
            </Button>
          </Stack>
        </Stack>
      </Paper>

      <Box sx={{ flex: 1, minHeight: 0, overflow: "auto", p: 2 }}>
        {selected?.type === "topic" ? (
          <TopicDetail topicId={selected.id} onSelectSub={selectSub} />
        ) : selected?.type === "sub" ? (
          <SubDetail subId={selected.id} sub={subById[selected.id]} onGoToTopic={goToTopic} />
        ) : (
          <Stack alignItems="center" justifyContent="center" spacing={1.5} sx={{ height: "100%", color: "text.secondary" }}>
            <PodcastsIcon sx={{ fontSize: 48, opacity: 0.4 }} />
            <Typography variant="body1">
              {environments.length > 0
                ? "Pick an environment, flow group and topic to begin."
                : "Pick a flow group and a topic to begin."}
            </Typography>
            <Typography variant="body2">
              View counts, peek messages, publish test data, and live-tail traffic.
            </Typography>
          </Stack>
        )}
      </Box>
    </Box>
  );
}

import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import CircularProgress from "@mui/material/CircularProgress";
import SwapHorizIcon from "@mui/icons-material/SwapHoriz";
import DeleteForeverIcon from "@mui/icons-material/DeleteForever";
import SearchIcon from "@mui/icons-material/Search";
import { api } from "../../api/client";
import type { MongoConfig, MongoDocumentResponse } from "../../api/types";
import { useUi } from "../../app/UiProvider";
import { computeDiff, type DiffStats } from "./mongoDiff";

type Side = "left" | "right";

interface Doc {
  id: string;
  raw: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  obj: any;
}

interface PanelState {
  env: string;
  db: string;
  coll: string;
  productId: string;
  dbOptions: string[];
  collOptions: string[];
  docs: Doc[];
  selectedIndex: number;
  status: { text: string; kind: "muted" | "ok" | "warn" };
}

const initialPanel: PanelState = {
  env: "",
  db: "",
  coll: "",
  productId: "",
  dbOptions: [],
  collOptions: [],
  docs: [],
  selectedIndex: 0,
  status: { text: "No document loaded.", kind: "muted" },
};

function RawJson({ panel }: { panel: PanelState }) {
  if (panel.docs.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ p: 1 }}>
        No document loaded.
      </Typography>
    );
  }
  const raw = panel.docs[panel.selectedIndex]?.raw || "";
  return (
    <Box className="j-body">
      {raw.split("\n").map((line, i) => (
        <div key={i} className="j-line j-plain">
          {line.length ? line : " "}
        </div>
      ))}
    </Box>
  );
}

export function MongoView() {
  const { withBusy, toast, confirm } = useUi();
  const [config, setConfig] = useState<MongoConfig | null>(null);
  const [left, setLeft] = useState<PanelState>(initialPanel);
  const [right, setRight] = useState<PanelState>(initialPanel);
  const [diffOnly, setDiffOnly] = useState(false);

  const setters: Record<Side, React.Dispatch<React.SetStateAction<PanelState>>> = {
    left: setLeft,
    right: setRight,
  };
  const panels: Record<Side, PanelState> = { left, right };

  useEffect(() => {
    (async () => {
      try {
        setConfig(await api<MongoConfig>("/api/mongo/config"));
      } catch (e) {
        toast((e as Error).message, "error", "Failed to load Mongo config");
      }
    })();
  }, [toast]);

  const update = (side: Side, patch: Partial<PanelState>) =>
    setters[side]((p) => ({ ...p, ...patch }));

  const onEnv = (side: Side, env: string) => {
    const dbOptions = config?.environments.find((e) => e.name === env)?.databases || [];
    update(side, { env, db: "", coll: "", dbOptions, collOptions: [], docs: [] });
  };

  const onDb = async (side: Side, db: string) => {
    update(side, { db, coll: "", collOptions: [], docs: [] });
    const env = panels[side].env;
    if (!env || !db) return;
    await withBusy("Loading collections…", async () => {
      try {
        const cols = await api<string[]>("/api/mongo/collections", { params: { env, db } });
        update(side, { collOptions: cols });
      } catch (e) {
        toast((e as Error).message, "error", "Failed to load collections");
      }
    });
  };

  const load = async (side: Side) => {
    const p = panels[side];
    const productId = p.productId.trim();
    if (!p.env || !p.db || !p.coll) {
      toast("Pick an environment, database and collection.", "error");
      return;
    }
    if (!productId) {
      toast("Enter a productId to load.", "error");
      return;
    }
    await withBusy("Loading document…", async () => {
      try {
        const res = await api<MongoDocumentResponse>("/api/mongo/document", {
          params: { env: p.env, db: p.db, collection: p.coll, productId },
        });
        if (res.found) {
          const docs = (res.documents || []).map((d) => ({ id: d.id, raw: d.json, obj: JSON.parse(d.json) }));
          const n = docs.length;
          update(side, {
            docs,
            selectedIndex: 0,
            status: {
              text: `Loaded · ${p.coll} · ${n} document${n === 1 ? "" : "s"} for productId ${productId}`,
              kind: "ok",
            },
          });
        } else {
          update(side, {
            docs: [],
            selectedIndex: 0,
            status: { text: `No document with productId "${productId}" in ${p.coll}.`, kind: "warn" },
          });
        }
      } catch (e) {
        update(side, { docs: [], status: { text: (e as Error).message, kind: "warn" } });
        toast((e as Error).message, "error", "Load failed");
      }
    });
  };

  const del = (side: Side) => {
    const p = panels[side];
    if (!p.env || !p.db || !p.coll) {
      toast("Pick an environment, database and collection first.", "error");
      return;
    }
    if (!p.docs.length) {
      toast("Load a document first, then choose which one to delete.", "error");
      return;
    }
    const id = p.docs[p.selectedIndex].id;
    confirm({
      title: "Delete document",
      danger: true,
      confirmLabel: "Delete document",
      busyMessage: "Deleting document…",
      body: (
        <Typography variant="body2" color="text.secondary">
          Permanently delete the document _id "{id}" from {p.env} / {p.db} / {p.coll}? This is destructive and
          cannot be undone. The source pipeline will typically re-seed and reprocess this document.
        </Typography>
      ),
      onConfirm: async () => {
        const res = await api<{ deleted: number }>("/api/mongo/document", {
          method: "DELETE",
          params: { env: p.env, db: p.db, collection: p.coll, id },
        });
        if (res.deleted > 0) {
          toast(`Deleted _id "${id}" from ${p.env} / ${p.db} / ${p.coll}. It can now be reprocessed.`, "success");
        } else {
          toast(`No document deleted — _id "${id}" was not found.`, "info");
        }
        await load(side);
      },
    });
  };

  const swap = () => {
    setLeft(right);
    setRight(left);
  };

  const compareBoth = async () => {
    await load("left");
    await load("right");
  };

  const bothLoaded = left.docs.length > 0 && right.docs.length > 0;
  const { rows, stats } = useMemo<{ rows: ReturnType<typeof computeDiff>["rows"]; stats: DiffStats }>(() => {
    if (!bothLoaded) return { rows: [], stats: { eq: 0, diff: 0, missing: 0 } };
    return computeDiff(left.docs[left.selectedIndex]?.obj, right.docs[right.selectedIndex]?.obj);
  }, [bothLoaded, left, right]);

  const visibleRows = diffOnly ? rows.filter((r) => !(r.left.cls === "j-eq" && r.right.cls === "j-eq")) : rows;

  const renderControls = (side: Side) => {
    const p = panels[side];
    return (
      <Stack spacing={1.25}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Chip label={side === "left" ? "A" : "B"} color="primary" size="small" />
          <Typography
            variant="caption"
            sx={{ color: p.status.kind === "ok" ? "success.main" : p.status.kind === "warn" ? "warning.main" : "text.secondary" }}
          >
            {p.status.text}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          <FormControl sx={{ minWidth: 130 }}>
            <InputLabel>Environment</InputLabel>
            <Select label="Environment" value={p.env} onChange={(e) => onEnv(side, String(e.target.value))}>
              {(config?.environments || []).map((env) => (
                <MenuItem key={env.name} value={env.name}>
                  {env.name}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <FormControl sx={{ minWidth: 150 }} disabled={!p.env}>
            <InputLabel>Database</InputLabel>
            <Select label="Database" value={p.db} onChange={(e) => onDb(side, String(e.target.value))}>
              {p.dbOptions.map((d) => (
                <MenuItem key={d} value={d}>
                  {d}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <FormControl sx={{ minWidth: 150 }} disabled={!p.db}>
            <InputLabel>Collection</InputLabel>
            <Select label="Collection" value={p.coll} onChange={(e) => update(side, { coll: String(e.target.value), docs: [] })}>
              {p.collOptions.map((c) => (
                <MenuItem key={c} value={c}>
                  {c}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <TextField
            label="productId"
            value={p.productId}
            onChange={(e) => update(side, { productId: e.target.value })}
            onKeyDown={(e) => {
              if (e.key === "Enter") load(side);
            }}
            sx={{ flex: 1, minWidth: 140 }}
          />
          <Button variant="contained" startIcon={<SearchIcon />} onClick={() => load(side)}>
            Load
          </Button>
          <Button color="error" variant="outlined" startIcon={<DeleteForeverIcon />} onClick={() => del(side)}>
            Delete
          </Button>
        </Stack>
        {p.docs.length > 0 && (
          <Stack direction="row" alignItems="center" spacing={1}>
            <Typography variant="caption" color="text.secondary">
              Document
            </Typography>
            <FormControl sx={{ minWidth: 220 }} disabled={p.docs.length <= 1}>
              <Select
                value={String(p.selectedIndex)}
                onChange={(e) => update(side, { selectedIndex: Number(e.target.value) })}
              >
                {p.docs.map((d, i) => (
                  <MenuItem key={d.id} value={String(i)} sx={{ fontFamily: "monospace", fontSize: 12 }}>
                    _id {d.id}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <Chip label={`${p.docs.length} match${p.docs.length === 1 ? "" : "es"}`} size="small" variant="outlined" />
          </Stack>
        )}
      </Stack>
    );
  };

  const renderBody = (side: Side) => {
    if (bothLoaded) {
      return (
        <Box className="j-body">
          {visibleRows.map((r, i) => {
            const cell = side === "left" ? r.left : r.right;
            return (
              <div
                key={i}
                className={`j-line ${cell.cls}`}
                style={{ paddingLeft: 8 + r.depth * 16 }}
                dangerouslySetInnerHTML={{ __html: cell.html && cell.html.length ? cell.html : "&nbsp;" }}
              />
            );
          })}
        </Box>
      );
    }
    return <RawJson panel={panels[side]} />;
  };

  if (!config) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", height: "100%" }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, p: 2, gap: 2 }}>
      <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 3 }}>
        <Stack direction="row" alignItems="center" spacing={2} flexWrap="wrap" useFlexGap>
          <Typography variant="h6">Mongo Compare</Typography>
          <Button variant="contained" onClick={compareBoth}>
            Compare both
          </Button>
          <Button startIcon={<SwapHorizIcon />} onClick={swap}>
            Swap A/B
          </Button>
          <FormControlLabel
            control={<Switch checked={diffOnly} onChange={(e) => setDiffOnly(e.target.checked)} />}
            label="Differences only"
          />
          <Box sx={{ flex: 1 }} />
          {bothLoaded && (
            <Stack direction="row" spacing={1}>
              <Chip label={`${stats.eq} matching`} sx={{ bgcolor: "#e6f4ec", color: "#1aa564" }} />
              <Chip label={`${stats.diff} differing`} sx={{ bgcolor: "#fdeaea", color: "#e0413f" }} />
              <Chip label={`${stats.missing} only on one side`} variant="outlined" />
            </Stack>
          )}
        </Stack>
      </Paper>

      <Box
        sx={{
          flex: 1,
          minHeight: 0,
          display: "grid",
          gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" },
          gap: 2,
        }}
      >
        {(["left", "right"] as Side[]).map((side) => (
          <Paper
            key={side}
            variant="outlined"
            sx={{ borderRadius: 3, display: "flex", flexDirection: "column", minHeight: 0, overflow: "hidden" }}
          >
            <Box sx={{ p: 1.5, borderBottom: "1px solid", borderColor: "divider" }}>{renderControls(side)}</Box>
            <Box sx={{ flex: 1, minHeight: 0, overflow: "auto", p: 1 }}>{renderBody(side)}</Box>
          </Paper>
        ))}
      </Box>
    </Box>
  );
}

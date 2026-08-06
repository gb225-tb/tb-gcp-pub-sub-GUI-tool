import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import SearchIcon from "@mui/icons-material/Search";
import DeleteSweepIcon from "@mui/icons-material/DeleteSweep";
import { api } from "../../api/client";
import type { CleanupDeleteResponse, CleanupScanResponse, MongoConfig } from "../../api/types";
import { useUi } from "../../app/UiProvider";

interface CollState {
  name: string;
  count: number | null;
  checked: boolean;
}
interface GroupState {
  label: string;
  database: string;
  collections: CollState[];
}

export function CleanupView() {
  const { withBusy, toast, confirm } = useUi();
  const [config, setConfig] = useState<MongoConfig | null>(null);
  const [env, setEnv] = useState("");
  const [productId, setProductId] = useState("");
  const [groups, setGroups] = useState<GroupState[]>([]);
  const [scanBadge, setScanBadge] = useState<{ text: string; kind: "ok" | "warn" } | null>(null);
  const [deleteBadges, setDeleteBadges] = useState<{ total: number; perColl: { collection: string; deleted: number }[] } | null>(
    null
  );

  useEffect(() => {
    (async () => {
      try {
        const cfg = await api<MongoConfig>("/api/mongo/config");
        setConfig(cfg);
        setGroups(
          (cfg.productCleanup || []).map((g) => ({
            label: g.label,
            database: g.database,
            collections: g.collections.map((c) => ({ name: c, count: null, checked: false })),
          }))
        );
      } catch (e) {
        toast((e as Error).message, "error", "Failed to load Mongo config");
      }
    })();
  }, [toast]);

  const targets = useMemo(
    () =>
      groups.flatMap((g) => g.collections.filter((c) => c.checked).map((c) => ({ database: g.database, collection: c.name }))),
    [groups]
  );

  const setColl = (dbName: string, collName: string, patch: Partial<CollState>) =>
    setGroups((prev) =>
      prev.map((g) =>
        g.database === dbName
          ? { ...g, collections: g.collections.map((c) => (c.name === collName ? { ...c, ...patch } : c)) }
          : g
      )
    );

  const setGroupChecked = (dbName: string, checked: boolean) =>
    setGroups((prev) =>
      prev.map((g) =>
        g.database === dbName ? { ...g, collections: g.collections.map((c) => ({ ...c, checked })) } : g
      )
    );

  const scan = () =>
    withBusy("Scanning collections…", async () => {
      if (!env) {
        toast("Select an environment.", "error");
        return;
      }
      const pid = productId.trim();
      if (!pid) {
        toast("Enter a productId.", "error");
        return;
      }
      setDeleteBadges(null);
      try {
        const res = await api<CleanupScanResponse>("/api/mongo/cleanup/scan", { params: { env, productId: pid } });
        const byKey: Record<string, number> = {};
        (res.groups || []).forEach((g) => g.collections.forEach((c) => (byKey[`${g.database}::${c.name}`] = c.count)));
        setGroups((prev) =>
          prev.map((g) => ({
            ...g,
            collections: g.collections.map((c) => {
              const n = byKey[`${g.database}::${c.name}`] ?? 0;
              return { ...c, count: n, checked: n > 0 };
            }),
          }))
        );
        setScanBadge(
          (res.total || 0) === 0
            ? { text: "productId not found in any collection", kind: "warn" }
            : { text: `${res.total} document(s) found`, kind: "ok" }
        );
      } catch (e) {
        toast((e as Error).message, "error", "Scan failed");
      }
    });

  const del = () => {
    const pid = productId.trim();
    if (!env || !pid) {
      toast("Select an environment and productId.", "error");
      return;
    }
    if (!targets.length) {
      toast("Select at least one collection.", "error");
      return;
    }
    confirm({
      title: "Delete product data",
      danger: true,
      confirmLabel: `Delete from ${targets.length} collection(s)`,
      busyMessage: "Deleting product from collections…",
      body: (
        <Box>
          <Typography variant="body2" color="text.secondary">
            Permanently delete ALL documents with productId "{pid}" in {env} from these collections? This cannot
            be undone.
          </Typography>
          <Box component="ul" sx={{ mt: 1, pl: 2.5 }}>
            {targets.map((t) => (
              <li key={`${t.database}/${t.collection}`}>
                {t.database} / {t.collection}
              </li>
            ))}
          </Box>
        </Box>
      ),
      onConfirm: async () => {
        const res = await api<CleanupDeleteResponse>("/api/mongo/cleanup/delete", {
          method: "POST",
          body: { env, productId: pid, targets },
        });
        setDeleteBadges({
          total: res.totalDeleted || 0,
          perColl: (res.results || []).filter((r) => r.deleted > 0),
        });
        // Reflect deletions in the badges.
        (res.results || []).forEach((r) => {
          if (r.deleted > 0) setColl(r.database, r.collection, { count: 0, checked: false });
        });
        const t = res.totalDeleted || 0;
        toast(`Deleted ${t} document(s) for productId "${pid}".`, t > 0 ? "success" : "info");
      },
    });
  };

  if (!config) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", height: "100%" }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ height: "100%", minHeight: 0, overflow: "auto", p: 2 }}>
      <Stack spacing={2} sx={{ maxWidth: 1100, mx: "auto" }}>
        <Typography variant="h6">Product Clean Up</Typography>

        <Paper variant="outlined" sx={{ p: 2, borderRadius: 3 }}>
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <FormControl sx={{ minWidth: 160 }}>
              <InputLabel>Environment</InputLabel>
              <Select label="Environment" value={env} onChange={(e) => setEnv(String(e.target.value))}>
                {config.environments.map((e) => (
                  <MenuItem key={e.name} value={e.name}>
                    {e.name}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <TextField
              label="productId"
              value={productId}
              onChange={(e) => setProductId(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") scan();
              }}
              sx={{ flex: 1, minWidth: 200 }}
            />
            <Button variant="contained" startIcon={<SearchIcon />} onClick={scan}>
              Scan
            </Button>
            {scanBadge && (
              <Chip
                label={scanBadge.text}
                color={scanBadge.kind === "warn" ? "warning" : "primary"}
                variant={scanBadge.kind === "warn" ? "outlined" : "filled"}
              />
            )}
          </Stack>
        </Paper>

        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2 }}>
          {groups.map((g) => {
            const allChecked = g.collections.length > 0 && g.collections.every((c) => c.checked);
            return (
              <Paper key={g.database} variant="outlined" sx={{ borderRadius: 3, overflow: "hidden" }}>
                <Stack
                  direction="row"
                  alignItems="center"
                  justifyContent="space-between"
                  sx={{ px: 2, py: 1, borderBottom: "1px solid", borderColor: "divider", bgcolor: "action.hover" }}
                >
                  <FormControlLabel
                    control={
                      <Checkbox checked={allChecked} onChange={(e) => setGroupChecked(g.database, e.target.checked)} />
                    }
                    label={<Typography variant="subtitle2">{g.label}</Typography>}
                  />
                  <Chip label={g.database} size="small" variant="outlined" className="mono" />
                </Stack>
                <Box sx={{ p: 1 }}>
                  {g.collections.map((c) => (
                    <Stack
                      key={c.name}
                      direction="row"
                      alignItems="center"
                      spacing={1}
                      sx={{ px: 1, py: 0.5, borderRadius: 1.5, "&:hover": { bgcolor: "action.hover" } }}
                    >
                      <Checkbox
                        checked={c.checked}
                        onChange={(e) => setColl(g.database, c.name, { checked: e.target.checked })}
                      />
                      <Typography variant="body2" sx={{ flex: 1 }}>
                        {c.name}
                      </Typography>
                      <Chip
                        label={c.count === null ? "—" : String(c.count)}
                        size="small"
                        variant={c.count && c.count > 0 ? "filled" : "outlined"}
                        color={c.count && c.count > 0 ? "primary" : "default"}
                      />
                    </Stack>
                  ))}
                </Box>
              </Paper>
            );
          })}
        </Box>

        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <Button
            variant="contained"
            color="error"
            startIcon={<DeleteSweepIcon />}
            disabled={targets.length === 0}
            onClick={del}
          >
            Delete selected
          </Button>
          {deleteBadges && (
            <>
              <Chip color="success" label={`${deleteBadges.total} deleted`} />
              {deleteBadges.perColl.map((r) => (
                <Chip key={r.collection} label={`${r.collection}: ${r.deleted}`} variant="outlined" />
              ))}
            </>
          )}
        </Stack>
      </Stack>
    </Box>
  );
}

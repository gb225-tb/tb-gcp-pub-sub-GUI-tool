import { useEffect, useMemo, useRef, useState } from "react";
import Autocomplete from "@mui/material/Autocomplete";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Divider from "@mui/material/Divider";
import FormControl from "@mui/material/FormControl";
import IconButton from "@mui/material/IconButton";
import InputLabel from "@mui/material/InputLabel";
import ListSubheader from "@mui/material/ListSubheader";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Alert from "@mui/material/Alert";
import AddIcon from "@mui/icons-material/Add";
import CloseIcon from "@mui/icons-material/Close";
import UploadFileIcon from "@mui/icons-material/UploadFile";
import SendIcon from "@mui/icons-material/Send";
import ClearIcon from "@mui/icons-material/Clear";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { api } from "../../api/client";
import type { BulkPublishResult, SchemaDescriptor } from "../../api/types";
import { useAppState } from "../../app/AppState";
import { useUi } from "../../app/UiProvider";
import {
  BULK_OPS,
  computeBulkFields,
  computeFiltered,
  countInvalid,
  filterLabel,
  isFilterActive,
  parseDelimited,
  parseJsonMessages,
  schemaFieldMap,
  type BulkFilter,
  type BulkRecord,
  type ParseResult,
} from "./bulkLogic";

const DELIMITERS = [
  { v: "auto", label: "Auto-detect" },
  { v: ",", label: "Comma" },
  { v: "tab", label: "Tab" },
  { v: "|", label: "Pipe" },
  { v: ";", label: "Semicolon" },
];

let filterId = 1;

function FilterRow({
  filter,
  fields,
  onChange,
  onRemove,
}: {
  filter: BulkFilter;
  fields: string[];
  onChange: (f: BulkFilter) => void;
  onRemove: () => void;
}) {
  const opDef = BULK_OPS.find((o) => o.v === filter.op);
  const needsValue = !opDef || opDef.needsValue;

  return (
    <Paper variant="outlined" sx={{ p: 1.25, borderRadius: 2 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: needsValue ? 1 : 0 }}>
        <FormControl sx={{ flex: 1, minWidth: 120 }}>
          <InputLabel>Field</InputLabel>
          <Select
            label="Field"
            value={filter.field}
            onChange={(e) => onChange({ ...filter, field: String(e.target.value) })}
          >
            {fields.map((h) => (
              <MenuItem key={h} value={h}>
                {h}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
        <FormControl sx={{ minWidth: 150 }}>
          <InputLabel>Condition</InputLabel>
          <Select
            label="Condition"
            value={filter.op}
            onChange={(e) => onChange({ ...filter, op: String(e.target.value) })}
          >
            {BULK_OPS.map((o) => (
              <MenuItem key={o.v} value={o.v}>
                {o.label}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
        <IconButton onClick={onRemove} title="Remove filter">
          <CloseIcon fontSize="small" />
        </IconButton>
      </Stack>
      {needsValue && (
        <Autocomplete
          multiple
          freeSolo
          size="small"
          options={[]}
          value={filter.values}
          onChange={(_, newValue) => {
            const flat: string[] = [];
            (newValue as string[]).forEach((v) =>
              String(v)
                .split(/[,\n\t]/)
                .map((s) => s.trim())
                .filter(Boolean)
                .forEach((s) => {
                  if (!flat.includes(s)) flat.push(s);
                })
            );
            onChange({ ...filter, values: flat });
          }}
          renderTags={(value: readonly string[], getTagProps) =>
            value.map((option, index) => {
              const { key, ...rest } = getTagProps({ index });
              return <Chip key={key} label={option} {...rest} />;
            })
          }
          renderInput={(params) => (
            <TextField {...params} placeholder="value(s) — Enter or , to add" />
          )}
        />
      )}
    </Paper>
  );
}

export function BulkView() {
  const { groups } = useAppState();
  const { withBusy, toast, confirm } = useUi();

  const [schemas, setSchemas] = useState<SchemaDescriptor[]>([]);
  const [schemaId, setSchemaId] = useState("");
  const [topic, setTopic] = useState("");
  const [delimiter, setDelimiter] = useState("auto");

  const [fileName, setFileName] = useState<string | null>(null);
  const [rawText, setRawText] = useState<string | null>(null);
  const [records, setRecords] = useState<BulkRecord[]>([]);
  const [fields, setFields] = useState<string[]>([]);
  const [parsed, setParsed] = useState<ParseResult | null>(null);
  const [parseError, setParseError] = useState<string | null>(null);

  const [filters, setFilters] = useState<BulkFilter[]>([]);
  const [matchMode, setMatchMode] = useState<"all" | "any">("all");
  const [results, setResults] = useState<BulkPublishResult | null>(null);
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const schema = useMemo(() => schemas.find((s) => s.id === schemaId) || null, [schemas, schemaId]);

  useEffect(() => {
    (async () => {
      try {
        setSchemas(await api<SchemaDescriptor[]>("/api/schemas"));
      } catch {
        setSchemas([]);
      }
    })();
  }, []);

  // Reparse whenever the file, delimiter, or schema changes.
  useEffect(() => {
    if (rawText == null) return;
    const fieldMap = schemaFieldMap(schema);
    const ext = (fileName || "").toLowerCase().split(".").pop();
    try {
      const result = ext === "json" ? parseJsonMessages(rawText, fieldMap) : parseDelimited(rawText, delimiter, fieldMap);
      setRecords(result.records);
      setParsed(result);
      setFields(computeBulkFields(result));
      setParseError(null);
      setFilters([]);
      setResults(null);
    } catch (e) {
      setRecords([]);
      setFields([]);
      setParsed(null);
      setParseError((e as Error).message);
      setFilters([]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rawText, delimiter, schemaId, fileName]);

  const filtered = useMemo(() => computeFiltered(records, filters, matchMode), [records, filters, matchMode]);
  const anyActive = filters.some(isFilterActive);
  const invalidInfo = useMemo(() => countInvalid(records, schema), [records, schema]);

  const handleFile = (file: File) => {
    setFileName(file.name);
    const reader = new FileReader();
    reader.onload = () => setRawText(String(reader.result || ""));
    reader.onerror = () => toast("Could not read the file.", "error");
    reader.readAsText(file);
  };

  const clear = () => {
    setFileName(null);
    setRawText(null);
    setRecords([]);
    setFields([]);
    setParsed(null);
    setParseError(null);
    setFilters([]);
    setMatchMode("all");
    setResults(null);
    if (inputRef.current) inputRef.current.value = "";
  };

  const doBulkPost = (msgs: string[]) =>
    withBusy(`Publishing ${msgs.length} message(s) to ${topic}…`, async () => {
      const res = await api<BulkPublishResult>(`/api/topics/${encodeURIComponent(topic)}/publish-bulk`, {
        method: "POST",
        body: { messages: msgs },
      });
      setResults(res);
      if (res.failed > 0) toast(`Published ${res.published}/${res.total} · ${res.failed} failed.`, "error", "Bulk publish");
      else toast(`Published ${res.published} message(s) to ${topic}.`, "success");
    });

  const post = () => {
    if (!topic) {
      toast("Select a topic to publish to.", "error");
      return;
    }
    if (!records.length) {
      toast("Load a file with at least one record.", "error");
      return;
    }
    const msgs = filtered.map((r) => JSON.stringify(r));
    if (!msgs.length) {
      toast("No records match the current filter — nothing to post.", "error");
      return;
    }
    const active = filters.filter(isFilterActive);
    const { invalid, samples } = countInvalid(filtered, schema);

    confirm({
      title: "Confirm bulk publish",
      confirmLabel: invalid > 0 ? `Publish anyway (${invalid} may fail)` : `Publish ${msgs.length} message(s)`,
      busyMessage: `Publishing ${msgs.length} message(s) to ${topic}…`,
      body: (
        <Stack spacing={1}>
          <Typography variant="body2" color="text.secondary">
            Publish these messages to the selected topic? This is a real, deliberate write.
          </Typography>
          <ConfirmRow k="Topic" v={<span className="mono">{topic}</span>} />
          <ConfirmRow k="Schema" v={schema ? schema.title : "None (raw strings)"} />
          <ConfirmRow
            k="Messages"
            v={active.length ? `${msgs.length} of ${records.length} (filtered)` : `${msgs.length} (all records)`}
          />
          {active.length > 0 && (
            <ConfirmRow
              k={`Filter · match ${matchMode === "any" ? "ANY" : "ALL"}`}
              v={
                <Box component="ul" sx={{ m: 0, pl: 2 }}>
                  {active.map((f) => (
                    <li key={f.id} className="mono">
                      {filterLabel(f)}
                    </li>
                  ))}
                </Box>
              }
            />
          )}
          {invalid > 0 && (
            <Alert severity="warning" variant="outlined">
              <strong>
                {invalid} of {msgs.length} record(s) do not satisfy "{schema?.title}"
              </strong>
              <Box component="ul" sx={{ m: "6px 0 0", pl: 2 }}>
                {samples.map((s, i) => (
                  <li key={i} className="mono">
                    {s}
                  </li>
                ))}
              </Box>
              <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
                The Dataflow consumer will likely reject these.
              </Typography>
            </Alert>
          )}
        </Stack>
      ),
      onConfirm: () => doBulkPost(msgs),
    });
  };

  const shown = Math.min(5, filtered.length);

  return (
    <Box sx={{ height: "100%", minHeight: 0, display: "flex", flexDirection: { xs: "column", md: "row" }, gap: 2, p: 2 }}>
      {/* Left: configuration + filters */}
      <Box sx={{ width: { xs: "100%", md: 420 }, flexShrink: 0, minHeight: 0, overflow: "auto", pr: 0.5 }}>
        <Stack spacing={2}>
          <Typography variant="h6">Bulk Post</Typography>

          <FormControl fullWidth>
            <InputLabel id="bulk-topic-label">Topic</InputLabel>
            <Select labelId="bulk-topic-label" label="Topic" value={topic} onChange={(e) => setTopic(String(e.target.value))}>
              <MenuItem value="">
                <em>Select a topic…</em>
              </MenuItem>
              {groups.flatMap((g) => [
                <ListSubheader key={`h-${g.name}`}>{g.name}</ListSubheader>,
                ...g.topics.map((t) => (
                  <MenuItem key={t} value={t} sx={{ fontFamily: "monospace", fontSize: 12 }}>
                    {t}
                  </MenuItem>
                )),
              ])}
            </Select>
          </FormControl>

          <Paper
            variant="outlined"
            onClick={() => inputRef.current?.click()}
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              const f = e.dataTransfer.files?.[0];
              if (f) handleFile(f);
            }}
            sx={{
              p: 2,
              borderRadius: 3,
              borderStyle: "dashed",
              borderColor: dragging ? "primary.main" : "divider",
              bgcolor: dragging ? "action.hover" : "transparent",
              cursor: "pointer",
              textAlign: "center",
            }}
          >
            <UploadFileIcon color="primary" />
            <Typography variant="body2" sx={{ mt: 0.5 }}>
              {fileName ? fileName : "Drop a .csv / .txt / .json file, or click to choose"}
            </Typography>
            <input
              ref={inputRef}
              type="file"
              accept=".csv,.txt,.json"
              hidden
              onChange={(e) => e.target.files?.[0] && handleFile(e.target.files[0])}
            />
          </Paper>

          <Stack direction="row" spacing={1}>
            <FormControl sx={{ flex: 1 }}>
              <InputLabel>Delimiter</InputLabel>
              <Select label="Delimiter" value={delimiter} onChange={(e) => setDelimiter(String(e.target.value))}>
                {DELIMITERS.map((d) => (
                  <MenuItem key={d.v} value={d.v}>
                    {d.label}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <FormControl sx={{ flex: 1.4 }}>
              <InputLabel>Schema</InputLabel>
              <Select label="Schema" value={schemaId} onChange={(e) => setSchemaId(String(e.target.value))}>
                <MenuItem value="">None (raw strings)</MenuItem>
                {schemas.map((s) => (
                  <MenuItem key={s.id} value={s.id}>
                    {s.coercible ? s.title : `${s.title} (structural)`}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>

          {/* File meta */}
          {(parsed || parseError) && (
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
              {parseError ? (
                <>
                  <Chip color="error" label="Parse error" size="small" />
                  <Typography variant="caption" color="error">
                    {fileName}: {parseError}
                  </Typography>
                </>
              ) : parsed ? (
                <>
                  <Chip label={parsed.format} size="small" />
                  {parsed.delimiter && (
                    <Typography variant="caption" color="text.secondary">
                      delimiter: {parsed.delimiter === "\t" ? "Tab" : parsed.delimiter}
                    </Typography>
                  )}
                  <Typography variant="caption" color="text.secondary">
                    {records.length} record(s)
                  </Typography>
                  {schema &&
                    (schema.coercible ? (
                      <>
                        <Chip label={`schema: ${schema.title}`} size="small" variant="outlined" />
                        {invalidInfo.invalid > 0 ? (
                          <Chip color="warning" label={`${invalidInfo.invalid} fail schema`} size="small" />
                        ) : (
                          <Chip color="success" label="all match schema" size="small" />
                        )}
                      </>
                    ) : (
                      <Typography variant="caption" color="text.secondary">
                        schema "{schema.title}" is structural — no column coercion applied
                      </Typography>
                    ))}
                </>
              ) : null}
            </Stack>
          )}

          {/* Filters */}
          {records.length > 0 && fields.length > 0 && (
            <>
              <Divider />
              <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
                <Typography variant="subtitle2">Filter</Typography>
                <ToggleButtonGroup
                  exclusive
                  value={matchMode}
                  onChange={(_, v) => v && setMatchMode(v)}
                  size="small"
                >
                  <ToggleButton value="all">Match ALL</ToggleButton>
                  <ToggleButton value="any">Match ANY</ToggleButton>
                </ToggleButtonGroup>
              </Stack>

              <Stack spacing={1}>
                {filters.length === 0 && (
                  <Typography variant="caption" color="text.secondary">
                    No filters — every record will be posted. Add a filter to narrow it down.
                  </Typography>
                )}
                {filters.map((f) => (
                  <FilterRow
                    key={f.id}
                    filter={f}
                    fields={fields}
                    onChange={(nf) => setFilters((prev) => prev.map((x) => (x.id === f.id ? nf : x)))}
                    onRemove={() => setFilters((prev) => prev.filter((x) => x.id !== f.id))}
                  />
                ))}
              </Stack>

              <Stack direction="row" alignItems="center" spacing={1}>
                <Button
                  startIcon={<AddIcon />}
                  onClick={() => setFilters((prev) => [...prev, { id: filterId++, field: "", op: "eq", values: [] }])}
                >
                  Add filter
                </Button>
                <Box sx={{ flex: 1 }} />
                <Chip
                  label={anyActive ? `${filtered.length} of ${records.length} match` : `${records.length} record(s)`}
                  color={anyActive ? (filtered.length === 0 ? "warning" : "success") : "default"}
                  variant={anyActive ? "filled" : "outlined"}
                  size="small"
                />
              </Stack>
            </>
          )}

          <Divider />
          <Stack direction="row" spacing={1}>
            <Button
              variant="contained"
              startIcon={<SendIcon />}
              disabled={records.length === 0 || filtered.length === 0}
              onClick={post}
            >
              Post
            </Button>
            <Button
              startIcon={<ClearIcon />}
              disabled={records.length === 0 && rawText == null}
              onClick={clear}
            >
              Clear
            </Button>
          </Stack>

          {results && (
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
              <Chip color="success" label={`${results.published} published`} size="small" />
              {results.failed > 0 && <Chip color="error" label={`${results.failed} failed`} size="small" />}
              {results.errors?.length > 0 && (
                <Accordion variant="outlined" sx={{ width: "100%" }}>
                  <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                    <Typography variant="body2">{results.errors.length} error(s)</Typography>
                  </AccordionSummary>
                  <AccordionDetails>
                    <Box component="ul" sx={{ m: 0, pl: 2 }}>
                      {results.errors.map((er, i) => (
                        <li key={i} className="mono">
                          {er}
                        </li>
                      ))}
                    </Box>
                  </AccordionDetails>
                </Accordion>
              )}
            </Stack>
          )}
        </Stack>
      </Box>

      {/* Right: preview */}
      <Paper variant="outlined" sx={{ flex: 1, minWidth: 0, minHeight: 0, borderRadius: 3, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        <Stack
          direction="row"
          alignItems="center"
          justifyContent="space-between"
          sx={{ px: 2, py: 1.25, borderBottom: "1px solid", borderColor: "divider", bgcolor: "action.hover" }}
        >
          <Typography variant="subtitle2">Preview</Typography>
          <Typography variant="caption" color="text.secondary">
            {filtered.length > 0 ? `${filtered.length} message(s) to post · showing first ${shown}` : ""}
          </Typography>
        </Stack>
        <Box sx={{ flex: 1, minHeight: 0, overflow: "auto", p: 2 }}>
          {parseError ? (
            <Typography variant="body2" color="error">
              {parseError}
            </Typography>
          ) : filtered.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {records.length === 0
                ? "No file loaded yet."
                : anyActive
                  ? "No records match the current filter."
                  : "No records found in the file."}
            </Typography>
          ) : (
            <Stack spacing={1.5}>
              {filtered.slice(0, shown).map((r, i) => (
                <Box key={i}>
                  <Typography variant="caption" color="text.secondary">
                    #{i + 1}
                  </Typography>
                  <Box
                    component="pre"
                    className="mono"
                    sx={{
                      m: 0,
                      p: 1.25,
                      bgcolor: "action.hover",
                      borderRadius: 1.5,
                      fontSize: 12,
                      overflow: "auto",
                      whiteSpace: "pre-wrap",
                      wordBreak: "break-word",
                    }}
                  >
                    {JSON.stringify(r, null, 2)}
                  </Box>
                </Box>
              ))}
            </Stack>
          )}
        </Box>
      </Paper>
    </Box>
  );
}

function ConfirmRow({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <Stack direction="row" spacing={2} sx={{ py: 0.25 }}>
      <Typography variant="caption" color="text.secondary" sx={{ minWidth: 120, flexShrink: 0 }}>
        {k}
      </Typography>
      <Box sx={{ fontSize: 13 }}>{v}</Box>
    </Stack>
  );
}

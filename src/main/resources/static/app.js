"use strict";

const state = {
  view: "pubsub", // "pubsub" | "mongo"
  project: "",
  emulator: false,
  restricted: false,
  groups: [], // [{ name, topics: [] }]
  activeGroup: null,
  subById: {}, // cache of SubscriptionInfo seen via topic detail
  selected: null, // { type: 'topic'|'sub', id }
  tails: new Set(), // active EventSource connections (one per subscription tail)
  busyCount: 0,
};

const $ = (sel) => document.querySelector(sel);
const el = (tag, props = {}, children = []) => {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([k, v]) => {
    if (k === "class") node.className = v;
    else if (k === "html") node.innerHTML = v;
    else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined) node.setAttribute(k, v);
  });
  (Array.isArray(children) ? children : [children]).forEach((c) => {
    if (c == null) return;
    node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
  });
  return node;
};

function closeTails() {
  state.tails.forEach((es) => {
    try { es.close(); } catch { /* ignore */ }
  });
  state.tails.clear();
}

// Resolve an API path against the app's context path (works at "/" or "/catalog-pubsub-gui").
function apiUrl(path) {
  return new URL(String(path).replace(/^\//, ""), document.baseURI);
}

// ----------------------------------------------------------------- API
async function api(path, options = {}) {
  const url = apiUrl(path);
  if (state.project) url.searchParams.set("project", state.project);
  if (options.params) {
    Object.entries(options.params).forEach(([k, v]) => url.searchParams.set(k, v));
    delete options.params;
  }
  const opts = { headers: {}, ...options };
  if (opts.body && typeof opts.body !== "string") {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(opts.body);
  }
  const res = await fetch(url, opts);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data;
}

// ------------------------------------------------------- Status & busy
function setStatus(text, type = "idle") {
  $("#statusText").textContent = text;
  $("#statusDot").className = `status-dot ${type}`;
}

function setBusy(on, message) {
  state.busyCount += on ? 1 : -1;
  if (state.busyCount < 0) state.busyCount = 0;
  const busy = state.busyCount > 0;
  const overlay = $("#busyOverlay");
  if (busy) {
    if (message) $("#busyText").textContent = message;
    overlay.classList.remove("hidden");
    setStatus(message || "Working…", "busy");
  } else {
    overlay.classList.add("hidden");
    setStatus("Ready.", "idle");
  }
}

async function withBusy(message, fn) {
  setBusy(true, message);
  try {
    return await fn();
  } finally {
    setBusy(false);
  }
}

function isBusy() {
  return state.busyCount > 0;
}

// --------------------------------------------------------------- Toasts
function toast(message, type = "info", title) {
  const titles = { success: "Success", error: "Error", info: "Info" };
  const node = el("div", { class: `toast ${type}` }, [
    el("div", { class: "toast-title" }, title || titles[type] || "Info"),
    el("div", { class: "toast-msg" }, message),
  ]);
  $("#toasts").appendChild(node);
  setTimeout(() => {
    node.style.opacity = "0";
    node.style.transition = "opacity 0.3s";
    setTimeout(() => node.remove(), 300);
  }, type === "error" ? 7000 : 3500);
}

// --------------------------------------------------------------- Modal
function openModal(title, bodyNode, footerNodes) {
  $("#modalTitle").textContent = title;
  const body = $("#modalBody");
  body.innerHTML = "";
  body.appendChild(bodyNode);
  const footer = $("#modalFooter");
  footer.innerHTML = "";
  (footerNodes || []).forEach((n) => footer.appendChild(n));
  $("#modalOverlay").classList.remove("hidden");
}
function closeModal() {
  if (isBusy()) return; // prevent closing while an operation is running
  $("#modalOverlay").classList.add("hidden");
}

// ---------------------------------------------------------- Data loading
async function loadConfig() {
  try {
    const cfg = await api("/api/config");
    state.emulator = cfg.emulator;
    state.restricted = cfg.restricted;
    state.groups = Array.isArray(cfg.topicGroups) ? cfg.topicGroups : [];
    if (!state.project && cfg.defaultProjectId) {
      state.project = cfg.defaultProjectId;
      $("#projectInput").value = cfg.defaultProjectId;
    }
    const badge = $("#modeBadge");
    if (cfg.emulator) {
      badge.textContent = `EMULATOR · ${cfg.emulatorHost}`;
      badge.className = "badge mode-emulator";
      $("#statusMode").textContent = "Emulator (counts unavailable)";
    } else {
      badge.textContent = "REAL GCP";
      badge.className = "badge mode-real";
      $("#statusMode").textContent = "Real GCP";
    }
  } catch (e) {
    toast(e.message, "error");
  }
}

async function loadAll() {
  closeTails();
  state.project = $("#projectInput").value.trim();
  $("#statusProject").textContent = state.project ? `project: ${state.project}` : "";

  // If no groups are configured, fall back to one group built from /api/topics.
  if (!state.groups || state.groups.length === 0) {
    await withBusy("Loading topics…", async () => {
      try {
        const topics = await api("/api/topics");
        state.groups = [{ name: "Topics", topics: topics.map((t) => t.id) }];
      } catch (e) {
        state.groups = [{ name: "Topics", topics: [] }];
        toast(e.message, "error", "Failed to load topics");
      }
    });
  }
  renderGroups();
}

// ----------------------------------------------------------- Navigation
function renderGroups() {
  const tabs = $("#groupTabs");
  tabs.innerHTML = "";
  if (!state.activeGroup && state.groups.length) state.activeGroup = state.groups[0].name;

  state.groups.forEach((g) => {
    const active = g.name === state.activeGroup;
    tabs.appendChild(el("button", {
      class: `group-tab${active ? " active" : ""}`,
      onclick: () => { if (!isBusy()) setActiveGroup(g.name); },
    }, [
      el("span", { class: "group-tab-name" }, g.name),
      el("span", { class: "pill" }, String(g.topics.length)),
    ]));
  });
  renderTopicSelect();
}

function setActiveGroup(name) {
  state.activeGroup = name;
  renderGroups();
}

function renderTopicSelect() {
  const select = $("#topicSelect");
  select.innerHTML = "";
  select.appendChild(el("option", { value: "" }, "Select a topic…"));
  const group = state.groups.find((g) => g.name === state.activeGroup);
  if (group) {
    group.topics.forEach((t) => {
      const opt = el("option", { value: t }, t);
      if (state.selected && state.selected.type === "topic" && state.selected.id === t) opt.selected = true;
      select.appendChild(opt);
    });
  }
}

function selectItem(type, id) {
  if (isBusy()) return;
  closeTails();
  state.selected = { type, id };
  if (type === "topic") {
    renderTopicSelect();
    renderTopicDetail(id);
  } else {
    renderSubDetail(id);
  }
}

// Activate the group that owns a topic, then open it.
function goToTopic(topicId) {
  const group = state.groups.find((g) => g.topics.includes(topicId));
  if (group) state.activeGroup = group.name;
  state.selected = { type: "topic", id: topicId };
  renderGroups();
  renderTopicDetail(topicId);
}

// ----------------------------------------------------- Counts rendering
function countCard(label, value, accent) {
  return el("div", { class: `count-card${accent ? " " + accent : ""}` }, [
    el("div", { class: "count-value" }, value === null ? "—" : String(value)),
    el("div", { class: "count-label" }, label),
  ]);
}

function countsRow(c) {
  if (!c || !c.available) {
    return el("div", {}, [
      el("div", { class: "counts-grid" }, [
        countCard("Total", "—"),
        countCard("ACK · consumed (24h)", "—", "accent-ack"),
        countCard("Non-ACK · pending", "—", "accent-nack"),
      ]),
      el("div", { class: "hint", style: "margin-top:8px" }, (c && c.note) || "Counts unavailable."),
    ]);
  }
  return el("div", { class: "counts-grid" }, [
    countCard("Total", c.total),
    countCard("ACK · consumed (24h)", c.ack, "accent-ack"),
    countCard("Non-ACK · pending", c.nonAck, "accent-nack"),
  ]);
}

// --------------------------------------------------------- Topic detail
async function renderTopicDetail(topicId) {
  const content = $("#content");
  content.innerHTML = "";

  content.appendChild(el("div", { class: "detail-header" }, [
    el("div", { class: "detail-title" }, [
      el("span", { class: "type-chip chip-topic" }, "Topic"),
      el("h2", {}, topicId),
    ]),
    el("div", { class: "detail-actions" }, [
      el("button", { class: "btn btn-sm", onclick: () => renderTopicDetail(topicId) }, "⟳ Refresh"),
      el("button", { class: "btn btn-sm btn-danger", onclick: () => confirmPurgeTopic(topicId) }, "Purge all"),
    ]),
  ]));

  const countsSection = el("div", { class: "section" }, [
    el("div", { class: "section-head" }, [el("h3", {}, "Message counts (all subscriptions)")]),
    el("div", { class: "section-body", id: "topicCountsBody" }, loadingNode("Reading Cloud Monitoring…")),
  ]);
  content.appendChild(countsSection);

  const subsSection = el("div", { class: "section" }, [
    el("div", { class: "section-head" }, [el("h3", {}, "Subscriptions")]),
    el("div", { class: "section-body", id: "topicSubsBody" }, loadingNode("Loading subscriptions…")),
  ]);
  content.appendChild(subsSection);

  content.appendChild(buildPublishSection(topicId));

  content.appendChild(el("div", { class: "tails-header" }, [
    el("h3", {}, "Live tail — whole topic"),
    el("span", { class: "hint" }, "Creates a temporary subscription (auto-deleted on stop). Sees every published message even when other subscriptions are actively consumed (e.g. by Dataflow)."),
  ]));
  content.appendChild(buildTailSection({
    path: `api/topics/${encodeURIComponent(topicId)}/tail`,
    name: topicId,
    title: "Live tail (new subscription)",
    hint: "A dedicated temporary subscription receives its own copy of every message published to this topic, so nothing is taken from real consumers.",
  }));

  content.appendChild(el("div", { class: "tails-header" }, [
    el("h3", {}, "Live tail — per existing subscription"),
    el("span", { class: "hint" }, "Observes each existing subscription without ACK (messages released). Note: a subscription actively drained by its consumer may show little or nothing here — use the whole-topic tail above instead."),
  ]));
  content.appendChild(el("div", { id: "topicTails" }, loadingNode("Loading subscriptions…")));

  try {
    const counts = await api(`/api/topics/${encodeURIComponent(topicId)}/counts`);
    $("#topicCountsBody").innerHTML = "";
    $("#topicCountsBody").appendChild(countsRow(counts));
    renderTopicSubsTable(topicId, counts);
  } catch (e) {
    $("#topicCountsBody").innerHTML = "";
    $("#topicCountsBody").appendChild(el("div", { class: "hint" }, "Error: " + e.message));
    renderTopicSubsTable(topicId, null);
  }
}

async function renderTopicSubsTable(topicId, counts) {
  const body = $("#topicSubsBody");
  const tailsWrap = $("#topicTails");
  body.innerHTML = "";
  let subs;
  try {
    subs = await api(`/api/topics/${encodeURIComponent(topicId)}/subscriptions`);
  } catch (e) {
    body.appendChild(el("div", { class: "hint" }, "Error: " + e.message));
    if (tailsWrap) { tailsWrap.innerHTML = ""; tailsWrap.appendChild(el("div", { class: "hint" }, "Error: " + e.message)); }
    return;
  }
  if (subs.length === 0) {
    body.appendChild(el("div", { class: "hint" }, "No subscriptions on this topic — there are no messages to view or count."));
    if (tailsWrap) { tailsWrap.innerHTML = ""; tailsWrap.appendChild(el("div", { class: "hint" }, "No subscriptions to tail on this topic.")); }
    return;
  }
  subs.forEach((s) => (state.subById[s.id] = s));

  if (tailsWrap) {
    tailsWrap.innerHTML = "";
    subs.forEach((s) => tailsWrap.appendChild(buildTailSection({
      path: `api/subscriptions/${encodeURIComponent(s.id)}/tail`,
      name: s.id,
      mono: s.id,
      compact: true,
    })));
  }

  const countById = {};
  if (counts && counts.subscriptions) counts.subscriptions.forEach((c) => (countById[c.subscriptionId] = c));

  const rows = subs.map((s) => {
    const c = countById[s.id];
    return el("tr", {}, [
      el("td", {}, [el("span", { class: "row-link", onclick: () => selectItem("sub", s.id) }, s.id)]),
      el("td", { class: "mono num" }, c && c.available ? String(c.total) : "—"),
      el("td", { class: "mono num accent-ack" }, c && c.available ? String(c.ack) : "—"),
      el("td", { class: "mono num accent-nack" }, c && c.available ? String(c.nonAck) : "—"),
      el("td", {}, [
        el("button", { class: "btn btn-sm", onclick: () => viewLatest(s.id) }, "View latest"),
        el("button", { class: "btn btn-sm btn-danger", style: "margin-left:6px", onclick: () => confirmPurgeSub(s.id) }, "Purge"),
      ]),
    ]);
  });
  body.appendChild(table(["Subscription", "Total", "ACK (24h)", "Non-ACK", "Actions"], rows));
}

async function viewLatest(subId) {
  await withBusy(`Fetching latest message from ${subId}…`, async () => {
    try {
      const msgs = await api(`/api/subscriptions/${encodeURIComponent(subId)}/latest`, { method: "POST" });
      const bodyNode = el("div", {});
      if (!msgs || msgs.length === 0) {
        bodyNode.appendChild(el("div", { class: "hint" }, "No messages currently available on this subscription."));
      } else {
        bodyNode.appendChild(el("div", { class: "hint", style: "margin-bottom:10px" }, "Non-destructive peek — the message stays in the subscription."));
        msgs.forEach((m) => { const card = messageCard(m); card.classList.add("open"); bodyNode.appendChild(card); });
      }
      openModal(`Latest message · ${subId}`, bodyNode, [el("button", { class: "btn", onclick: closeModal }, "Close")]);
    } catch (e) {
      toast(e.message, "error", "View failed");
    }
  });
}

// ---------------------------------------------------------- Sub detail
async function renderSubDetail(subId) {
  const sub = state.subById[subId];
  const content = $("#content");
  content.innerHTML = "";

  content.appendChild(el("div", { class: "detail-header" }, [
    el("div", { class: "detail-title" }, [
      el("span", { class: "type-chip chip-sub" }, "Subscription"),
      el("h2", {}, subId),
    ]),
    el("div", { class: "detail-actions" }, [
      el("button", { class: "btn btn-sm", onclick: () => renderSubDetail(subId) }, "⟳ Refresh"),
      el("button", { class: "btn btn-sm btn-danger", onclick: () => confirmPurgeSub(subId) }, "Purge"),
    ]),
  ]));

  if (sub) {
    content.appendChild(el("div", { class: "meta-grid" }, [
      metaCard("Topic", sub.topicId, () => goToTopic(sub.topicId)),
      metaCard("Ack deadline", sub.ackDeadlineSeconds + "s"),
      metaCard("Delivery", sub.hasPush ? "Push" : "Pull"),
      metaCard("Retention", sub.messageRetentionDuration || "—"),
      sub.hasPush ? metaCard("Push endpoint", sub.pushEndpoint) : null,
    ].filter(Boolean)));
  }

  const countsSection = el("div", { class: "section" }, [
    el("div", { class: "section-head" }, [el("h3", {}, "Message counts")]),
    el("div", { class: "section-body", id: "subCountsBody" }, loadingNode("Reading Cloud Monitoring…")),
  ]);
  content.appendChild(countsSection);

  const maxInput = el("input", { type: "number", value: "10", min: "1", max: "1000", style: "width:80px" });
  const peekBtn = el("button", { class: "btn btn-primary btn-sm" }, "View messages (peek)");
  const latestBtn = el("button", { class: "btn btn-sm" }, "View latest");
  const msgContainer = el("div", { id: "msgContainer" }, el("div", { class: "hint" }, "Peek messages to view them here (non-destructive)."));

  peekBtn.addEventListener("click", () => doPeek(subId, maxInput.value, msgContainer));
  latestBtn.addEventListener("click", () => doPeek(subId, "1", msgContainer));

  content.appendChild(el("div", { class: "section" }, [
    el("div", { class: "section-head" }, [el("h3", {}, "Messages")]),
    el("div", { class: "section-body" }, [
      el("div", { class: "msg-toolbar" }, [
        el("div", { class: "field" }, [el("label", {}, "Max"), maxInput]),
        peekBtn, latestBtn,
        el("span", { class: "spacer" }),
        el("span", { class: "hint" }, "Peek is non-destructive — messages are not consumed."),
      ]),
      msgContainer,
    ]),
  ]));

  content.appendChild(buildTailSection({
    path: `api/subscriptions/${encodeURIComponent(subId)}/tail`,
    name: subId,
    title: "Live tail",
    hint: "Observes this subscription in real time and releases every message (no ACK). If a consumer (e.g. Dataflow) is actively draining it, messages may not appear here — tail the whole topic from the topic view instead.",
  }));

  try {
    const c = await api(`/api/subscriptions/${encodeURIComponent(subId)}/counts`);
    $("#subCountsBody").innerHTML = "";
    $("#subCountsBody").appendChild(countsRow(c));
  } catch (e) {
    $("#subCountsBody").innerHTML = "";
    $("#subCountsBody").appendChild(el("div", { class: "hint" }, "Error: " + e.message));
  }
}

async function doPeek(subId, max, container) {
  await withBusy(`Peeking messages from ${subId}…`, async () => {
    container.innerHTML = "";
    container.appendChild(loadingNode("Peeking…"));
    try {
      const msgs = await api(`/api/subscriptions/${encodeURIComponent(subId)}/peek`, { method: "POST", params: { max: max || "10" } });
      container.innerHTML = "";
      if (!msgs || msgs.length === 0) {
        container.appendChild(el("div", { class: "hint" }, "No messages currently available on this subscription."));
      } else {
        container.appendChild(el("div", { class: "hint", style: "margin-bottom:8px" }, `${msgs.length} message(s) peeked (not consumed).`));
        msgs.forEach((m) => container.appendChild(messageCard(m)));
      }
    } catch (e) {
      container.innerHTML = "";
      container.appendChild(el("div", { class: "hint" }, "Error: " + e.message));
      toast(e.message, "error", "Peek failed");
    }
  });
}

// --------------------------------------------------------------- Publish
function buildPublishSection(topicId) {
  const dataInput = el("textarea", { placeholder: "Message body (plain text or JSON)…" });
  const orderingInput = el("input", { type: "text", placeholder: "(optional)" });
  const attrRows = el("div", { class: "kv-rows" });

  const addAttrRow = (k = "", v = "") => {
    const keyI = el("input", { type: "text", placeholder: "key", value: k });
    const valI = el("input", { type: "text", placeholder: "value", value: v });
    const row = el("div", { class: "kv-row" }, [
      keyI, valI,
      el("button", { class: "icon-btn", title: "Remove attribute", onclick: () => row.remove() }, "✕"),
    ]);
    attrRows.appendChild(row);
  };

  const collect = () => {
    const attributes = {};
    attrRows.querySelectorAll(".kv-row").forEach((row) => {
      const inputs = row.querySelectorAll("input");
      const key = inputs[0].value.trim();
      if (key) attributes[key] = inputs[1].value;
    });
    return { data: dataInput.value, attributes, orderingKey: orderingInput.value.trim() || null };
  };

  const publishBtn = el("button", { class: "btn btn-primary" }, "Publish message");
  const burstInput = el("input", { type: "number", value: "1", min: "1", max: "100", style: "width:70px" });
  const burstBtn = el("button", { class: "btn" }, "Publish ×N");

  const doPublish = (times) => withBusy(`Publishing ${times > 1 ? times + " messages" : "message"} to ${topicId}…`, async () => {
    const body = collect();
    let last;
    try {
      for (let i = 0; i < times; i += 1) {
        last = await api(`/api/topics/${encodeURIComponent(topicId)}/publish`, { method: "POST", body });
      }
      toast(times > 1 ? `Published ${times} messages (last id ${last.messageId}).` : `Published · message id ${last.messageId}.`, "success");
    } catch (e) {
      toast(e.message, "error", "Publish failed");
    }
  });

  publishBtn.addEventListener("click", () => doPublish(1));
  burstBtn.addEventListener("click", () => {
    const n = Math.max(1, Math.min(parseInt(burstInput.value, 10) || 1, 100));
    doPublish(n);
  });

  return el("div", { class: "section" }, [
    el("div", { class: "section-head" }, [
      el("h3", {}, "Publish a message"),
      el("span", { class: "hint" }, "Send a message to this topic — start the live tail below to watch it arrive."),
    ]),
    el("div", { class: "section-body" }, [
      el("div", { class: "field" }, [el("label", {}, "Data"), dataInput]),
      el("div", { class: "form-row", style: "margin-top:12px" }, [
        el("div", { class: "field grow" }, [
          el("label", {}, "Attributes"),
          attrRows,
          el("button", { class: "btn btn-sm", style: "align-self:flex-start", onclick: () => addAttrRow() }, "+ Add attribute"),
        ]),
        el("div", { class: "field" }, [el("label", {}, "Ordering key"), orderingInput]),
      ]),
      el("div", { class: "msg-toolbar", style: "margin-top:14px" }, [
        publishBtn,
        el("span", { class: "spacer" }),
        el("span", { class: "hint" }, "Burst"), burstInput, burstBtn,
      ]),
    ]),
  ]);
}

// --------------------------------------------------------------- Live tail
// Generic live-tail panel. opts:
//   path    - SSE API path (e.g. "api/subscriptions/<id>/tail")
//   name    - label used in toasts
//   title   - heading text (string); or set `mono` to show a monospace name
//   mono    - monospace heading text (for compact per-subscription panels)
//   hint    - body hint text (null to omit)
//   compact - smaller heading, no body hint
function buildTailSection(opts) {
  const liveDot = el("span", { class: "live-dot hidden" });
  const statusText = el("span", { class: "hint" }, "Not listening.");
  const countBadge = el("span", { class: "pill" }, "0");
  const list = el("div", { class: "tail-list" }, el("div", { class: "hint" }, "Start the live tail to stream messages in real time."));
  let received = 0;
  let es = null;

  const startBtn = el("button", { class: "btn btn-sm btn-success" }, "▶ Start");
  const stopBtn = el("button", { class: "btn btn-sm", disabled: "true" }, "■ Stop");

  const stop = () => {
    if (es) { try { es.close(); } catch { /* ignore */ } state.tails.delete(es); es = null; }
    liveDot.classList.add("hidden");
    statusText.textContent = "Stopped.";
    startBtn.disabled = false;
    stopBtn.disabled = true;
  };

  const start = () => {
    stop();
    received = 0;
    countBadge.textContent = "0";
    list.innerHTML = "";
    list.appendChild(el("div", { class: "hint" }, "Listening for messages…"));

    const url = apiUrl(opts.path);
    if (state.project) url.searchParams.set("project", state.project);

    es = new EventSource(url);
    state.tails.add(es);
    const myEs = es;
    statusText.textContent = "Connecting…";
    startBtn.disabled = true;
    stopBtn.disabled = false;

    es.onopen = () => {
      liveDot.classList.remove("hidden");
      statusText.textContent = "Live — streaming messages.";
    };
    es.onmessage = (e) => {
      if (!e.data) return;
      let m;
      try { m = JSON.parse(e.data); } catch { return; }
      if (received === 0) list.innerHTML = "";
      received += 1;
      countBadge.textContent = String(received);
      const card = messageCard(m);
      card.classList.add("msg-new");
      list.insertBefore(card, list.firstChild);
      while (list.children.length > 200) list.removeChild(list.lastChild);
    };
    es.onerror = () => {
      if (es === myEs) {
        toast(`Live tail disconnected${opts.name ? " (" + opts.name + ")" : ""}`, "error");
        stop();
      }
    };
  };

  startBtn.addEventListener("click", start);
  stopBtn.addEventListener("click", stop);

  const heading = opts.mono
    ? el("h3", { style: "font-size:13px" }, [el("span", { class: "mono" }, opts.mono)])
    : el("h3", {}, opts.title || "Live tail");

  return el("div", { class: "section tail-section" }, [
    el("div", { class: "section-head" }, [
      el("div", { style: "display:flex;align-items:center;gap:10px;min-width:0" }, [
        heading, liveDot, statusText,
      ]),
      el("div", { style: "display:flex;align-items:center;gap:10px" }, [
        el("span", { class: "hint" }, "received"), countBadge, startBtn, stopBtn,
      ]),
    ]),
    el("div", { class: "section-body" }, [
      (opts.compact || !opts.hint) ? null : el("div", { class: "hint", style: "margin-bottom:10px" }, opts.hint),
      list,
    ].filter(Boolean)),
  ]);
}

function messageCard(m) {
  const attrEntries = Object.entries(m.attributes || {});
  const body = el("div", { class: "msg-card-body" }, [
    el("div", { class: "field" }, [el("label", {}, "Data"), el("div", { class: "msg-data" }, m.data || "(empty)")]),
    el("div", { class: "meta-grid", style: "margin:12px 0 0" }, [
      metaCard("Message ID", m.messageId || "—"),
      metaCard("Publish time", m.publishTime || "—"),
      metaCard("Delivery attempt", String(m.deliveryAttempt || 0)),
      m.orderingKey ? metaCard("Ordering key", m.orderingKey) : null,
      m.source ? metaCard("Observed on", m.source) : null,
    ].filter(Boolean)),
    attrEntries.length ? el("div", { style: "margin-top:12px" }, [
      el("label", { class: "hint" }, "Attributes"),
      el("div", { class: "attr-tags" }, attrEntries.map(([k, v]) =>
        el("span", { class: "attr-tag" }, [el("b", {}, k + ": "), v]))),
    ]) : null,
  ].filter(Boolean));

  const preview = (m.data || "(empty)").replace(/\s+/g, " ").slice(0, 120);
  const card = el("div", { class: "msg-card" }, [
    el("div", { class: "msg-card-head" }, [
      el("span", { class: "chev" }, "›"),
      el("span", { class: "msg-id" }, "#" + (m.messageId || "?")),
      el("span", { class: "msg-preview" }, preview),
    ]),
    body,
  ]);
  card.querySelector(".msg-card-head").addEventListener("click", () => card.classList.toggle("open"));
  return card;
}

// ---------------------------------------------------------- Purge (destructive)
function confirmPurgeSub(subId) {
  destructiveConfirm(
    "Purge subscription",
    `Drain and discard ALL messages from "${subId}"? This acknowledges every message, so any consumers sharing this subscription will NOT receive them. This cannot be undone.`,
    async () => {
      const res = await api(`/api/subscriptions/${encodeURIComponent(subId)}/purge`, { method: "POST" });
      toast(`Purged ${res.purged} message(s) from "${subId}"`, "success");
    },
    `Purging ${subId}… (draining backlog)`
  );
}

function confirmPurgeTopic(topicId) {
  destructiveConfirm(
    "Purge all subscriptions",
    `Drain and discard ALL messages from EVERY subscription on topic "${topicId}"? Consumers on those subscriptions will NOT receive the discarded messages. This cannot be undone.`,
    async () => {
      const res = await api(`/api/topics/${encodeURIComponent(topicId)}/purge`, { method: "POST" });
      toast(`Purged ${res.totalPurged} message(s) across ${Object.keys(res.perSubscription || {}).length} subscription(s)`, "success");
      renderTopicDetail(topicId);
    },
    `Purging all subscriptions on ${topicId}…`
  );
}

function destructiveConfirm(title, message, action, busyMessage) {
  const confirm = el("button", { class: "btn btn-danger" }, "Purge");
  const cancel = el("button", { class: "btn", onclick: closeModal }, "Cancel");
  confirm.addEventListener("click", async () => {
    confirm.disabled = true;
    cancel.disabled = true;
    try {
      await withBusy(busyMessage || "Working…", action);
      $("#modalOverlay").classList.add("hidden");
    } catch (e) {
      toast(e.message, "error", "Purge failed");
      confirm.disabled = false;
      cancel.disabled = false;
    }
  });
  openModal(title, el("p", { style: "margin:0;color:var(--text-dim);line-height:1.5" }, message), [cancel, confirm]);
}

// --------------------------------------------------------------- Helpers
function metaCard(k, v, onClick) {
  const value = onClick
    ? el("div", { class: "v row-link", onclick: onClick }, v)
    : el("div", { class: "v" }, v);
  return el("div", { class: "meta-card" }, [el("div", { class: "k" }, k), value]);
}

function table(headers, rows) {
  return el("table", { class: "table" }, [
    el("thead", {}, el("tr", {}, headers.map((h) => el("th", {}, h)))),
    el("tbody", {}, rows),
  ]);
}

function loadingNode(text) {
  return el("div", { class: "loading" }, [el("span", { class: "spinner" }), " " + (text || "Loading…")]);
}

// --------------------------------------------------------- Auth gate (ADC)
let authPollTimer = null;

function showAuthGate() { $("#authGate").classList.remove("hidden"); }
function hideAuthGate() {
  if (authPollTimer) { clearInterval(authPollTimer); authPollTimer = null; }
  $("#authGate").classList.add("hidden");
}

function setAuthMsg(text, isError) {
  const m = $("#authMsg");
  m.textContent = text;
  m.className = "auth-msg" + (isError ? " error" : "");
}

function renderAuthActions(status) {
  const actions = $("#authActions");
  actions.innerHTML = "";

  const retry = el("button", { class: "btn", onclick: () => checkAuthAndStart(true) }, "I’ve signed in — retry");

  if (status.loginAvailable) {
    const signIn = el("button", { class: "btn btn-google" }, [
      el("span", { class: "g" }, "G"), "Sign in with Google (ADC)",
    ]);
    signIn.addEventListener("click", () => startLogin(signIn));
    actions.appendChild(signIn);
    actions.appendChild(retry);
  } else {
    actions.appendChild(retry);
  }
}

async function startLogin(button) {
  button.disabled = true;
  setAuthMsg("Opening a browser window for Google sign-in…");
  try {
    const res = await api("/api/auth/login", { method: "POST" });
    setAuthMsg(res.message || "Complete sign-in in the browser window, then return here.");
  } catch (e) {
    setAuthMsg(e.message, true);
    button.disabled = false;
    return;
  }
  $("#authActions").innerHTML = "";
  $("#authActions").appendChild(el("div", { class: "auth-spin" }, [
    el("span", { class: "spinner" }), " Waiting for sign-in to complete…",
  ]));
  if (authPollTimer) clearInterval(authPollTimer);
  authPollTimer = setInterval(pollAuthThenEnter, 2500);
}

async function pollAuthThenEnter() {
  let status;
  try { status = await api("/api/auth/status"); } catch { return; }
  if (status.authenticated) {
    // Credentials are in place — redirect back into the tool.
    enterTool();
  } else if (!status.loginInProgress && status.lastError) {
    if (authPollTimer) { clearInterval(authPollTimer); authPollTimer = null; }
    setAuthMsg(status.lastError, true);
    renderAuthActions(status);
  }
}

function enterTool() {
  hideAuthGate();
  loadConfig().then(loadAll);
}

async function checkAuthAndStart(fromRetry) {
  let status;
  try {
    status = await api("/api/auth/status");
  } catch (e) {
    // Backend reachable problem — let the user into the tool; errors will surface there.
    enterTool();
    return;
  }
  if (status.authenticated) {
    enterTool();
    return;
  }
  if (fromRetry) setAuthMsg("Still no credentials found. Finish the Google sign-in, then retry.", true);
  else setAuthMsg("This tool needs Google Cloud credentials (ADC) to read your Pub/Sub topics.");
  renderAuthActions(status);
  showAuthGate();
}

// ============================================================ Mongo Compare
const mongo = { config: null, left: null, right: null };
let mongoInited = false;

// ---- View switching (Pub/Sub <-> Mongo Compare <-> Bulk Post)
function setView(view) {
  state.view = view;
  const isMongo = view === "mongo";
  const isBulk = view === "bulk";
  const isCleanup = view === "cleanup";
  const isPubsub = !isMongo && !isBulk && !isCleanup;
  $("#pubsubView").classList.toggle("hidden", !isPubsub);
  $("#mongoView").classList.toggle("hidden", !isMongo);
  $("#bulkView").classList.toggle("hidden", !isBulk);
  $("#cleanupView").classList.toggle("hidden", !isCleanup);
  $("#navPubsub").classList.toggle("active", isPubsub);
  $("#navMongo").classList.toggle("active", isMongo);
  $("#navBulk").classList.toggle("active", isBulk);
  $("#navCleanup").classList.toggle("active", isCleanup);
  // The project field is irrelevant for the Mongo-backed views.
  $("#projectField").classList.toggle("hidden", isMongo || isCleanup);
  document.body.classList.toggle("view-mongo", isMongo);
  document.body.classList.toggle("view-bulk", isBulk);
  document.body.classList.toggle("view-cleanup", isCleanup);
  if (isMongo) initMongo();
  if (isBulk) initBulk();
  if (isCleanup) initCleanup();
}

async function initMongo() {
  if (mongoInited) return;
  mongoInited = true;
  try {
    mongo.config = await api("/api/mongo/config");
  } catch (e) {
    mongoInited = false;
    toast(e.message, "error", "Failed to load Mongo config");
    return;
  }
  buildMongoPanel("left", $("#mongoPanelLeft"));
  buildMongoPanel("right", $("#mongoPanelRight"));
}

function buildMongoPanel(side, container) {
  const envSel = el("select", { class: "m-select" }, [el("option", { value: "" }, "Environment…")]);
  (mongo.config.environments || []).forEach((e) => envSel.appendChild(el("option", { value: e.name }, e.name)));

  const dbSel = el("select", { class: "m-select" }, [el("option", { value: "" }, "Database…")]);
  const collSel = el("select", { class: "m-select" }, [el("option", { value: "" }, "Collection…")]);
  const idInput = el("input", { class: "m-input", type: "text", placeholder: "productId to fetch", autocomplete: "off" });
  const loadBtn = el("button", { class: "btn btn-sm btn-primary" }, "Load");
  const delBtn = el("button", { class: "btn btn-sm btn-danger" }, "Delete");
  const statusEl = el("div", { class: "mongo-status hint" }, "No document loaded.");
  const docSel = el("select", { class: "m-select doc-sel" });
  const docCount = el("span", { class: "doc-count" });
  const docbar = el("div", { class: "mongo-docbar hidden" }, [
    el("span", { class: "doc-label" }, "Document"),
    docSel,
    docCount,
  ]);
  const body = el("div", { class: "mongo-json" });

  envSel.addEventListener("change", () => onEnvChange(side));
  dbSel.addEventListener("change", () => onDbChange(side));
  collSel.addEventListener("change", () => { mongo[side].loaded = false; });
  docSel.addEventListener("change", () => onDocChange(side));
  idInput.addEventListener("keydown", (e) => { if (e.key === "Enter" && !isBusy()) loadPanelDoc(side); });
  loadBtn.addEventListener("click", () => { if (!isBusy()) loadPanelDoc(side); });
  delBtn.addEventListener("click", () => { if (!isBusy()) confirmMongoDelete(side); });

  const controls = el("div", { class: "mongo-controls" }, [
    el("div", { class: "m-field" }, [el("label", {}, "Environment"), envSel]),
    el("div", { class: "m-field" }, [el("label", {}, "Database"), dbSel]),
    el("div", { class: "m-field" }, [el("label", {}, "Collection"), collSel]),
    el("div", { class: "m-field grow" }, [el("label", {}, "productId"), idInput]),
    el("div", { class: "m-field m-actions" }, [loadBtn, delBtn]),
  ]);

  container.innerHTML = "";
  container.appendChild(el("div", { class: "mongo-panel-head" }, [
    el("span", { class: "mongo-panel-tag" }, side === "left" ? "A" : "B"),
    statusEl,
  ]));
  container.appendChild(controls);
  container.appendChild(docbar);
  container.appendChild(body);

  mongo[side] = {
    envSel, dbSel, collSel, idInput, docSel, docCount, docbar, body, statusEl,
    loaded: false, obj: null, raw: null, docs: [], selectedIndex: 0,
  };
}

// Rebuild the document selector from the panel's fetched documents and point the
// panel at the currently-selected one.
function refreshDocSelector(side) {
  const p = mongo[side];
  p.docSel.innerHTML = "";
  (p.docs || []).forEach((d, i) => p.docSel.appendChild(el("option", { value: String(i) }, `_id ${d.id}`)));
  const has = p.docs && p.docs.length > 0;
  p.docbar.classList.toggle("hidden", !has);
  p.docCount.textContent = has ? `${p.docs.length} match${p.docs.length === 1 ? "" : "es"}` : "";
  if (has) {
    if (p.selectedIndex >= p.docs.length) p.selectedIndex = 0;
    p.docSel.value = String(p.selectedIndex);
    p.docSel.disabled = p.docs.length <= 1;
    const d = p.docs[p.selectedIndex];
    p.obj = d.obj;
    p.raw = d.raw;
    p.loaded = true;
  } else {
    p.obj = null;
    p.raw = null;
    p.loaded = false;
  }
}

function onDocChange(side) {
  const p = mongo[side];
  p.selectedIndex = Number(p.docSel.value) || 0;
  const d = (p.docs || [])[p.selectedIndex];
  if (d) { p.obj = d.obj; p.raw = d.raw; p.loaded = true; }
  renderMongoDocs();
}

function onEnvChange(side) {
  const p = mongo[side];
  p.dbSel.innerHTML = "";
  p.dbSel.appendChild(el("option", { value: "" }, "Database…"));
  p.collSel.innerHTML = "";
  p.collSel.appendChild(el("option", { value: "" }, "Collection…"));
  p.loaded = false;
  const envCfg = (mongo.config.environments || []).find((e) => e.name === p.envSel.value);
  if (envCfg) (envCfg.databases || []).forEach((d) => p.dbSel.appendChild(el("option", { value: d }, d)));
}

async function onDbChange(side) {
  const p = mongo[side];
  p.collSel.innerHTML = "";
  p.collSel.appendChild(el("option", { value: "" }, "Collection…"));
  p.loaded = false;
  const env = p.envSel.value;
  const db = p.dbSel.value;
  if (!env || !db) return;
  await withBusy("Loading collections…", async () => {
    try {
      const cols = await api("/api/mongo/collections", { params: { env, db } });
      cols.forEach((c) => p.collSel.appendChild(el("option", { value: c }, c)));
    } catch (e) {
      toast(e.message, "error", "Failed to load collections");
    }
  });
}

async function loadPanelDoc(side) {
  const p = mongo[side];
  const env = p.envSel.value;
  const db = p.dbSel.value;
  const collection = p.collSel.value;
  const productId = p.idInput.value.trim();
  if (!env || !db || !collection) { toast("Pick an environment, database and collection.", "error"); return; }
  if (!productId) { toast("Enter a productId to load.", "error"); return; }
  await withBusy("Loading document…", async () => {
    try {
      const res = await api("/api/mongo/document", { params: { env, db, collection, productId } });
      if (res.found) {
        p.docs = (res.documents || []).map((d) => ({ id: d.id, raw: d.json, obj: JSON.parse(d.json) }));
        p.selectedIndex = 0;
        refreshDocSelector(side);
        const n = p.docs.length;
        p.statusEl.textContent = `Loaded · ${collection} · ${n} document${n === 1 ? "" : "s"} for productId ${productId}`;
        p.statusEl.className = "mongo-status ok";
      } else {
        p.docs = [];
        p.selectedIndex = 0;
        refreshDocSelector(side);
        p.statusEl.textContent = `No document with productId "${productId}" in ${collection}.`;
        p.statusEl.className = "mongo-status warn";
      }
    } catch (e) {
      p.docs = [];
      refreshDocSelector(side);
      p.statusEl.textContent = e.message;
      p.statusEl.className = "mongo-status warn";
      toast(e.message, "error", "Load failed");
    }
    renderMongoDocs();
  });
}

async function mongoCompare() {
  await loadPanelDoc("left");
  await loadPanelDoc("right");
}

function mongoGetSel(side) {
  const p = mongo[side];
  return { env: p.envSel.value, db: p.dbSel.value, coll: p.collSel.value, id: p.idInput.value };
}

async function mongoApplySel(side, sel) {
  const p = mongo[side];
  p.envSel.value = sel.env;
  onEnvChange(side);
  p.dbSel.value = sel.db;
  await onDbChange(side);
  p.collSel.value = sel.coll;
  p.idInput.value = sel.id;
}

async function mongoSwap() {
  const a = mongoGetSel("left");
  const b = mongoGetSel("right");
  await withBusy("Swapping sides…", async () => {
    await mongoApplySel("left", b);
    await mongoApplySel("right", a);
  });
  const L = mongo.left, R = mongo.right;
  [L.docs, R.docs] = [R.docs, L.docs];
  [L.selectedIndex, R.selectedIndex] = [R.selectedIndex, L.selectedIndex];
  refreshDocSelector("left");
  refreshDocSelector("right");
  const lt = L.statusEl.textContent, lc = L.statusEl.className;
  L.statusEl.textContent = R.statusEl.textContent; L.statusEl.className = R.statusEl.className;
  R.statusEl.textContent = lt; R.statusEl.className = lc;
  renderMongoDocs();
}

function confirmMongoDelete(side) {
  const p = mongo[side];
  const env = p.envSel.value;
  const db = p.dbSel.value;
  const collection = p.collSel.value;
  if (!env || !db || !collection) {
    toast("Pick an environment, database and collection first.", "error");
    return;
  }
  if (!p.loaded || !p.docs || !p.docs.length) {
    toast("Load a document first, then choose which one to delete.", "error");
    return;
  }
  const id = p.docs[p.selectedIndex].id;
  const cancel = el("button", { class: "btn", onclick: closeModal }, "Cancel");
  const confirm = el("button", { class: "btn btn-danger" }, "Delete document");
  confirm.addEventListener("click", async () => {
    confirm.disabled = true;
    cancel.disabled = true;
    try {
      await withBusy("Deleting document…", async () => {
        const res = await api("/api/mongo/document", { method: "DELETE", params: { env, db, collection, id } });
        if (res.deleted > 0) {
          toast(`Deleted _id "${id}" from ${env} / ${db} / ${collection}. It can now be reprocessed.`, "success");
        } else {
          toast(`No document deleted — _id "${id}" was not found.`, "info");
        }
        await loadPanelDoc(side);
      });
      $("#modalOverlay").classList.add("hidden");
    } catch (e) {
      toast(e.message, "error", "Delete failed");
      confirm.disabled = false;
      cancel.disabled = false;
    }
  });
  const msg = `Permanently delete the document _id "${id}" from ${env} / ${db} / ${collection}? `
    + "This is destructive and cannot be undone. The source pipeline will typically "
    + "re-seed and reprocess this document.";
  openModal("Delete document", el("p", { style: "margin:0;color:var(--text-dim);line-height:1.5" }, msg), [cancel, confirm]);
}

// ---- Attribute-wise, key-aligned JSON diff --------------------------------
const MISSING = Symbol("missing");

function jtype(v) {
  if (v === MISSING) return "missing";
  if (Array.isArray(v)) return "array";
  if (v && typeof v === "object") return "object";
  return "scalar";
}

function jDeepEqual(a, b) {
  const ta = jtype(a), tb = jtype(b);
  if (ta !== tb) return false;
  if (ta === "scalar" || ta === "missing") return a === b;
  if (ta === "array") {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!jDeepEqual(a[i], b[i])) return false;
    return true;
  }
  const ak = Object.keys(a).sort();
  const bk = Object.keys(b).sort();
  if (ak.length !== bk.length) return false;
  for (let i = 0; i < ak.length; i++) {
    if (ak[i] !== bk[i]) return false;
    if (!jDeepEqual(a[ak[i]], b[ak[i]])) return false;
  }
  return true;
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}
function jfmt(v) {
  try { return JSON.stringify(v); } catch { return String(v); }
}
function jKey(key) {
  if (key === null) return "";
  if (typeof key === "number") return `<span class="j-k">${key}</span>: `;
  return `<span class="j-k">"${esc(key)}":</span> `;
}
function jVal(v) { return `<span class="j-v">${esc(jfmt(v))}</span>`; }
function jAbsent(key) { return `${jKey(key)}<span class="j-v j-dash">—</span>`; }

// Split a string into word/whitespace tokens so we can diff at word granularity.
function jtokenize(s) { return s.match(/\s+|[^\s]+/g) || []; }

function jRenderTokens(tokens) {
  let out = "";
  for (const tk of tokens) out += tk.eq ? esc(tk.t) : `<span class="j-chg">${esc(tk.t)}</span>`;
  return out;
}

// Word-level LCS diff of two strings; returns [leftInnerHtml, rightInnerHtml]
// with only the differing tokens wrapped in <span class="j-chg">.
function jWordDiff(a, b) {
  const A = jtokenize(a);
  const B = jtokenize(b);
  const n = A.length, m = B.length;
  // Guard against pathologically large values; fall back to whole-value highlight.
  if (n * m > 250000) {
    return [`<span class="j-chg">${esc(a)}</span>`, `<span class="j-chg">${esc(b)}</span>`];
  }
  const dp = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const left = [], right = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (A[i] === B[j]) { left.push({ t: A[i], eq: true }); right.push({ t: B[j], eq: true }); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { left.push({ t: A[i], eq: false }); i++; }
    else { right.push({ t: B[j], eq: false }); j++; }
  }
  while (i < n) { left.push({ t: A[i], eq: false }); i++; }
  while (j < m) { right.push({ t: B[j], eq: false }); j++; }
  return [jRenderTokens(left), jRenderTokens(right)];
}

function jPairStatus(leftPresent, rightPresent, equal) {
  if (!leftPresent && !rightPresent) return ["j-missing", "j-missing"];
  if (!leftPresent) return ["j-missing", "j-diff"];
  if (!rightPresent) return ["j-diff", "j-missing"];
  return equal ? ["j-eq", "j-eq"] : ["j-diff", "j-diff"];
}

function jChild(container, type, key) {
  if (type === "object") {
    return Object.prototype.hasOwnProperty.call(container, key) ? container[key] : MISSING;
  }
  if (type === "array") {
    const i = Number(key);
    return i < container.length ? container[i] : MISSING;
  }
  return MISSING;
}

// Recursively build aligned rows; each row carries a left/right cell so the two
// columns line up 1:1. Keys are sorted and matched across both sides.
function jDiff(left, right, key, depth, rows, stats) {
  const lt = jtype(left), rt = jtype(right);
  const lContainer = lt === "object" || lt === "array";
  const rContainer = rt === "object" || rt === "array";
  const recurse = (lContainer || rContainer) && lt !== "scalar" && rt !== "scalar";

  if (recurse) {
    const anyObject = lt === "object" || rt === "object";
    const arrMode = !anyObject;
    let keys;
    if (arrMode) {
      const len = Math.max(lContainer ? left.length : 0, rContainer ? right.length : 0);
      keys = Array.from({ length: len }, (_, i) => i);
    } else {
      const set = new Set();
      if (lt === "object") Object.keys(left).forEach((k) => set.add(k));
      if (rt === "object") Object.keys(right).forEach((k) => set.add(k));
      if (lt === "array") left.forEach((_, i) => set.add(String(i)));
      if (rt === "array") right.forEach((_, i) => set.add(String(i)));
      keys = [...set].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    }
    const eq = lContainer && rContainer && jDeepEqual(left, right);
    const [lcls, rcls] = jPairStatus(lContainer, rContainer, eq);
    const open = arrMode ? "[" : "{";
    const close = arrMode ? "]" : "}";
    rows.push({
      depth,
      left: { cls: lcls, html: lContainer ? jKey(key) + open : jAbsent(key) },
      right: { cls: rcls, html: rContainer ? jKey(key) + open : jAbsent(key) },
    });
    for (const ck of keys) {
      const lchild = jChild(left, lt, ck);
      const rchild = jChild(right, rt, ck);
      jDiff(lchild, rchild, arrMode ? Number(ck) : ck, depth + 1, rows, stats);
    }
    rows.push({
      depth,
      left: { cls: lcls, html: lContainer ? close : "" },
      right: { cls: rcls, html: rContainer ? close : "" },
    });
    return;
  }

  const lPresent = lt !== "missing";
  const rPresent = rt !== "missing";
  const eq = lPresent && rPresent && jDeepEqual(left, right);
  const [lcls, rcls] = jPairStatus(lPresent, rPresent, eq);
  if (!lPresent || !rPresent) stats.missing++;
  else if (eq) stats.eq++;
  else stats.diff++;

  // Build the inner value HTML, highlighting the differing parts in-place.
  let lInner = "", rInner = "";
  if (lPresent && rPresent) {
    if (eq) {
      lInner = esc(jfmt(left));
      rInner = esc(jfmt(right));
    } else if (typeof left === "string" && typeof right === "string") {
      const [la, rb] = jWordDiff(left, right);
      lInner = `"${la}"`;
      rInner = `"${rb}"`;
    } else {
      lInner = `<span class="j-chg">${esc(jfmt(left))}</span>`;
      rInner = `<span class="j-chg">${esc(jfmt(right))}</span>`;
    }
  } else if (lPresent) {
    lInner = `<span class="j-chg">${esc(jfmt(left))}</span>`;
  } else if (rPresent) {
    rInner = `<span class="j-chg">${esc(jfmt(right))}</span>`;
  }

  rows.push({
    depth,
    left: { cls: lcls, html: lPresent ? jKey(key) + `<span class="j-v">${lInner}</span>` : jAbsent(key) },
    right: { cls: rcls, html: rPresent ? jKey(key) + `<span class="j-v">${rInner}</span>` : jAbsent(key) },
  });
}

function jLine(depth, cell) {
  return el("div", {
    class: `j-line ${cell.cls}`,
    style: `padding-left:${8 + depth * 16}px`,
    html: cell.html && cell.html.length ? cell.html : "&nbsp;",
  });
}

function renderRawJson(bodyEl, raw) {
  bodyEl.innerHTML = "";
  String(raw || "").split("\n").forEach((line) => {
    bodyEl.appendChild(el("div", { class: "j-line j-plain" }, line.length ? line : " "));
  });
}

function renderMongoSummary(stats) {
  const s = $("#mongoSummary");
  s.innerHTML = "";
  s.appendChild(el("span", { class: "sum sum-eq" }, `${stats.eq} matching`));
  s.appendChild(el("span", { class: "sum sum-diff" }, `${stats.diff} differing`));
  s.appendChild(el("span", { class: "sum sum-missing" }, `${stats.missing} only on one side`));
}

function renderMongoDocs() {
  const L = mongo.left, R = mongo.right;
  if (!L || !R) return;
  L.body.innerHTML = "";
  R.body.innerHTML = "";
  $("#mongoSummary").innerHTML = "";
  const diffOnly = $("#mongoDiffOnly").checked;

  if (L.loaded && R.loaded) {
    const rows = [];
    const stats = { eq: 0, diff: 0, missing: 0 };
    jDiff(L.obj, R.obj, null, 0, rows, stats);
    rows.forEach((r) => {
      if (diffOnly && r.left.cls === "j-eq" && r.right.cls === "j-eq") return;
      L.body.appendChild(jLine(r.depth, r.left));
      R.body.appendChild(jLine(r.depth, r.right));
    });
    renderMongoSummary(stats);
  } else {
    if (L.loaded) renderRawJson(L.body, L.raw);
    if (R.loaded) renderRawJson(R.body, R.raw);
  }
}

// ============================================================ Bulk Post
const bulk = {
  records: [],   // parsed items (objects for CSV; objects/values for JSON)
  fields: [],    // header/field names available for filtering
  filters: [],   // [{ field, op, value }]
  matchMode: "all", // "all" (AND) | "any" (OR)
  format: null,
  fileName: null,
  rawText: null,
  schemas: [],   // available message schemas (from /api/schemas)
  schema: null,  // selected schema descriptor (or null for raw)
  _fieldMap: new Map(), // name -> field def for the selected coercible schema
  _filtered: null, // cache of the current filtered records (invalidated on change)
};
let bulkInited = false;

// Small debounce so typing in a filter value doesn't re-scan the whole file per keystroke.
function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}
const scheduleBulkRefresh = debounce(() => refreshBulkPreview(), 160);

// Filter operators. `needsValue: false` means the value box is not used.
const BULK_OPS = [
  { v: "eq", label: "equals", needsValue: true },
  { v: "ne", label: "not equals", needsValue: true },
  { v: "contains", label: "contains", needsValue: true },
  { v: "ncontains", label: "does not contain", needsValue: true },
  { v: "starts", label: "starts with", needsValue: true },
  { v: "ends", label: "ends with", needsValue: true },
  { v: "gt", label: "greater than", needsValue: true },
  { v: "gte", label: "greater or equal", needsValue: true },
  { v: "lt", label: "less than", needsValue: true },
  { v: "lte", label: "less or equal", needsValue: true },
  { v: "empty", label: "is empty", needsValue: false },
  { v: "notempty", label: "is not empty", needsValue: false },
];

function initBulk() {
  buildBulkTopicSelect();
  if (bulkInited) return;
  bulkInited = true;
  loadBulkSchemas();
  const dz = $("#bulkDropzone");
  const input = $("#bulkFileInput");
  dz.addEventListener("click", () => input.click());
  dz.addEventListener("dragover", (e) => { e.preventDefault(); dz.classList.add("dragover"); });
  dz.addEventListener("dragleave", () => dz.classList.remove("dragover"));
  dz.addEventListener("drop", (e) => {
    e.preventDefault();
    dz.classList.remove("dragover");
    const f = e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) handleBulkFile(f);
  });
  input.addEventListener("change", () => { if (input.files[0]) handleBulkFile(input.files[0]); });
  $("#bulkDelimiter").addEventListener("change", () => { if (bulk.rawText != null) reparseBulk(); });
  $("#bulkSchema").addEventListener("change", () => {
    const id = $("#bulkSchema").value;
    bulk.schema = bulk.schemas.find((s) => s.id === id) || null;
    bulk._fieldMap = schemaFieldMap();
    if (bulk.rawText != null) reparseBulk();
  });
  $("#bulkAddFilter").addEventListener("click", () => {
    if (!bulk.fields.length) { toast("Load a file with headers first.", "info"); return; }
    bulk.filters.push({ field: "", op: "eq", values: [] });
    renderBulkFilters();
  });
  $("#bulkMatchMode").addEventListener("change", () => {
    bulk.matchMode = $("#bulkMatchMode").value;
    refreshBulkPreview();
  });
  $("#bulkPostBtn").addEventListener("click", () => { if (!isBusy()) bulkPost(); });
  $("#bulkClearBtn").addEventListener("click", bulkClear);
}

function buildBulkTopicSelect() {
  const sel = $("#bulkTopic");
  const current = sel.value;
  sel.innerHTML = "";
  sel.appendChild(el("option", { value: "" }, "Select a topic…"));
  (state.groups || []).forEach((g) => {
    const og = el("optgroup", { label: g.name });
    (g.topics || []).forEach((t) => og.appendChild(el("option", { value: t }, t)));
    sel.appendChild(og);
  });
  if (current) sel.value = current;
}

// ---- Schemas
async function loadBulkSchemas() {
  try {
    bulk.schemas = await api("/api/schemas");
  } catch {
    bulk.schemas = [];
  }
  buildBulkSchemaSelect();
}

function buildBulkSchemaSelect() {
  const sel = $("#bulkSchema");
  const current = sel.value;
  sel.innerHTML = "";
  sel.appendChild(el("option", { value: "" }, "None (raw strings)"));
  bulk.schemas.forEach((s) => {
    const label = s.coercible ? s.title : `${s.title} (structural)`;
    sel.appendChild(el("option", { value: s.id }, label));
  });
  if (current) sel.value = current;
}

function schemaFieldMap() {
  const m = new Map();
  if (bulk.schema && bulk.schema.coercible) {
    (bulk.schema.fields || []).forEach((f) => m.set(f.name, f));
  }
  return m;
}

// Coerce a single value to the type declared by its schema field. Strings stay
// strings (this is the core fix); only number/integer/boolean/array/object
// fields are converted. Empty → null for nullable fields.
function coerceBySchema(value, def) {
  const types = def.types || [];
  const isStr = typeof value === "string";
  const empty = value === undefined || value === null || (isStr && value.trim() === "");
  if (empty) {
    if (def.nullable) return null;
    if (types.includes("string")) return "";
    return null;
  }
  if (types.includes("string")) return isStr ? value : String(value);
  if (types.includes("integer")) {
    const s = String(value).trim();
    if (/^-?\d+$/.test(s)) { const n = Number(s); if (Number.isSafeInteger(n)) return n; }
    return String(value);
  }
  if (types.includes("number")) {
    const n = Number(String(value).trim());
    return Number.isFinite(n) ? n : String(value);
  }
  if (types.includes("boolean")) {
    const s = String(value).trim().toLowerCase();
    if (["true", "1", "y", "yes"].includes(s)) return true;
    if (["false", "0", "n", "no"].includes(s)) return false;
    return String(value);
  }
  if (types.includes("array") || types.includes("object")) {
    if (isStr) { try { return JSON.parse(value); } catch { return value; } }
    return value;
  }
  return isStr ? value : String(value);
}

function applySchemaToRecord(obj, fieldMap) {
  if (!fieldMap.size) return obj;
  const out = {};
  Object.keys(obj).forEach((k) => {
    const def = fieldMap.get(k);
    out[k] = def ? coerceBySchema(obj[k], def) : obj[k];
  });
  return out;
}

// Best-effort, client-side validation that mirrors the consumer's schema checks
// (required, nullability, enum, and un-coercible number/boolean types) so bad
// rows can be surfaced before publishing.
function validateRecord(obj) {
  const s = bulk.schema;
  const issues = [];
  if (!s || !s.coercible) return issues;
  (s.required || []).forEach((name) => {
    const v = obj[name];
    if (v === undefined || v === null || (typeof v === "string" && v.trim() === "")) {
      issues.push(`${name} is required`);
    }
  });
  (s.fields || []).forEach((def) => {
    if (!(def.name in obj)) return;
    const v = obj[def.name];
    if (v === null) { if (!def.nullable) issues.push(`${def.name} must not be null`); return; }
    if (def.enum && def.enum.length && !def.enum.includes(String(v))) {
      issues.push(`${def.name}="${v}" not in enum`);
    }
    const types = def.types || [];
    if (!types.includes("string")) {
      if (types.includes("integer") && !(typeof v === "number" && Number.isInteger(v))) issues.push(`${def.name} not integer`);
      else if (types.includes("number") && typeof v !== "number") issues.push(`${def.name} not number`);
      else if (types.includes("boolean") && typeof v !== "boolean") issues.push(`${def.name} not boolean`);
    }
  });
  if (s.additionalProperties === false) {
    const allowed = new Set((s.fields || []).map((f) => f.name));
    Object.keys(obj).forEach((k) => { if (!allowed.has(k)) issues.push(`unexpected field "${k}"`); });
  }
  return issues;
}

// Count invalid records and gather a few sample messages for display.
function countInvalid(records) {
  let invalid = 0;
  const samples = [];
  if (!bulk.schema || !bulk.schema.coercible) return { invalid, samples };
  for (let i = 0; i < records.length; i++) {
    const issues = validateRecord(records[i]);
    if (issues.length) {
      invalid++;
      if (samples.length < 5) samples.push(`row ${i + 1}: ${issues.slice(0, 4).join("; ")}`);
    }
  }
  return { invalid, samples };
}

function handleBulkFile(file) {
  bulk.fileName = file.name;
  const reader = new FileReader();
  reader.onload = () => { bulk.rawText = String(reader.result || ""); reparseBulk(); };
  reader.onerror = () => toast("Could not read the file.", "error");
  reader.readAsText(file);
}

function reparseBulk() {
  const ext = (bulk.fileName || "").toLowerCase().split(".").pop();
  const choice = $("#bulkDelimiter").value;
  const fieldMap = bulk._fieldMap || new Map();
  let result;
  try {
    result = ext === "json"
      ? parseJsonMessages(bulk.rawText, fieldMap)
      : parseDelimited(bulk.rawText, choice, fieldMap);
  } catch (e) {
    bulk.records = [];
    bulk.fields = [];
    bulk.filters = [];
    invalidateBulkFilter();
    renderBulkMeta({ error: e.message });
    renderBulkFilters();
    renderBulkPreview([], { error: e.message });
    updateBulkButtons();
    return;
  }
  bulk.records = result.records;
  bulk.format = result.format;
  bulk.fields = computeBulkFields(result);
  bulk.filters = []; // fresh file → drop stale filters
  invalidateBulkFilter();
  renderBulkMeta(result);
  renderBulkFilters();
  refreshBulkPreview();
}

// Union of field names available for filtering: header row for delimited files,
// or the union of object keys for JSON.
function computeBulkFields(result) {
  if (result.headers && result.headers.length) return result.headers.filter(Boolean);
  const seen = new Set();
  const out = [];
  (result.records || []).forEach((r) => {
    if (r && typeof r === "object" && !Array.isArray(r)) {
      Object.keys(r).forEach((k) => { if (!seen.has(k)) { seen.add(k); out.push(k); } });
    }
  });
  return out;
}

// ---- Parsers
function parseJsonMessages(text, fieldMap) {
  const data = JSON.parse(text);
  const items = Array.isArray(data) ? data : [data];
  const records = (fieldMap && fieldMap.size)
    ? items.map((it) => (it && typeof it === "object" && !Array.isArray(it)) ? applySchemaToRecord(it, fieldMap) : it)
    : items;
  return { format: "JSON", records, delimiter: null };
}

function normalizeDelim(choice) {
  if (choice === "tab") return "\t";
  return choice;
}

function detectDelimiter(line) {
  const cands = [",", "\t", "|", ";"];
  let best = ",";
  let bestCount = 0;
  for (const c of cands) {
    const count = line.split(c).length - 1;
    if (count > bestCount) { bestCount = count; best = c; }
  }
  return best;
}

// Single-pass parser that honors quoted fields (with embedded delimiters/newlines
// and "" escapes). Returns an array of rows, each an array of cell strings.
function parseTable(text, delimiter) {
  const rows = [];
  let row = [];
  let field = "";
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false;
      } else field += ch;
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === delimiter) {
      row.push(field); field = "";
    } else if (ch === "\n") {
      row.push(field); rows.push(row); row = []; field = "";
    } else if (ch !== "\r") {
      field += ch;
    }
  }
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row); }
  return rows;
}

function coerceCell(raw) {
  const v = String(raw).trim();
  if (v === "") return null;
  if (v === "true") return true;
  if (v === "false") return false;
  if (/^-?\d+$/.test(v)) {
    const n = Number(v);
    if (Number.isSafeInteger(n)) return n;
  }
  if (/^-?(\d+\.\d*|\.\d+|\d+)(e[+-]?\d+)?$/i.test(v) && v !== ".") {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return v;
}

function parseDelimited(text, choice, fieldMap) {
  const firstLine = (text.split(/\r?\n/, 1)[0]) || "";
  const delimiter = choice === "auto" ? detectDelimiter(firstLine) : normalizeDelim(choice);
  const rows = parseTable(text, delimiter).filter((r) => !(r.length === 1 && r[0].trim() === ""));
  if (!rows.length) throw new Error("File is empty.");
  const headers = rows[0].map((h) => h.trim());
  if (!headers.some((h) => h.length)) throw new Error("No header row found.");
  const useSchema = fieldMap && fieldMap.size > 0;
  const records = [];
  for (let i = 1; i < rows.length; i++) {
    const cells = rows[i];
    const obj = {};
    headers.forEach((h, idx) => {
      if (!h) return;
      const cell = cells[idx] !== undefined ? cells[idx] : "";
      // With a schema, keep raw strings and let coercion decide types; otherwise
      // fall back to the heuristic auto-coercion.
      obj[h] = useSchema ? String(cell).trim() : coerceCell(cell);
    });
    records.push(useSchema ? applySchemaToRecord(obj, fieldMap) : obj);
  }
  return { format: "CSV/TXT", records, delimiter, headers };
}

// ---- Rendering
function renderBulkMeta(result) {
  const meta = $("#bulkFileMeta");
  meta.classList.remove("hidden");
  meta.innerHTML = "";
  if (result.error) {
    meta.appendChild(el("span", { class: "bulk-badge error" }, "Parse error"));
    meta.appendChild(el("span", { class: "bulk-meta-text" }, `${bulk.fileName || "file"}: ${result.error}`));
    return;
  }
  meta.appendChild(el("span", { class: "bulk-badge" }, result.format));
  meta.appendChild(el("span", { class: "bulk-meta-text" }, bulk.fileName || ""));
  if (result.delimiter) {
    const d = result.delimiter === "\t" ? "Tab" : result.delimiter;
    meta.appendChild(el("span", { class: "hint" }, `delimiter: ${d}`));
  }
  meta.appendChild(el("span", { class: "hint" }, `${(result.records || []).length} record(s)`));
  if (bulk.schema) {
    if (bulk.schema.coercible) {
      meta.appendChild(el("span", { class: "bulk-badge" }, `schema: ${bulk.schema.title}`));
      const { invalid } = countInvalid(result.records || []);
      if (invalid > 0) {
        meta.appendChild(el("span", { class: "bulk-badge warn" }, `${invalid} record(s) fail schema`));
      } else {
        meta.appendChild(el("span", { class: "bulk-badge ok" }, "all match schema"));
      }
    } else {
      meta.appendChild(el("span", { class: "hint" }, `schema "${bulk.schema.title}" is structural — no column coercion applied`));
    }
  }
}

// ---- Filtering
// Normalize a filter's values into a trimmed, de-duplicated array. Supports the
// legacy single-`value` shape too.
function filterValues(f) {
  const src = Array.isArray(f.values)
    ? f.values
    : (f.value != null && f.value !== "" ? [f.value] : []);
  const out = [];
  for (const v of src) {
    const s = String(v).trim();
    if (s !== "" && !out.includes(s)) out.push(s);
  }
  return out;
}

function isFilterActive(f) {
  if (!f || !f.field) return false;
  const opDef = BULK_OPS.find((o) => o.v === f.op);
  if (opDef && !opDef.needsValue) return true;
  return filterValues(f).length > 0;
}

// Compile a filter into a fast predicate. All values are normalized once
// up-front (lower-cased / parsed to number). A record matches if it satisfies
// ANY of the provided values (OR); the negative ops (not-equals / not-contains)
// match when NONE of the values are found.
function compileFilter(f) {
  const field = f.field;
  const op = f.op;
  const readObj = (rec) => (rec && typeof rec === "object" && !Array.isArray(rec)) ? rec[field] : undefined;

  if (op === "empty") {
    return (rec) => { const v = readObj(rec); return v === undefined || v === null || String(v).trim() === ""; };
  }
  if (op === "notempty") {
    return (rec) => { const v = readObj(rec); return !(v === undefined || v === null || String(v).trim() === ""); };
  }

  const targets = filterValues(f).map((raw) => ({
    raw,
    lc: raw.toLowerCase(),
    num: Number(raw),
    numOk: Number.isFinite(Number(raw)),
  }));
  const negative = op === "ne" || op === "ncontains";
  const baseOp = op === "ne" ? "eq" : op === "ncontains" ? "contains" : op;

  const matchesOne = (v, t) => {
    switch (baseOp) {
      case "eq": {
        if (v == null) return t.raw === "";
        const a = Number(v);
        if (t.numOk && Number.isFinite(a)) return a === t.num;
        return String(v).toLowerCase() === t.lc;
      }
      case "contains": return v != null && String(v).toLowerCase().includes(t.lc);
      case "starts": return v != null && String(v).toLowerCase().startsWith(t.lc);
      case "ends": return v != null && String(v).toLowerCase().endsWith(t.lc);
      case "gt": case "gte": case "lt": case "lte": {
        if (!t.numOk) return false;
        const a = Number(v);
        if (!Number.isFinite(a)) return false;
        if (baseOp === "gt") return a > t.num;
        if (baseOp === "gte") return a >= t.num;
        if (baseOp === "lt") return a < t.num;
        return a <= t.num;
      }
      default: return true;
    }
  };

  return (rec) => {
    const v = readObj(rec);
    let any = false;
    for (let i = 0; i < targets.length; i++) {
      if (matchesOne(v, targets[i])) { any = true; break; }
    }
    return negative ? !any : any;
  };
}

// Compute the filtered records once, using compiled predicates.
function computeBulkFiltered() {
  const active = bulk.filters.filter(isFilterActive);
  if (!active.length) return bulk.records; // no copy needed; never mutated
  const preds = active.map(compileFilter);
  const any = bulk.matchMode === "any";
  return bulk.records.filter((rec) => {
    if (any) {
      for (let i = 0; i < preds.length; i++) if (preds[i](rec)) return true;
      return false;
    }
    for (let i = 0; i < preds.length; i++) if (!preds[i](rec)) return false;
    return true;
  });
}

function invalidateBulkFilter() {
  bulk._filtered = null;
}

// Cached accessor: recompute only when the cache was invalidated.
function getBulkFiltered() {
  if (bulk._filtered == null) bulk._filtered = computeBulkFiltered();
  return bulk._filtered;
}

function bulkFilteredMessages() {
  return getBulkFiltered().map((r) => JSON.stringify(r));
}

function filterLabel(f) {
  const opDef = BULK_OPS.find((o) => o.v === f.op);
  const opLabel = opDef ? opDef.label : f.op;
  if (opDef && !opDef.needsValue) return `${f.field} ${opLabel}`;
  const vals = filterValues(f);
  const shown = vals.map((v) => `"${v}"`).join(", ");
  const suffix = vals.length > 1 ? (f.op === "ne" || f.op === "ncontains" ? " (none of)" : " (any of)") : "";
  return `${f.field} ${opLabel} ${shown}${suffix}`;
}

// ---- Filter UI
function renderBulkFilters() {
  const wrap = $("#bulkFilterWrap");
  const list = $("#bulkFilterList");
  const enabled = bulk.records.length > 0 && bulk.fields.length > 0;
  wrap.classList.toggle("hidden", !enabled);
  if (!enabled) { list.innerHTML = ""; return; }
  $("#bulkMatchMode").value = bulk.matchMode;
  list.innerHTML = "";
  if (!bulk.filters.length) {
    list.appendChild(el("div", { class: "bulk-filter-empty hint" },
      "No filters — every record will be posted. Add a filter to narrow it down."));
  }
  bulk.filters.forEach((f) => list.appendChild(buildFilterRow(f)));
  updateBulkFilterCount();
}

function buildFilterRow(f) {
  // Migrate legacy single-value filters to the multi-value shape.
  if (!Array.isArray(f.values)) f.values = f.value != null && f.value !== "" ? [String(f.value)] : [];
  delete f.value;

  const fieldSel = el("select", { class: "bulk-f-field" });
  fieldSel.appendChild(el("option", { value: "" }, "Field…"));
  bulk.fields.forEach((h) => fieldSel.appendChild(el("option", { value: h }, h)));
  fieldSel.value = f.field || "";
  fieldSel.addEventListener("change", () => { f.field = fieldSel.value; refreshBulkPreview(); });

  const opSel = el("select", { class: "bulk-f-op" });
  BULK_OPS.forEach((o) => opSel.appendChild(el("option", { value: o.v }, o.label)));
  opSel.value = f.op;
  opSel.addEventListener("change", () => {
    f.op = opSel.value;
    renderBulkFilters();  // swap value editor for empty/notempty ops
    refreshBulkPreview();
  });

  const opDef = BULK_OPS.find((o) => o.v === f.op);
  const valueEditor = (opDef && !opDef.needsValue)
    ? el("div", { class: "bulk-f-noval" }, "no value needed")
    : buildChipInput(f);

  const rm = el("button", { class: "bulk-f-rm", title: "Remove filter" }, "✕");
  rm.addEventListener("click", () => {
    const i = bulk.filters.indexOf(f);
    if (i >= 0) bulk.filters.splice(i, 1);
    renderBulkFilters();
    refreshBulkPreview();
  });

  const top = el("div", { class: "bulk-filter-row-top" }, [fieldSel, opSel, rm]);
  return el("div", { class: "bulk-filter-row" }, [top, valueEditor]);
}

// A tag/chip editor so a single condition can hold several values (matched with
// OR). Type a value and press Enter or comma to add it; Backspace on an empty
// box removes the last chip. Paste of comma/newline-separated text adds many.
function buildChipInput(f) {
  const box = el("div", { class: "bulk-f-chips" });
  const input = el("input", { type: "text", class: "bulk-f-chipinput" });

  const afterChange = () => { invalidateBulkFilter(); refreshBulkPreview(); };

  const setPlaceholder = () => {
    input.placeholder = f.values.length ? "add value…" : "value(s) — Enter or , to add";
  };

  const renderChips = () => {
    box.querySelectorAll(".bulk-chip").forEach((c) => c.remove());
    f.values.forEach((val, idx) => {
      const x = el("button", { class: "bulk-chip-x", title: "Remove", tabindex: "-1" }, "✕");
      x.addEventListener("click", (e) => {
        e.stopPropagation();
        f.values.splice(idx, 1);
        renderChips();
        afterChange();
      });
      const chip = el("span", { class: "bulk-chip" }, [el("span", {}, val), x]);
      box.insertBefore(chip, input);
    });
    setPlaceholder();
  };

  const addValues = (text) => {
    const parts = String(text).split(/[,\n\t]/).map((s) => s.trim()).filter(Boolean);
    let added = false;
    parts.forEach((p) => { if (!f.values.includes(p)) { f.values.push(p); added = true; } });
    if (added) { renderChips(); afterChange(); }
    return added;
  };

  const commit = () => {
    const t = input.value.trim();
    input.value = "";
    if (t) addValues(t);
  };

  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === ",") { e.preventDefault(); commit(); }
    else if (e.key === "Backspace" && input.value === "" && f.values.length) {
      f.values.pop();
      renderChips();
      afterChange();
    }
  });
  input.addEventListener("blur", commit);
  input.addEventListener("paste", (e) => {
    const text = (e.clipboardData || window.clipboardData).getData("text");
    if (text && /[,\n\t]/.test(text)) { e.preventDefault(); addValues(text); }
  });
  box.addEventListener("mousedown", (e) => { if (e.target === box) { e.preventDefault(); input.focus(); } });

  box.appendChild(input);
  renderChips();
  return box;
}

function updateBulkFilterCount() {
  const badge = $("#bulkFilterCount");
  if (!badge) return;
  const total = bulk.records.length;
  const matched = getBulkFiltered().length;
  const anyActive = bulk.filters.some(isFilterActive);
  badge.textContent = anyActive ? `${matched} of ${total} match` : `${total} record(s)`;
  badge.classList.toggle("warn", anyActive && matched === 0);
  badge.classList.toggle("ok", anyActive && matched > 0);
}

// Recompute the filtered set once (via the cache) and refresh preview/count/buttons.
function refreshBulkPreview() {
  invalidateBulkFilter();
  const filtered = getBulkFiltered();
  renderBulkPreview(filtered, { format: bulk.format });
  updateBulkFilterCount();
  updateBulkButtons();
}

// `records` is the filtered record list; only the first few are stringified.
function renderBulkPreview(records, result) {
  const box = $("#bulkPreview");
  const count = $("#bulkPreviewCount");
  box.innerHTML = "";
  if (result && result.error) {
    box.appendChild(el("div", { class: "empty-state small" }, [el("p", {}, result.error)]));
    count.textContent = "";
    return;
  }
  if (!records.length) {
    const anyActive = bulk.filters.some(isFilterActive);
    const msg = anyActive ? "No records match the current filter." : "No records found in the file.";
    box.appendChild(el("div", { class: "empty-state small" }, [el("p", {}, msg)]));
    count.textContent = "";
    return;
  }
  const shown = Math.min(5, records.length);
  count.textContent = `${records.length} message(s) to post · showing first ${shown}`;
  for (let i = 0; i < shown; i++) {
    let pretty;
    try { pretty = JSON.stringify(records[i], null, 2); } catch { pretty = String(records[i]); }
    box.appendChild(el("div", { class: "bulk-msg" }, [
      el("div", { class: "bulk-msg-idx" }, `#${i + 1}`),
      el("pre", { class: "bulk-msg-json" }, pretty),
    ]));
  }
}

function renderBulkResults(res) {
  const box = $("#bulkResults");
  box.innerHTML = "";
  box.appendChild(el("span", { class: "bulk-badge ok" }, `${res.published} published`));
  if (res.failed > 0) box.appendChild(el("span", { class: "bulk-badge error" }, `${res.failed} failed`));
  if (res.errors && res.errors.length) {
    box.appendChild(el("details", { class: "bulk-errors" }, [
      el("summary", {}, `${res.errors.length} error(s)`),
      el("ul", {}, res.errors.map((er) => el("li", {}, er))),
    ]));
  }
}

function updateBulkButtons() {
  const has = bulk.records.length > 0 && getBulkFiltered().length > 0;
  $("#bulkPostBtn").disabled = !has;
  $("#bulkClearBtn").disabled = !(bulk.records.length > 0 || bulk.rawText != null);
}

function bulkClear() {
  bulk.records = [];
  bulk.fields = [];
  bulk.filters = [];
  bulk.matchMode = "all";
  bulk._filtered = null;
  bulk.rawText = null;
  bulk.fileName = null;
  bulk.format = null;
  $("#bulkFileInput").value = "";
  $("#bulkFileMeta").classList.add("hidden");
  $("#bulkFileMeta").innerHTML = "";
  $("#bulkMatchMode").value = "all";
  $("#bulkFilterWrap").classList.add("hidden");
  $("#bulkFilterList").innerHTML = "";
  $("#bulkFilterCount").textContent = "";
  $("#bulkPreview").innerHTML = "";
  $("#bulkPreview").appendChild(el("div", { class: "empty-state small" }, [el("p", {}, "No file loaded yet.")]));
  $("#bulkPreviewCount").textContent = "";
  $("#bulkResults").innerHTML = "";
  updateBulkButtons();
}

// Validate, then open a confirmation dialog before publishing.
function bulkPost() {
  const topic = $("#bulkTopic").value;
  if (!topic) { toast("Select a topic to publish to.", "error"); return; }
  if (!bulk.records.length) { toast("Load a file with at least one record.", "error"); return; }
  const msgs = bulkFilteredMessages();
  if (!msgs.length) { toast("No records match the current filter — nothing to post.", "error"); return; }
  confirmBulkPost(topic, msgs);
}

function confirmBulkPost(topic, msgs) {
  const total = bulk.records.length;
  const active = bulk.filters.filter(isFilterActive);
  const cancel = el("button", { class: "btn", onclick: closeModal }, "Cancel");
  const confirm = el("button", { class: "btn btn-primary" }, `Publish ${msgs.length} message(s)`);
  confirm.addEventListener("click", async () => {
    confirm.disabled = true;
    cancel.disabled = true;
    try {
      await doBulkPost(topic, msgs);
      $("#modalOverlay").classList.add("hidden");
    } catch (e) {
      toast(e.message, "error", "Bulk publish failed");
      confirm.disabled = false;
      cancel.disabled = false;
    }
  });

  const rows = [
    el("div", { class: "confirm-row" }, [
      el("span", { class: "confirm-k" }, "Topic"),
      el("span", { class: "confirm-v mono" }, topic),
    ]),
    el("div", { class: "confirm-row" }, [
      el("span", { class: "confirm-k" }, "Schema"),
      el("span", { class: "confirm-v" }, bulk.schema ? bulk.schema.title : "None (raw strings)"),
    ]),
    el("div", { class: "confirm-row" }, [
      el("span", { class: "confirm-k" }, "Messages"),
      el("span", { class: "confirm-v" }, active.length ? `${msgs.length} of ${total} (filtered)` : `${msgs.length} (all records)`),
    ]),
  ];
  if (active.length) {
    rows.push(el("div", { class: "confirm-row" }, [
      el("span", { class: "confirm-k" }, `Filter · match ${bulk.matchMode === "any" ? "ANY" : "ALL"}`),
      el("ul", { class: "confirm-filters" }, active.map((f) => el("li", { class: "mono" }, filterLabel(f)))),
    ]));
  }

  // Validate the exact set being posted so schema violations are surfaced first.
  const children = [
    el("p", { style: "margin:0 0 12px;color:var(--text-dim);line-height:1.5" },
      "Publish these messages to the selected topic? This is a real, deliberate write."),
    ...rows,
  ];
  const { invalid, samples } = countInvalid(getBulkFiltered());
  if (invalid > 0) {
    confirm.textContent = `Publish anyway (${invalid} may fail)`;
    children.push(el("div", { class: "confirm-warn" }, [
      el("strong", {}, `${invalid} of ${msgs.length} record(s) do not satisfy "${bulk.schema.title}"`),
      el("ul", { class: "confirm-filters" }, samples.map((s) => el("li", { class: "mono" }, s))),
      el("p", { class: "hint", style: "margin:6px 0 0" }, "The Dataflow consumer will likely reject these."),
    ]));
  }
  const body = el("div", { class: "confirm-body" }, children);
  openModal("Confirm bulk publish", body, [cancel, confirm]);
}

async function doBulkPost(topic, msgs) {
  await withBusy(`Publishing ${msgs.length} message(s) to ${topic}…`, async () => {
    const res = await api(`/api/topics/${encodeURIComponent(topic)}/publish-bulk`, {
      method: "POST",
      body: { messages: msgs },
    });
    renderBulkResults(res);
    if (res.failed > 0) toast(`Published ${res.published}/${res.total} · ${res.failed} failed.`, "error", "Bulk publish");
    else toast(`Published ${res.published} message(s) to ${topic}.`, "success");
  });
}

// ============================================================ Product Clean Up
const cleanup = { config: null, groups: [] };
let cleanupInited = false;

async function initCleanup() {
  if (!cleanupInited) {
    cleanupInited = true;
    $("#cleanupScanBtn").addEventListener("click", () => { if (!isBusy()) cleanupScan(); });
    $("#cleanupDeleteBtn").addEventListener("click", () => { if (!isBusy()) confirmCleanupDelete(); });
    $("#cleanupProductId").addEventListener("keydown", (e) => { if (e.key === "Enter" && !isBusy()) cleanupScan(); });
  }
  if (!cleanup.config) {
    try {
      cleanup.config = await api("/api/mongo/config");
    } catch (e) {
      cleanupInited = false;
      toast(e.message, "error", "Failed to load Mongo config");
      return;
    }
    buildCleanupEnv();
    buildCleanupPanels();
  }
}

function buildCleanupEnv() {
  const sel = $("#cleanupEnv");
  sel.innerHTML = "";
  sel.appendChild(el("option", { value: "" }, "Environment…"));
  (cleanup.config.environments || []).forEach((e) => sel.appendChild(el("option", { value: e.name }, e.name)));
}

function buildCleanupPanels() {
  const wrap = $("#cleanupPanels");
  wrap.innerHTML = "";
  cleanup.groups = [];
  (cleanup.config.productCleanup || []).forEach((g) => {
    const rows = [];
    const list = el("div", { class: "cleanup-list" });
    const selectAll = el("input", { type: "checkbox", class: "cleanup-selectall" });
    selectAll.addEventListener("change", () => {
      rows.forEach((r) => { r.checkbox.checked = selectAll.checked; });
      updateCleanupDeleteBtn();
    });
    (g.collections || []).forEach((c) => {
      const checkbox = el("input", { type: "checkbox", class: "cleanup-cb" });
      checkbox.addEventListener("change", updateCleanupDeleteBtn);
      const countEl = el("span", { class: "cleanup-count muted" }, "—");
      list.appendChild(el("label", { class: "cleanup-row" }, [
        checkbox,
        el("span", { class: "cleanup-coll" }, c),
        countEl,
      ]));
      rows.push({ collection: c, checkbox, countEl });
    });
    wrap.appendChild(el("div", { class: "cleanup-panel" }, [
      el("div", { class: "cleanup-panel-head" }, [
        el("label", { class: "cleanup-selectall-lbl" }, [selectAll, el("span", {}, g.label)]),
        el("span", { class: "cleanup-db" }, g.database),
      ]),
      list,
    ]));
    cleanup.groups.push({ label: g.label, database: g.database, selectAll, rows });
  });
}

function collectCleanupTargets() {
  const targets = [];
  cleanup.groups.forEach((grp) => {
    grp.rows.forEach((r) => {
      if (r.checkbox.checked) targets.push({ database: grp.database, collection: r.collection });
    });
  });
  return targets;
}

function updateCleanupDeleteBtn() {
  $("#cleanupDeleteBtn").disabled = collectCleanupTargets().length === 0;
}

async function cleanupScan() {
  const env = $("#cleanupEnv").value;
  const productId = $("#cleanupProductId").value.trim();
  if (!env) { toast("Select an environment.", "error"); return; }
  if (!productId) { toast("Enter a productId.", "error"); return; }
  $("#cleanupResults").innerHTML = "";
  await withBusy("Scanning collections…", async () => {
    try {
      const res = await api("/api/mongo/cleanup/scan", { params: { env, productId } });
      applyCleanupScan(res);
    } catch (e) {
      toast(e.message, "error", "Scan failed");
    }
  });
}

function applyCleanupScan(res) {
  const byKey = {};
  (res.groups || []).forEach((g) => {
    (g.collections || []).forEach((c) => { byKey[g.database + "::" + c.name] = c.count; });
  });
  cleanup.groups.forEach((grp) => {
    grp.rows.forEach((r) => {
      const count = byKey[grp.database + "::" + r.collection];
      const n = typeof count === "number" ? count : 0;
      r.countEl.textContent = String(n);
      r.countEl.className = "cleanup-count" + (n > 0 ? " has" : " muted");
      r.checkbox.checked = n > 0; // preselect collections that actually hold the product
    });
    grp.selectAll.checked = grp.rows.length > 0 && grp.rows.every((r) => r.checkbox.checked);
  });
  updateCleanupDeleteBtn();
  const results = $("#cleanupResults");
  results.innerHTML = "";
  if ((res.total || 0) === 0) {
    results.appendChild(el("span", { class: "cleanup-badge warn" }, "productId not found in any collection"));
  } else {
    results.appendChild(el("span", { class: "cleanup-badge" }, `${res.total} document(s) found`));
  }
}

function renderCleanupResults(res) {
  const box = $("#cleanupResults");
  box.innerHTML = "";
  box.appendChild(el("span", { class: "cleanup-badge ok" }, `${res.totalDeleted || 0} deleted`));
  (res.results || []).filter((r) => r.deleted > 0).forEach((r) => {
    box.appendChild(el("span", { class: "cleanup-badge" }, `${r.collection}: ${r.deleted}`));
  });
}

function confirmCleanupDelete() {
  const env = $("#cleanupEnv").value;
  const productId = $("#cleanupProductId").value.trim();
  const targets = collectCleanupTargets();
  if (!env || !productId) { toast("Select an environment and productId.", "error"); return; }
  if (!targets.length) { toast("Select at least one collection.", "error"); return; }

  const cancel = el("button", { class: "btn", onclick: closeModal }, "Cancel");
  const confirm = el("button", { class: "btn btn-danger" }, `Delete from ${targets.length} collection(s)`);
  confirm.addEventListener("click", async () => {
    confirm.disabled = true;
    cancel.disabled = true;
    try {
      await withBusy("Deleting product from collections…", async () => {
        const res = await api("/api/mongo/cleanup/delete", { method: "POST", body: { env, productId, targets } });
        renderCleanupResults(res);
        // Reflect the deletions in the badges without wiping the results panel.
        (res.results || []).forEach((r) => {
          const grp = cleanup.groups.find((g) => g.database === r.database);
          const row = grp && grp.rows.find((x) => x.collection === r.collection);
          if (row) { row.countEl.textContent = "0"; row.countEl.className = "cleanup-count muted"; row.checkbox.checked = false; }
          if (grp) grp.selectAll.checked = false;
        });
        updateCleanupDeleteBtn();
        const t = res.totalDeleted || 0;
        toast(`Deleted ${t} document(s) for productId "${productId}".`, t > 0 ? "success" : "info");
      });
      $("#modalOverlay").classList.add("hidden");
    } catch (e) {
      toast(e.message, "error", "Delete failed");
      confirm.disabled = false;
      cancel.disabled = false;
    }
  });

  const list = el("ul", { style: "margin:8px 0 0;padding-left:18px" },
    targets.map((t) => el("li", {}, `${t.database} / ${t.collection}`)));
  const body = el("div", {}, [
    el("p", { style: "margin:0;color:var(--text-dim);line-height:1.5" },
      `Permanently delete ALL documents with productId "${productId}" in ${env} from these collections? This cannot be undone.`),
    list,
  ]);
  openModal("Delete product data", body, [cancel, confirm]);
}

// --------------------------------------------------------------- Wire up
function init() {
  $("#reloadBtn").addEventListener("click", () => { if (!isBusy()) loadAll(); });
  $("#modalClose").addEventListener("click", closeModal);
  $("#modalOverlay").addEventListener("click", (e) => { if (e.target.id === "modalOverlay") closeModal(); });
  $("#projectInput").addEventListener("change", () => { if (!isBusy()) loadAll(); });
  $("#topicSelect").addEventListener("change", (e) => { if (e.target.value) selectItem("topic", e.target.value); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });

  // Mongo Compare view
  $("#navPubsub").addEventListener("click", () => setView("pubsub"));
  $("#navMongo").addEventListener("click", () => setView("mongo"));
  $("#navBulk").addEventListener("click", () => setView("bulk"));
  $("#navCleanup").addEventListener("click", () => setView("cleanup"));
  $("#mongoCompareBtn").addEventListener("click", () => { if (!isBusy()) mongoCompare(); });
  $("#mongoSwapBtn").addEventListener("click", () => { if (!isBusy()) mongoSwap(); });
  $("#mongoDiffOnly").addEventListener("change", renderMongoDocs);

  checkAuthAndStart(false);
}

document.addEventListener("DOMContentLoaded", init);

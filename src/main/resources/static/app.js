"use strict";

const state = {
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

// --------------------------------------------------------------- Wire up
function init() {
  $("#reloadBtn").addEventListener("click", () => { if (!isBusy()) loadAll(); });
  $("#modalClose").addEventListener("click", closeModal);
  $("#modalOverlay").addEventListener("click", (e) => { if (e.target.id === "modalOverlay") closeModal(); });
  $("#projectInput").addEventListener("change", () => { if (!isBusy()) loadAll(); });
  $("#topicSelect").addEventListener("change", (e) => { if (e.target.value) selectItem("topic", e.target.value); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });

  checkAuthAndStart(false);
}

document.addEventListener("DOMContentLoaded", init);

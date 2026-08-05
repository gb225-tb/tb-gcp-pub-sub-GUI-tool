# Catalog Pub/Sub Monitoring Tool

An internal, **read-only monitoring** web console for **Google Cloud Pub/Sub**.
It is organised around message-flow groups and is designed to observe traffic
**without disturbing real consumers**.

The app is served under the context path **`/catalog-pubsub-gui`**. The main UI
uses a light theme; the **live tail** panel stays dark for an at-a-glance console
feel.

- **Flow tabs + topic dropdown** — topics are grouped (e.g. *Inbound*,
  *Config to Runtime*, *Runtime to CT*); pick a group tab, then a topic from its
  dropdown.
- **Message counts** per topic/subscription from **Cloud Monitoring**:
  - **Non-ACK** = current backlog (`num_undelivered_messages`)
  - **ACK (24h)** = consumed (`ack_message_count`, summed over 24h)
  - **Total** = ACK + Non-ACK
- **View / peek** the latest message(s) — strictly **non-destructive** (pulled
  then immediately released, never acknowledged).
- **Publish** — send a test message (data + attributes + optional ordering key,
  with an optional burst count) to a topic, then watch it arrive in the live tail.
- **Live tail** (Spring WebFlux + SSE), in two modes:
  - **Whole topic (new subscription)** — creates a dedicated **temporary
    subscription** (auto-deleted on stop) that receives its own copy of every
    published message. This is the reliable way to see traffic even when other
    subscriptions are actively drained by a consumer (e.g. **Dataflow**), with
    no impact on them.
  - **Per existing subscription** — observes a real subscription **without
    acking** (messages released/nacked, de-duplicated by id). Useful for idle
    subscriptions or backlog, but a subscription that is actively consumed will
    show little or nothing here (its consumer wins the messages) — use the
    whole-topic tail instead.
- **Purge** (destructive, explicit) — drains a subscription (or every
  subscription on a topic) by acking until the backlog is empty, coordinated
  with a `CountDownLatch`.
- **Topic allow-list** — the tool only ever sees the configured/grouped topics.
- **Status bar + blocking overlay** — while connecting/fetching/purging the UI
  shows progress and prevents premature clicks or dialog dismissal.

> **Note on safety:** **viewing is peek-only** (pull-then-release) so it never
> steals messages from your real consumers. **Publish** adds a real message to a
> topic (useful for testing flows), and **Purge** is destructive — both are
> explicit, deliberate actions. Everything else is read-only monitoring.

## Mongo Compare

A second view (top nav: **Pub/Sub** | **Mongo Compare**) validates the data that
lands in MongoDB (Firestore in MongoDB-compatibility mode) after messages are
processed. It lets you fetch the *same* document across environments and see
exactly how they differ.

- **Two side-by-side panels (A / B)** — each independently picks an
  **Environment** (Dev / QA / Perf), a **Database** (item-config, item-runtime,
  inventory-config, inventory-runtime), a **Collection** (loaded live from the
  DB), and a **`productId`** to fetch. So you can compare, e.g., Dev *item-config*
  vs QA *item-config* for the same `productId`.
- **`productId` lookup** — searches the `productId` field (seeded across the
  related collections), matching the value as a string and, when it parses as a
  number, as a numeric value too. **All** matching documents are fetched (a
  single productId can map to several, e.g. multiple SKUs); when there is more
  than one, a per-panel **Document** dropdown (labelled by `_id`) lets you choose
  which one to view and compare.
- **Attribute-wise diff** — both documents are rendered key-aligned with keys
  **sorted and matched**, and each attribute is colour-coded:
  - **Green** = present on both sides and **equal**
  - **Red** = present on both sides but **different**
  - **Gray** (hatched) = **only on one side** (missing on the other)
  - A summary bar shows counts of matching / differing / one-sided attributes.
  - **Differences only** toggle collapses the identical attributes.
- **Swap** flips panel A and B (selections and loaded documents).
- **Delete (destructive)** — removes the currently selected document by its
  `_id` from one side after an explicit confirmation, so the source pipeline
  re-seeds and **reprocesses** it. This is the only write the view performs.

> **Safety:** only environment / database / collection names and document bodies
> ever reach the browser — connection URIs and credentials stay on the server.
> **Delete** is destructive and gated behind a confirmation dialog.

## Bulk Post

A third view (left rail: **Bulk-Posting / Pub/Sub**) publishes many messages to a
topic from an uploaded file. The file is parsed in the browser, previewed, then
sent to the selected topic in a single batch request.

- **Supported files** — `.csv`, `.txt`, or `.json`.
  - **JSON**: a single object (one message) or an **array of objects** (one
    message per element). Each element is published as-is.
  - **CSV / TXT**: the first line is the **header** (keys); every subsequent row
    becomes an object keyed by those headers. The **delimiter** can be chosen
    (comma / tab / pipe / semicolon) or **auto-detected** from the header line.
    Quoted fields (with embedded delimiters/newlines and `""` escapes) are
    handled.
- **Schema (recommended)** — pick a message schema so each column is coerced to
  the **type the Dataflow consumer expects** instead of being guessed. This is
  the important bit: schemas such as `product_message` carry every column as a
  raw **string** (e.g. `Division`, `ProductColorCode`, and the `*Flag` / cost
  fields are `string` / `[string, null]`), so guessing types turns them into
  integers and the consumer rejects the message
  (`integer found, [string, null] expected`). With a schema selected:
  - `string` fields stay strings (leading zeros and numeric-looking codes are
    preserved); nullable fields with empty cells become `null`.
  - only `number` / `integer` / `boolean` / `array` / `object` fields are
    converted.
  - the UI validates each record against the schema (required, nullability,
    enum, un-coercible numbers) and shows how many **fail schema** before you
    post; the confirm dialog lists sample failures.
  - Schemas are loaded from `GET /api/schemas` (bundled under
    `src/main/resources/schemas/`). Choosing **None (raw strings)** falls back to
    the heuristic coercion below. Structural schemas (`oneOf`/nested, no flat
    `properties`) are listed but do not drive column coercion.
- **Value coercion** (CSV/TXT, no schema) — numbers become numbers, `true`/`false`
  become booleans, empty cells become `null`, everything else stays a string.
- **Filter** — build one or more conditions on the file's headers (equals,
  contains, ranges, is empty, …) combined with match **ALL** / **ANY** to narrow
  down which records get posted; a live count shows how many match. Each
  condition accepts **multiple values** entered as chips (type + Enter/comma, or
  paste a comma/newline-separated list): a record matches if the field satisfies
  **any** of the chips (and the "not equals" / "does not contain" operators match
  when **none** of them are found).
- **Preview** — shows the detected format, delimiter, record count, selected
  schema, validation status, and the first few generated JSON messages.
- **Post** — a **confirmation dialog** summarizes the topic, schema, message
  count (and any active filter / schema failures) before publishing every
  generated message via `POST /api/topics/{id}/publish-bulk`; it then reports how
  many succeeded / failed (with the first errors listed).

> **Safety:** publishing is a real, deliberate write (like the single **Publish**
> action) and only targets configured/allowed topics.

## Product Clean Up

A fourth view (left rail: **Cleanup**) deletes a product's documents from the
config and runtime MongoDB collections in one environment, so it can be
re-seeded / reprocessed.

- Pick an **Environment**, enter a **productId**, and click **Scan**.
- Two panels — **Config** (`item-config` DB) and **Runtime** (`item-runtime` DB)
  — list their collections with a checkbox and a **match-count** badge showing
  how many documents hold that productId. Collections with matches are
  pre-selected; if the productId is absent everywhere, all counts are `0` and a
  "not found in any collection" note is shown.
- **Delete selected** (destructive, confirmed) removes every document matching
  the productId (`deleteMany`) from each checked collection and reports the
  **deleted count per collection**.

The collection groups are defined once (they are the same across environments):

```yaml
mongo:
  product-cleanup:
    - label: Config
      database: item-config
      collections: [Product, Variant, SKU, Price, EnrichedProduct, Rating, ProductCategoryAssociation]
    - label: Runtime
      database: item-runtime
      collections: [Product, Variant, SKU, Price, ProductCategoryAssociation]
```

> **Safety:** delete uses `deleteMany` by productId and is gated behind a
> confirmation that lists exactly which environment/collections will be purged.

Built with **Spring Boot + Spring WebFlux (Java 17)**, the official
`google-cloud-pubsub` and `google-cloud-monitoring` clients. The UI is a
dependency-free single-page app (no Node/npm build step) — the whole thing runs
from one jar.

---

## Prerequisites

- Java 17+, Maven 3.9+
- Authenticated [gcloud CLI](https://cloud.google.com/sdk) (Application Default Credentials)
- IAM on the running identity:
  - `roles/pubsub.viewer` (list), `roles/pubsub.subscriber` (peek / per-sub tail / purge)
  - `roles/pubsub.publisher` if you use the **Publish** action
  - `roles/pubsub.editor` for the **whole-topic tail** (creates + deletes the temporary subscription)
  - `roles/monitoring.viewer` for the counts

## Build & run

```bash
mvn clean package
java -jar target/catalog-pubsub-gui-1.0.0.jar
```

Open <http://localhost:8080/catalog-pubsub-gui/>. The project defaults to
`np-ecom-1-08ba` (override with the field in the UI or `PUBSUB_PROJECT_ID`).

### `./run.sh` (auto-frees the port)

To avoid the "Port 8080 was already in use" error, use the helper script — it
kills whatever is listening on the port, builds the jar if missing, then starts:

```bash
./run.sh               # port 8080
PORT=8099 ./run.sh     # custom port
```

One-liner equivalent if you prefer not to use the script:

```bash
lsof -ti tcp:8080 | xargs kill -9 2>/dev/null; java -jar target/catalog-pubsub-gui-1.0.0.jar
```

### Signing in (Application Default Credentials)

You no longer need to run `gcloud` before launching. When the tool starts without
ADC, it shows a **sign-in gate** with a **“Sign in with Google (ADC)”** button.
Clicking it runs `gcloud auth application-default login` on the host, which opens
your browser for Google sign-in. The gate polls in the background and, once the
credentials land on disk, **redirects you straight into the tool**.

> This in-app sign-in only works when the tool runs **locally** on the same
> machine as your browser and the gcloud SDK. For shared/remote deployments,
> disable it (`PUBSUB_ALLOW_GCLOUD_LOGIN=false`) and sign in manually with
> `gcloud auth application-default login`.

## Configuration (`application.yml` / env)

| Setting                | Env var                | Default          | Description                                  |
|------------------------|------------------------|------------------|----------------------------------------------|
| `pubsub.project-id`    | `PUBSUB_PROJECT_ID`    | `np-ecom-1-08ba` | Default project (overridable in the UI)      |
| `pubsub.emulator-host` | `PUBSUB_EMULATOR_HOST` | _(empty)_        | Emulator `host:port` (counts unavailable)    |
| `pubsub.allowed-topics`| `PUBSUB_ALLOWED_TOPICS`| _(empty)_        | Extra allowed topics (comma-separated)       |
| `pubsub.topic-groups`  | —                      | 3 groups, 25 topics | Flow groups shown as tabs (see below)     |
| `pubsub.allow-gcloud-login` | `PUBSUB_ALLOW_GCLOUD_LOGIN` | `true`   | Allow the in-app "Sign in with Google" button to run gcloud |
| `spring.webflux.base-path` | `APP_CONTEXT_PATH` | `/catalog-pubsub-gui` | Context path the whole tool is served under |
| `server.port`          | `PORT`                 | `8080`           | HTTP port                                    |

Topics in any group are implicitly allowed; anything outside the configured set
is rejected with `403`.

### Mongo Compare connections

The Mongo Compare view is driven entirely by the `mongo.environments` block in
`application.yml`. Each environment lists its logical databases and the MongoDB
connection URI for each (the physical database name is embedded in the URI):

```yaml
mongo:
  environments:
    - name: Dev
      databases:
        - name: item-config
          uri: ${MONGO_DEV_ITEM_CONFIG:mongodb://…/fs-ctlg-item-config-dvlp?…}
        - name: item-runtime
          uri: ${MONGO_DEV_ITEM_RUNTIME:mongodb://…}
        # inventory-config, inventory-runtime …
    - name: QA   # 4 databases (…-test)
    - name: Perf # 4 databases (…-perf)
```

Each URI is an env-var placeholder (e.g. `MONGO_DEV_ITEM_CONFIG`) that defaults
to the checked-in value, so you can override any single connection without
editing the file. Add/remove environments or databases freely — the UI builds
its dropdowns from this config.

### Flow groups

Groups are defined in `application.yml` and pre-loaded from the team's Confluence
page (project `np-ecom-1-08ba`):

```yaml
pubsub:
  topic-groups:
    - name: Inbound
      topics: [ np-ecom-1-catalog_inbound_bazaarvoice-topic, … ]
    - name: Config to Runtime
      topics: [ np-ecom-1-catalog_inventory_change-topic, … ]
    - name: Runtime to CT
      topics: [ np-ecom-1-catalog_price_ct_ingest-topic, … ]
```

Edit/add groups freely — the UI builds one tab per group, in order, with a topic
dropdown.

## REST API

All endpoints are served under the context path (`/catalog-pubsub-gui`) and
accept an optional `?project=` query parameter.

| Method & path                                   | Description                              |
|-------------------------------------------------|------------------------------------------|
| `GET    /api/config`                            | Mode, default project, flow groups       |
| `GET    /api/auth/status`                       | Whether ADC is present + sign-in availability |
| `POST   /api/auth/login`                        | Launch `gcloud auth application-default login` |
| `GET    /api/topics`                            | All allowed topics (union of groups)     |
| `GET    /api/topics/{id}/subscriptions`         | Subscriptions on a topic                 |
| `GET    /api/topics/{id}/counts`                | Aggregated Total/ACK/Non-ACK for a topic |
| `POST   /api/topics/{id}/publish`               | Publish a message (data/attributes/key)  |
| `POST   /api/topics/{id}/publish-bulk`          | Publish many messages at once (`{ messages:[...] }`) |
| `GET    /api/schemas`                           | Bundled message schemas flattened for the Bulk-Posting type coercion (`[{id,title,coercible,fields[...]}]`) |
| `GET    /api/topics/{id}/tail`                  | **Whole-topic live tail** via a temp subscription (SSE) |
| `POST   /api/topics/{id}/purge`                 | Purge every subscription on the topic    |
| `GET    /api/subscriptions`                     | All allowed subscriptions                |
| `GET    /api/subscriptions/{id}/counts`         | Counts for one subscription              |
| `POST   /api/subscriptions/{id}/peek?max=`      | Peek messages (non-destructive)          |
| `POST   /api/subscriptions/{id}/latest`         | Peek the single latest message           |
| `GET    /api/subscriptions/{id}/tail`           | **Live tail** for one subscription (SSE) |
| `POST   /api/subscriptions/{id}/purge`          | Drain/purge the subscription             |
| `GET    /api/mongo/config`                      | Environments (+ database names, no URIs) and `productCleanup` groups |
| `GET    /api/mongo/collections?env=&db=`        | Collection names in an env/database      |
| `GET    /api/mongo/document?env=&db=&collection=&productId=` | Fetch all documents for a `productId` (`{ found, count, documents:[{id,json}] }`) |
| `DELETE /api/mongo/document?env=&db=&collection=&id=` | Delete a document by `_id` (`{ deleted }`) |
| `GET    /api/mongo/cleanup/scan?env=&productId=` | Per-collection match counts for a `productId` (`{ groups:[{label,database,collections:[{name,count}]}], total }`) |
| `POST   /api/mongo/cleanup/delete`              | Delete a `productId` from selected targets (body `{ env, productId, targets:[{database,collection}] }`) → `{ results:[{database,collection,deleted}], totalDeleted }` |

## Notes

- **Counts** come from Cloud Monitoring and are unavailable against the emulator
  (the UI shows `—` with an explanatory note).
- **Purge** drains via a streaming subscriber and a `CountDownLatch`: it stops
  once no message arrives for ~3s, or after a 120s hard cap, and reports how many
  it acknowledged.
- This tool performs **no auth of its own** — put it behind your existing
  internal access controls (VPN, IAP, etc.).

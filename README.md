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
| `GET    /api/topics/{id}/tail`                  | **Whole-topic live tail** via a temp subscription (SSE) |
| `POST   /api/topics/{id}/purge`                 | Purge every subscription on the topic    |
| `GET    /api/subscriptions`                     | All allowed subscriptions                |
| `GET    /api/subscriptions/{id}/counts`         | Counts for one subscription              |
| `POST   /api/subscriptions/{id}/peek?max=`      | Peek messages (non-destructive)          |
| `POST   /api/subscriptions/{id}/latest`         | Peek the single latest message           |
| `GET    /api/subscriptions/{id}/tail`           | **Live tail** for one subscription (SSE) |
| `POST   /api/subscriptions/{id}/purge`          | Drain/purge the subscription             |

## Notes

- **Counts** come from Cloud Monitoring and are unavailable against the emulator
  (the UI shows `—` with an explanatory note).
- **Purge** drains via a streaming subscriber and a `CountDownLatch`: it stops
  once no message arrives for ~3s, or after a 120s hard cap, and reports how many
  it acknowledged.
- This tool performs **no auth of its own** — put it behind your existing
  internal access controls (VPN, IAP, etc.).

// Base path the whole tool is served under (Vite injects "/catalog-pubsub-gui/").
const BASE = import.meta.env.BASE_URL;

// The active project is shared across all calls (mirrors the ?project override
// in the top bar). Set from the app state whenever the project field changes.
let currentProject = "";
export function setApiProject(project: string) {
  currentProject = project || "";
}

export function apiUrl(path: string, params?: Record<string, string | undefined>): URL {
  const clean = String(path).replace(/^\//, "");
  const url = new URL(BASE + clean, window.location.origin);
  if (currentProject) url.searchParams.set("project", currentProject);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null) url.searchParams.set(k, v);
    }
  }
  return url;
}

export interface ApiOptions {
  method?: string;
  body?: unknown;
  params?: Record<string, string | undefined>;
}

export async function api<T = unknown>(path: string, options: ApiOptions = {}): Promise<T> {
  const url = apiUrl(path, options.params);
  const headers: Record<string, string> = {};
  const init: RequestInit = { method: options.method || "GET", headers };
  if (options.body !== undefined) {
    if (typeof options.body === "string") {
      init.body = options.body;
    } else {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(options.body);
    }
  }
  const res = await fetch(url, init);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const message = (data && (data.error || data.message)) || `HTTP ${res.status}`;
    throw new Error(message);
  }
  return data as T;
}

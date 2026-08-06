import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, setApiProject } from "../api/client";
import type { AppConfig, PubSubEnvironment, TopicGroup, TopicInfo } from "../api/types";
import { useUi } from "./UiProvider";

interface AppStateValue {
  config: AppConfig | null;
  project: string;
  groups: TopicGroup[];
  /** Configured environments (Dev/QA/Perf). Empty when running unrestricted. */
  environments: PubSubEnvironment[];
  /** Name of the currently selected environment (drives project + groups). */
  activeEnv: string;
  emulator: boolean;
  emulatorHost: string;
  restricted: boolean;
  setProject: (project: string) => void;
  /** Switch the active environment: repoints the project and topic groups. */
  setActiveEnv: (name: string) => void;
  /** Load config + topic groups (called once authenticated). */
  load: () => Promise<void>;
  /** Reload topic groups for the current project / environment. */
  reload: () => Promise<void>;
}

const AppStateContext = createContext<AppStateValue | null>(null);

export function useAppState(): AppStateValue {
  const ctx = useContext(AppStateContext);
  if (!ctx) throw new Error("useAppState must be used within AppStateProvider");
  return ctx;
}

export function AppStateProvider({ children }: { children: ReactNode }) {
  const { toast, withBusy } = useUi();
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [project, setProjectState] = useState("");
  const [groups, setGroups] = useState<TopicGroup[]>([]);
  const [environments, setEnvironments] = useState<PubSubEnvironment[]>([]);
  const [activeEnv, setActiveEnvState] = useState("");

  // Fallback for unrestricted deploys with no configured environments: fetch the
  // raw topic list from GCP and show it as a single group.
  const loadGroups = useCallback(
    async (baseGroups: TopicGroup[]) => {
      if (baseGroups.length > 0) {
        setGroups(baseGroups);
        return;
      }
      await withBusy("Loading topics…", async () => {
        try {
          const topics = await api<TopicInfo[]>("/api/topics");
          setGroups([{ name: "Topics", topics: topics.map((t) => t.id) }]);
        } catch (e) {
          setGroups([{ name: "Topics", topics: [] }]);
          toast((e as Error).message, "error", "Failed to load topics");
        }
      });
    },
    [toast, withBusy]
  );

  const applyEnv = useCallback((envs: PubSubEnvironment[], name: string) => {
    const env = envs.find((e) => e.name === name) || envs[0];
    if (!env) return;
    setActiveEnvState(env.name);
    setProjectState(env.projectId);
    setApiProject(env.projectId);
    setGroups(env.topicGroups || []);
  }, []);

  const load = useCallback(async () => {
    try {
      const cfg = await api<AppConfig>("/api/config");
      setConfig(cfg);
      const envs = cfg.environments || [];
      setEnvironments(envs);
      if (envs.length > 0) {
        applyEnv(envs, envs[0].name);
      } else {
        if (!project && cfg.defaultProjectId) {
          setProjectState(cfg.defaultProjectId);
          setApiProject(cfg.defaultProjectId);
        }
        await loadGroups(cfg.topicGroups || []);
      }
    } catch (e) {
      toast((e as Error).message, "error");
    }
  }, [project, applyEnv, loadGroups, toast]);

  const reload = useCallback(async () => {
    if (environments.length > 0) {
      applyEnv(environments, activeEnv);
      return;
    }
    await loadGroups(config?.topicGroups || []);
  }, [environments, activeEnv, applyEnv, config, loadGroups]);

  const setActiveEnv = useCallback(
    (name: string) => {
      applyEnv(environments, name);
    },
    [environments, applyEnv]
  );

  const setProject = useCallback(
    (p: string) => {
      const trimmed = p.trim();
      setProjectState(trimmed);
      setApiProject(trimmed);
      // Manual project override only re-derives groups in the unrestricted path;
      // when environments are configured the groups follow the selected env.
      if (environments.length === 0) void loadGroups(config?.topicGroups || []);
    },
    [environments, config, loadGroups]
  );

  const value = useMemo<AppStateValue>(
    () => ({
      config,
      project,
      groups,
      environments,
      activeEnv,
      emulator: config?.emulator ?? false,
      emulatorHost: config?.emulatorHost ?? "",
      restricted: config?.restricted ?? false,
      setProject,
      setActiveEnv,
      load,
      reload,
    }),
    [config, project, groups, environments, activeEnv, setProject, setActiveEnv, load, reload]
  );

  return <AppStateContext.Provider value={value}>{children}</AppStateContext.Provider>;
}

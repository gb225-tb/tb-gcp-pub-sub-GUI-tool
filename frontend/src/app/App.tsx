import { useCallback, useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";
import { api } from "../api/client";
import type { AuthStatus } from "../api/types";
import { useAppState } from "./AppState";
import { AuthGate } from "./AuthGate";
import { AppShell } from "./AppShell";

type Phase = "checking" | "gate" | "in";

export function App() {
  const { load } = useAppState();
  const [phase, setPhase] = useState<Phase>("checking");
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const [retried, setRetried] = useState(false);
  const enteredRef = useRef(false);

  const enter = useCallback(() => {
    setPhase("in");
    if (!enteredRef.current) {
      enteredRef.current = true;
      void load();
    }
  }, [load]);

  const check = useCallback(
    async (fromRetry: boolean) => {
      setRetried(fromRetry);
      let s: AuthStatus;
      try {
        s = await api<AuthStatus>("/api/auth/status");
      } catch {
        // Backend reachable problem — let the user in; errors surface later.
        enter();
        return;
      }
      setStatus(s);
      if (s.authenticated) enter();
      else setPhase("gate");
    },
    [enter]
  );

  useEffect(() => {
    void check(false);
  }, [check]);

  if (phase === "checking") {
    return (
      <Box sx={{ height: "100%", display: "grid", placeItems: "center" }}>
        <CircularProgress />
      </Box>
    );
  }

  if (phase === "gate") {
    return <AuthGate status={status} retried={retried} onAuthenticated={enter} onRetry={() => check(true)} />;
  }

  return <AppShell />;
}

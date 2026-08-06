import { useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import GoogleIcon from "@mui/icons-material/Google";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import { api } from "../api/client";
import type { AuthStatus } from "../api/types";

interface Props {
  status: AuthStatus | null;
  retried: boolean;
  onAuthenticated: () => void;
  onRetry: () => void;
}

export function AuthGate({ status, retried, onAuthenticated, onRetry }: Props) {
  const [msg, setMsg] = useState<{ text: string; error?: boolean }>(() => ({
    text: retried
      ? "Still no credentials found. Finish the Google sign-in, then retry."
      : "This tool needs Google Cloud credentials (ADC) to read your Pub/Sub topics.",
    error: retried,
  }));
  const [waiting, setWaiting] = useState(false);
  const [signingIn, setSigningIn] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

  const startLogin = async () => {
    setSigningIn(true);
    setMsg({ text: "Opening a browser window for Google sign-in…" });
    try {
      const res = await api<{ message?: string }>("/api/auth/login", { method: "POST" });
      setMsg({ text: res.message || "Complete sign-in in the browser window, then return here." });
    } catch (e) {
      setMsg({ text: (e as Error).message, error: true });
      setSigningIn(false);
      return;
    }
    setWaiting(true);
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      let s: AuthStatus;
      try {
        s = await api<AuthStatus>("/api/auth/status");
      } catch {
        return;
      }
      if (s.authenticated) {
        if (pollRef.current) clearInterval(pollRef.current);
        onAuthenticated();
      } else if (!s.loginInProgress && s.lastError) {
        if (pollRef.current) clearInterval(pollRef.current);
        setWaiting(false);
        setSigningIn(false);
        setMsg({ text: s.lastError, error: true });
      }
    }, 2500);
  };

  return (
    <Box sx={{ height: "100%", display: "grid", placeItems: "center", p: 2, bgcolor: "background.default" }}>
      <Card sx={{ maxWidth: 460, width: "100%" }}>
        <CardContent sx={{ p: 4 }}>
          <Stack spacing={2} alignItems="center" textAlign="center">
            <Box
              sx={{
                width: 56,
                height: 56,
                borderRadius: "50%",
                bgcolor: "primary.main",
                color: "#fff",
                display: "grid",
                placeItems: "center",
              }}
            >
              <LockOutlinedIcon />
            </Box>
            <Typography variant="h6">Sign in required</Typography>
            <Typography variant="body2" color={msg.error ? "error" : "text.secondary"}>
              {msg.text}
            </Typography>

            {waiting ? (
              <Stack direction="row" spacing={1.5} alignItems="center" sx={{ pt: 1 }}>
                <CircularProgress size={20} />
                <Typography variant="body2" color="text.secondary">
                  Waiting for sign-in to complete…
                </Typography>
              </Stack>
            ) : (
              <Stack direction="row" spacing={1.5} sx={{ pt: 1 }}>
                {status?.loginAvailable && (
                  <Button
                    variant="contained"
                    startIcon={<GoogleIcon />}
                    onClick={startLogin}
                    disabled={signingIn}
                  >
                    Sign in with Google (ADC)
                  </Button>
                )}
                <Button variant="outlined" color="inherit" onClick={onRetry}>
                  I’ve signed in — retry
                </Button>
              </Stack>
            )}
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}

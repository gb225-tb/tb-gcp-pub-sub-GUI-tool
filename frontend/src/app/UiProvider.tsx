import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Backdrop from "@mui/material/Backdrop";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Snackbar from "@mui/material/Snackbar";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

type Severity = "success" | "error" | "info" | "warning";

interface ToastItem {
  id: number;
  message: string;
  severity: Severity;
  title?: string;
}

export interface ConfirmOptions {
  title: string;
  body: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  busyMessage?: string;
  /** Runs while the dialog shows a spinner; dialog closes on success. */
  onConfirm: () => Promise<void> | void;
}

interface UiContextValue {
  toast: (message: string, severity?: Severity, title?: string) => void;
  confirm: (options: ConfirmOptions) => void;
  withBusy: <T>(message: string, fn: () => Promise<T>) => Promise<T>;
  busy: boolean;
}

const UiContext = createContext<UiContextValue | null>(null);

export function useUi(): UiContextValue {
  const ctx = useContext(UiContext);
  if (!ctx) throw new Error("useUi must be used within UiProvider");
  return ctx;
}

const DEFAULT_TITLES: Record<Severity, string> = {
  success: "Success",
  error: "Error",
  info: "Info",
  warning: "Warning",
};

export function UiProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [busyCount, setBusyCount] = useState(0);
  const [busyMessage, setBusyMessage] = useState("Working…");
  const nextId = useRef(1);

  const [confirmState, setConfirmState] = useState<ConfirmOptions | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);

  const toast = useCallback((message: string, severity: Severity = "info", title?: string) => {
    const id = nextId.current++;
    setToasts((prev) => [...prev, { id, message, severity, title }]);
  }, []);

  const removeToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const withBusy = useCallback(async <T,>(message: string, fn: () => Promise<T>): Promise<T> => {
    setBusyMessage(message);
    setBusyCount((c) => c + 1);
    try {
      return await fn();
    } finally {
      setBusyCount((c) => Math.max(0, c - 1));
    }
  }, []);

  const confirm = useCallback((options: ConfirmOptions) => {
    setConfirmState(options);
    setConfirmBusy(false);
  }, []);

  const closeConfirm = useCallback(() => {
    if (confirmBusy) return;
    setConfirmState(null);
  }, [confirmBusy]);

  const runConfirm = useCallback(async () => {
    if (!confirmState) return;
    setConfirmBusy(true);
    try {
      await withBusy(confirmState.busyMessage || "Working…", async () => {
        await confirmState.onConfirm();
      });
      setConfirmState(null);
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setConfirmBusy(false);
    }
  }, [confirmState, withBusy, toast]);

  const value = useMemo<UiContextValue>(
    () => ({ toast, confirm, withBusy, busy: busyCount > 0 }),
    [toast, confirm, withBusy, busyCount]
  );

  return (
    <UiContext.Provider value={value}>
      {children}

      <Backdrop open={busyCount > 0} sx={{ zIndex: (t) => t.zIndex.modal + 2, color: "#fff" }}>
        <Stack alignItems="center" spacing={2}>
          <CircularProgress color="inherit" />
          <Typography variant="body2">{busyMessage}</Typography>
        </Stack>
      </Backdrop>

      <Box
        sx={{
          position: "fixed",
          top: 16,
          right: 16,
          zIndex: (t) => t.zIndex.modal + 3,
          display: "flex",
          flexDirection: "column",
          gap: 1,
          maxWidth: 380,
        }}
      >
        {toasts.map((t) => (
          <Snackbar
            key={t.id}
            open
            sx={{ position: "static", transform: "none" }}
            autoHideDuration={t.severity === "error" ? 7000 : 3500}
            onClose={(_, reason) => {
              if (reason === "clickaway") return;
              removeToast(t.id);
            }}
          >
            <Alert
              severity={t.severity}
              variant="filled"
              onClose={() => removeToast(t.id)}
              sx={{ width: "100%", boxShadow: 3 }}
            >
              <AlertTitle sx={{ mb: 0.25 }}>{t.title || DEFAULT_TITLES[t.severity]}</AlertTitle>
              {t.message}
            </Alert>
          </Snackbar>
        ))}
      </Box>

      <Dialog open={!!confirmState} onClose={closeConfirm} maxWidth="sm" fullWidth>
        {confirmState && (
          <>
            <DialogTitle sx={{ fontWeight: 700 }}>{confirmState.title}</DialogTitle>
            <DialogContent dividers>{confirmState.body}</DialogContent>
            <DialogActions>
              <Button onClick={closeConfirm} disabled={confirmBusy} color="inherit">
                {confirmState.cancelLabel || "Cancel"}
              </Button>
              <Button
                onClick={runConfirm}
                disabled={confirmBusy}
                variant="contained"
                color={confirmState.danger ? "error" : "primary"}
              >
                {confirmState.confirmLabel || "Confirm"}
              </Button>
            </DialogActions>
          </>
        )}
      </Dialog>
    </UiContext.Provider>
  );
}

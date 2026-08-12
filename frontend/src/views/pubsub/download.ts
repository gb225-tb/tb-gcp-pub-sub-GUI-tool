import type { MessageView } from "../../api/types";

/** Trigger a browser download of arbitrary text as a file. Purely client-side (no server call). */
function downloadText(filename: string, text: string, mime = "application/json"): void {
  const blob = new Blob([text], { type: `${mime};charset=utf-8` });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** Make a filesystem-safe fragment out of an arbitrary id/name. */
function safe(part: string): string {
  return (part || "message").replace(/[^A-Za-z0-9._-]+/g, "_").slice(0, 80);
}

function stamp(): string {
  return new Date().toISOString().replace(/[:.]/g, "-");
}

/**
 * Download a single peeked/tailed message as pretty JSON (data payload + all metadata).
 * Non-destructive: operates only on data already fetched into the browser — never ACKs.
 */
export function downloadMessage(message: MessageView): void {
  const name = `message-${safe(message.messageId || "unknown")}.json`;
  downloadText(name, JSON.stringify(message, null, 2));
}

/**
 * Download every currently-shown message as a single JSON array (capped by whatever was peeked/tailed).
 * Non-destructive: operates only on data already fetched into the browser — never ACKs.
 */
export function downloadMessages(messages: MessageView[], baseName: string): void {
  const name = `messages-${safe(baseName)}-${messages.length}-${stamp()}.json`;
  downloadText(name, JSON.stringify(messages, null, 2));
}

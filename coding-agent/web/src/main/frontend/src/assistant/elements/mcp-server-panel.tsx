// Adapted from assistant-ui Elements (MIT). See LICENSE.assistant-ui and
// documentation/src/main/minisite/content/features/ui-advanced.adoc for provenance.
"use client";

import type { ComponentProps } from "react";
import { ChevronRightIcon, Loader2Icon, MinusIcon, PlugIcon, XIcon } from "./icons";
import { cn, field, mono, paper } from "./surfaces";

export type McpServerStatus =
  | "connected"
  | "connecting"
  | "needs-auth"
  | "unknown"
  | "disabled"
  | "failed";

export function mcpServerStatus(state: string): McpServerStatus {
  if (state === 'ENABLED' || state === 'RUNNING') { return 'connected'; }
  if (state === 'DISABLED') { return 'disabled'; }
  if (state === 'PENDING') { return 'connecting'; }
  if (state === 'FAILED') { return 'failed'; }
  return 'unknown';
}

export interface McpServer {
  id: string;
  name: string;
  transport?: string;
  /** Display-safe URL supplied by the backend, never the raw configuration. */
  url?: string;
  status: McpServerStatus;
  /** Omitted when the backend does not report tool metadata. */
  tools?: readonly string[];
  stateLabel?: string;
}

const DOT: Record<McpServerStatus, string> = {
  connected: "bg-[var(--yca-success)]",
  connecting: "bg-[var(--yca-warning)] animate-pulse",
  "needs-auth": "bg-[var(--yca-warning)]",
  failed: "bg-[var(--yca-error)]",
  unknown: "bg-[var(--yca-muted)]",
  disabled: "bg-[var(--yca-muted)]",
};

const COLOR: Record<McpServerStatus, string> = {
  connected: "text-[var(--yca-success)]",
  connecting: "text-[var(--yca-warning)]",
  "needs-auth": "text-[var(--yca-warning)]",
  failed: "text-[var(--yca-error)]",
  disabled: "text-[var(--yca-muted)]",
  unknown: "text-[var(--yca-muted)]",
};

const LABEL: Record<McpServerStatus, string> = {
  connected: "connected",
  connecting: "connecting",
  "needs-auth": "needs auth",
  failed: "failed",
  unknown: "unknown",
  disabled: "disabled",
};

export function McpServerPanel({
  servers,
  expandedId,
  onToggle,
  onAuthorize,
  onReset,
  resetting = false,
  className,
  ...props
}: Omit<
  ComponentProps<"div">,
  "children" | "servers" | "expandedId" | "onToggle" | "onAuthorize" | "onReset"
> & {
  servers: readonly McpServer[];
  expandedId?: string;
  onToggle?: (id: string) => void;
  onAuthorize?: (id: string) => void;
  onReset?: (id: string) => void;
  resetting?: boolean;
}) {
  const connected = servers.filter(
    (server) => server.status === "connected",
  ).length;

  return (
    <div
      data-slot="mcp-server-panel"
      className={cn(
        paper,
        "flex w-full max-w-sm flex-col gap-1 rounded-2xl p-3",
        className,
      )}

      {...props}
    >
      <div className="flex items-baseline justify-between px-1 pb-1">
        <span className="text-[13.5px] font-medium">Servers</span>
        <span className={cn(mono, "text-[var(--yca-muted)] tabular-nums")}>
          {connected} of {servers.length} connected
        </span>
      </div>

      {servers.map((server) => {
        const expanded = expandedId === server.id;
        const StatusIcon = server.status === "disabled" ? MinusIcon
          : server.status === "failed" ? XIcon
          : server.status === "connecting" ? Loader2Icon : PlugIcon;
        const rowClassName = cn(
          "flex items-center gap-2.5 rounded-xl px-1.5 py-2 text-start",
          onToggle && "hover:bg-[var(--yca-panel-2)] transition-colors",
        );
        const row = (
          <>
            <ChevronRightIcon
              className={cn(
                "text-[var(--yca-muted)] size-3 shrink-0 transition-transform duration-200 motion-reduce:transition-none",
                expanded && "rotate-90",
              )}
            />
            <StatusIcon data-slot="mcp-status-icon" className={cn(COLOR[server.status], "size-3.5 shrink-0", server.status === "connecting" && "animate-spin motion-reduce:animate-none")} />
            <span className="min-w-0 flex-1 truncate text-[13.5px]">
              {server.name}
            </span>
            <span className={cn(mono, "text-[var(--yca-muted)] shrink-0")}>
              {server.tools !== undefined && `${server.tools.length} tools`}
            </span>
            <span
              aria-hidden
              className={cn(
                "size-1.5 shrink-0 rounded-full",
                DOT[server.status],
              )}
            />
            <span className={cn(mono, COLOR[server.status])}>{server.stateLabel ?? LABEL[server.status]}</span>
          </>
        );
        return (
          <div key={server.id} className="flex flex-col">
                <div className="flex flex-wrap items-center gap-2">
              {onToggle ? (
                <button
                  type="button"
                  aria-expanded={expanded}
                  onClick={() => onToggle(server.id)}
                  className={cn(rowClassName, "min-w-0 flex-1")}
                >
                  {row}
                </button>
              ) : (
                <div className={cn(rowClassName, "min-w-0 flex-1")}>{row}</div>
              )}
              {onReset && <button type="button" disabled={resetting} aria-label={`Reset ${server.name}`} onClick={() => onReset(server.id)} className="rounded border border-[var(--yca-border)] px-2 py-1 text-xs disabled:opacity-50">Reset</button>}
            </div>

            {expanded && (
              <div className="fade-in slide-in-from-top-1 animate-in flex flex-col gap-1.5 px-1.5 ps-8 pb-2 duration-200">
                <div className="flex items-center gap-2">
                  <span className={cn(mono, "text-[var(--yca-muted)]")}>
                    {server.transport === "http" ? "Streamable HTTP"
                      : server.transport === "stdio" ? "Standard input/output (stdio; command, arguments and environment hidden)"
                      : server.transport === "in-process" ? "Built-in (in-process)"
                      : server.transport ?? "Connection details unavailable"}
                  </span>
                  <span className={cn(mono, "text-[var(--yca-muted)]")}>
                    {server.transport && "· "}{server.stateLabel ?? LABEL[server.status]}
                  </span>
                  {server.status === "needs-auth" && onAuthorize && (
                    <button
                      type="button"
                      onClick={() => onAuthorize(server.id)}
                      className="ms-auto rounded-full bg-[var(--yca-panel-2)] px-2 py-0.5 text-[11px] font-medium text-[var(--yca-warning)] transition-colors duration-150 hover:bg-[var(--yca-panel)]"
                    >
                      Authorize
                    </button>
                  )}
                </div>
                {server.url && <div className={cn(mono, "min-w-0 whitespace-pre-wrap break-all text-[var(--yca-muted)]")} aria-label={`URL for ${server.name}`}>{server.url}</div>}
                {server.transport === "http" && !server.url && <p className={cn(mono, "text-[var(--yca-muted)]")}>URL unavailable (invalid or unsafe configuration)</p>}
                <div className="flex flex-wrap gap-1">
                  {server.tools?.map((tool) => (
                    <span
                      key={tool}
                      className={cn(
                        field,
                        mono,
                        "text-[var(--yca-muted)] rounded-md px-1.5 py-0.5",
                      )}
                    >
                      {tool}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

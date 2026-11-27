// Adapted from assistant-ui Elements (MIT). See LICENSE.assistant-ui and
// documentation/src/main/minisite/content/features/ui-advanced.adoc for provenance.
"use client";

import type { ComponentProps } from "react";
import { BotIcon, CheckIcon } from "./icons";
import { cn, field, inkButton, mono, paper } from "./surfaces";

export interface AgentSkill {
  name: string;
  description: string;
}

export function AgentCard({
  name,
  description,
  provider,
  version,
  model,
  endpoint,
  skills,
  connected,
  onConnect,
  className,
  ...props
}: Omit<
  ComponentProps<"div">,
  | "children"
  | "name"
  | "description"
  | "provider"
  | "version"
  | "model"
  | "endpoint"
  | "skills"
  | "connected"
  | "onConnect"
> & {
  name: string;
  description: string;
  provider: string;
  version: string;
  model: string;
  endpoint: string;
  skills: readonly AgentSkill[];
  connected: boolean;
  onConnect?: () => void;
}) {
  return (
    <div
      data-slot="agent-card"
      className={cn(
        paper,
        "flex w-full max-w-sm flex-col gap-3.5 rounded-[20px] p-4",
        className,
      )}

      {...props}
    >
      <div className="flex items-start gap-3">
        <span className="bg-[var(--yca-panel-2)] text-[var(--yca-muted)] flex size-9 shrink-0 items-center justify-center rounded-xl">
          <BotIcon className="size-4" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="flex items-baseline gap-2">
            <span className="truncate text-[13.5px] font-medium">{name}</span>
            <span className={cn(mono, "text-[var(--yca-muted)] shrink-0")}>
              v{version}
            </span>
          </span>
          <span className="text-[var(--yca-muted)] truncate text-xs">
            {provider}
          </span>
        </div>
      </div>

      <p className="text-[var(--yca-muted)] text-xs leading-relaxed">
        {description}
      </p>

      <div className="flex flex-col gap-1.5">
        {skills.map((skill) => (
          <div key={skill.name} className="flex items-baseline gap-2">
            <span
              className={cn(
                field,
                mono,
                "text-[var(--yca-muted)] shrink-0 rounded-md px-1.5 py-0.5",
              )}
            >
              {skill.name}
            </span>
            <span className="text-[var(--yca-muted)] min-w-0 flex-1 truncate text-xs">
              {skill.description}
            </span>
          </div>
        ))}
      </div>

      <div className="border-[var(--yca-border)] flex items-center gap-2 border-t pt-3">
        <span
          className={cn(mono, "text-[var(--yca-muted)] min-w-0 flex-1 truncate")}
        >
          {endpoint}
        </span>
        <span className={cn(mono, "text-[var(--yca-muted)] shrink-0")}>
          {model}
        </span>
      </div>

      <button
        type="button"
        onClick={onConnect}
        disabled={connected}
        className={cn(
          connected
            ? cn(field, "text-[var(--yca-muted)]")
            : cn(inkButton, "justify-center"),
          "flex h-8 items-center justify-center gap-1.5 rounded-full px-3.5 text-xs font-medium disabled:pointer-events-none",
        )}
      >
        {connected ? (
          <>
            <CheckIcon className="size-3.5 text-[var(--yca-success)]" />
            Connected
          </>
        ) : (
          "Connect"
        )}
      </button>
    </div>
  );
}

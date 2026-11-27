// Adapted from assistant-ui Elements (MIT). See LICENSE.assistant-ui and
// documentation/src/main/minisite/content/features/ui-advanced.adoc for provenance.
"use client";

import { useId, useState, type ComponentProps } from "react";
import { CheckIcon, Loader2Icon, MinusIcon, XIcon } from "./icons";
import { cn, ghostButton, mono } from "./surfaces";
import { clamp } from "./range";

export type TodoStatus = "pending" | "active" | "done" | "failed" | "cancelled";

export interface TodoItem {
  id: string;
  text: string;
  status: TodoStatus;
  description?: string | undefined;
  reason?: string | undefined;
}

export function TodoList({
  items,
  revision,
  title = "Todos",
  description,
  maxVisible,
  className,
  ...props
}: Omit<ComponentProps<"div">, "children" | "items" | "revision" | "title"> & {
  items: readonly TodoItem[];
  revision?: number | undefined;
  title?: string | undefined;
  description?: string | undefined;
  maxVisible?: number | undefined;
}) {
  const done = items.filter((item) => item.status === "done").length;
  const total = items.filter((item) => item.status !== "cancelled").length;
  const visibleCount = Math.floor(
    clamp(maxVisible ?? items.length, 0, items.length),
  );
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  const hiddenItems = items.filter(
    (item, index) =>
      index >= visibleCount &&
      item.status !== "active" &&
      item.status !== "failed",
  );
  const canFold = maxVisible !== undefined && hiddenItems.length > 0;

  return (
    <div
      data-slot="todo-list"
      className={cn("flex min-w-0 w-full max-w-sm flex-col gap-3", className)}
      {...props}
    >
      <div className="flex min-w-0 flex-wrap items-start justify-between gap-x-3 gap-y-1">
        <div className="min-w-0">
          <span className="text-[13.5px] font-medium [overflow-wrap:anywhere]">{title}</span>
          {description ? (
            <p className="text-[var(--yca-muted)] text-xs leading-4 [overflow-wrap:anywhere]">
              {description}
            </p>
          ) : null}
        </div>
        <span
          className={cn(
            mono,
            "text-[var(--yca-muted)] shrink-0 whitespace-nowrap tabular-nums",
          )}
        >
          {revision === undefined
            ? `${done}/${total}`
            : `${done}/${total} · rev ${revision}`}
        </span>
      </div>
      <ul id={listId} className="flex min-w-0 flex-col gap-1">
        {items.map((item, index) => {
          const collapsed =
            canFold &&
            index >= visibleCount &&
            item.status !== "active" &&
            item.status !== "failed";
          const rowClassName =
            "fade-in slide-in-from-bottom-1 animate-in fill-mode-both flex items-start gap-2.5 py-0.5 text-[13.5px] duration-300 motion-reduce:animate-none";
          const row = (
            <>
              <span
                aria-hidden
                className="flex size-4 h-5 shrink-0 items-center justify-center"
              >
                {item.status === "done" ? (
                  <span className="border-[var(--yca-border)] bg-[var(--yca-panel-2)] flex size-3.5 items-center justify-center rounded-[5px] border">
                    <CheckIcon className="text-[var(--yca-muted)] size-2.5" />
                  </span>
                ) : item.status === "failed" ? (
                  <span className="flex size-3.5 items-center justify-center rounded-[5px] border border-[var(--yca-error)] bg-[var(--yca-panel-2)]">
                    <XIcon className="size-2.5 text-[var(--yca-error)]" />
                  </span>
                ) : item.status === "cancelled" ? (
                  <span className="border-[var(--yca-border)] text-[var(--yca-muted)] flex size-3.5 items-center justify-center rounded-[5px] border">
                    <MinusIcon className="size-2.5" />
                  </span>
                ) : item.status === "active" ? (
                  <Loader2Icon className="size-3.5 animate-spin text-[var(--yca-accent)] motion-reduce:animate-none" />
                ) : (
                  <span className="border-[var(--yca-border)] size-3.5 rounded-[5px] border" />
                )}
              </span>
              <span className="sr-only">{item.status}</span>
              <div className="min-w-0 flex-1 leading-5 [overflow-wrap:anywhere]">
                <span
                  className={cn(
                    item.status === "done" &&
                      "text-[var(--yca-muted)] line-through decoration-[1.5px]",
                    item.status === "active" && "text-[var(--yca-text)]",
                    item.status === "pending" && "text-[var(--yca-muted)]",
                    item.status === "failed" &&
                      "text-[var(--yca-error)]",
                    item.status === "cancelled" &&
                      "text-[var(--yca-muted)] line-through decoration-[1.5px]",
                  )}
                >
                  {item.text}
                </span>
                {item.status === "failed" ? (
                  item.reason ? (
                    <p className="text-[var(--yca-muted)] text-xs leading-4 [overflow-wrap:anywhere]">
                      {item.reason}
                    </p>
                  ) : null
                ) : item.status !== "cancelled" && item.description ? (
                  <p className="text-[var(--yca-muted)] text-xs leading-4 [overflow-wrap:anywhere]">
                    {item.description}
                  </p>
                ) : null}
              </div>
            </>
          );

          if (collapsed && !expanded) { return null; }

          return (
            <li
              key={item.id}
              data-status={item.status}
              className={rowClassName}
            >
              {row}
            </li>
          );
        })}
      </ul>
      {canFold ? (
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={listId}
          onClick={() => setExpanded((current) => !current)}
          className={cn(
            ghostButton,
            "self-start px-2 py-1 text-xs font-medium",
          )}
        >
          {expanded ? "Show less" : `Show ${hiddenItems.length} more`}
        </button>
      ) : null}
    </div>
  );
}

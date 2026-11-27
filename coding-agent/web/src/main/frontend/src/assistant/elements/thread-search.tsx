// Adapted from assistant-ui Elements (MIT). See LICENSE.assistant-ui and
// documentation/src/main/minisite/content/features/ui-advanced.adoc for provenance.
"use client";

import { useId, type ComponentProps } from "react";
import { PinIcon, SearchIcon } from "./icons";
import { cn, field, mono, paper } from "./surfaces";

export interface SearchableThread {
  id: string;
  title: string;
  group: string;
  preview: string;
  pinned?: boolean;
}

type ThreadSearchInteractionProps =
  | {
      onActiveChange: (id: string) => void;
      onSelect: (id: string) => void;
    }
  | {
      onActiveChange?: (id: string) => void;
      onSelect?: undefined;
    };

type ThreadSearchProps = Omit<
  ComponentProps<"div">,
  | "children"
  | "threads"
  | "query"
  | "activeId"
  | "onQueryChange"
  | "onActiveChange"
  | "onSelect"
> & {
  threads: readonly SearchableThread[];
  query: string;
  activeId: string;
  onQueryChange?: (query: string) => void;
  /** Backend search may match message content absent from title/preview. */
  serverFiltered?: boolean;
  loading?: boolean;
  searchLabel?: string;
  emptyLabel?: string;
  showGroupCounts?: boolean;
} & ThreadSearchInteractionProps;

export function ThreadSearch({
  threads,
  query,
  activeId,
  onQueryChange,
  onActiveChange,
  onSelect,
  serverFiltered = false,
  loading = false,
  searchLabel = "Search threads",
  emptyLabel = "No threads yet",
  showGroupCounts = false,
  className,
  ...props
}: ThreadSearchProps) {
  const listId = useId();
  const optionId = (id: string) => `${listId}-${id}`;
  const matches = loading ? [] : serverFiltered ? threads : threads.filter((thread) =>
    `${thread.title} ${thread.preview}`
      .toLowerCase()
      .includes(query.toLowerCase()),
  );
  const pinned = matches.filter((thread) => thread.pinned);
  const groups = [
    ...new Set(matches.filter((t) => !t.pinned).map((t) => t.group)),
  ];

  const ordered = [
    ...pinned,
    ...groups.flatMap((group) =>
      matches.filter((thread) => !thread.pinned && thread.group === group),
    ),
  ];

  const move = (delta: number) => {
    if (ordered.length === 0 || !onActiveChange) { return false; }
    const at = ordered.findIndex((thread) => thread.id === activeId);
    // activeId can be filtered out by the query; start from the edge the key implies
    const from = at === -1 ? (delta > 0 ? -1 : 0) : at;
    const next = ordered[(from + delta + ordered.length) % ordered.length];
    if (!next) { return false; }
    onActiveChange(next.id);
    document
      .getElementById(optionId(next.id))
      ?.scrollIntoView({ block: "nearest" });
    return true;
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) {
      return;
    }
    if (event.key === "ArrowDown") {
      if (move(1)) { event.preventDefault(); }
    } else if (event.key === "ArrowUp") {
      if (move(-1)) { event.preventDefault(); }
    } else if (event.key === "Enter") {
      const active = ordered.find((thread) => thread.id === activeId);
      if (active && onSelect) {
        event.preventDefault();
        onSelect(active.id);
      }
    }
  };

  const row = (thread: SearchableThread) => {
    const className = cn(
      "flex flex-col gap-0.5 rounded-xl px-2 py-1 text-start transition-colors",
      thread.id === activeId
        ? "bg-[var(--yca-panel-2)]"
        : onSelect
          ? "hover:bg-[var(--yca-panel-2)]"
          : undefined,
    );
    const content = (
      <>
        <span className="flex items-center gap-1.5">
          {thread.pinned && (
            <PinIcon className="text-[var(--yca-muted)] size-2.5 shrink-0" />
          )}
          <span className="min-w-0 flex-1 truncate text-[13px]">
            {thread.title}
          </span>
        </span>
        <span className="text-[var(--yca-muted)] truncate text-xs">
          {thread.preview}
        </span>
      </>
    );

    return onSelect ? (
      <button
        key={thread.id}
        id={optionId(thread.id)}
        type="button"
        role="option"
        tabIndex={-1}
        aria-selected={thread.id === activeId}
        onMouseDown={(event) => event.preventDefault()}
        onClick={() => {
          onActiveChange?.(thread.id);
          onSelect(thread.id);
        }}
        className={className}
      >
        {content}
      </button>
    ) : (
      <div
        key={thread.id}
        id={optionId(thread.id)}
        role="option"
        aria-selected={thread.id === activeId}
        className={className}
      >
        {content}
      </div>
    );
  };

  return (
    <div
      data-slot="thread-search"
      className={cn(
        paper,
        "flex w-full max-w-sm flex-col gap-1.5 rounded-2xl p-3",
        className,
      )}

      {...props}
    >
      <div
        className={cn(
          field,
          "flex items-center gap-2 rounded-xl px-2.5 py-1.5",
        )}
      >
        <SearchIcon className="text-[var(--yca-muted)] size-3.5 shrink-0" />
        <input
          value={query}
          onChange={(event) => onQueryChange?.(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={searchLabel}
          aria-label={searchLabel}
          role="combobox"
          aria-expanded={true}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={
            ordered.some((thread) => thread.id === activeId)
              ? optionId(activeId)
              : undefined
          }
          className="text-[var(--yca-text)] placeholder:text-[var(--yca-muted)] min-w-0 flex-1 bg-transparent text-[13px] outline-none"
        />
      </div>

      <div
        id={listId}
        role="listbox"
        aria-label="Threads"
        aria-busy={loading}
        className="flex flex-col gap-1.5"
      >
        {pinned.length > 0 && (
          <div role="group" aria-label="Pinned" className="flex flex-col">
            <span
              aria-hidden
              className={cn(mono, "text-[var(--yca-muted)] px-2 pb-1")}
            >
              pinned
            </span>
            {pinned.map(row)}
          </div>
        )}

        {groups.map((group) => (
          <div
            key={group}
            role="group"
            aria-label={group}
            className="flex flex-col"
          >
            <span
              aria-hidden
              className={cn(mono, "text-[var(--yca-muted)] px-2 pb-1")}
            >
              {group}
              {showGroupCounts && <span className="ml-2">{matches.filter(thread => !thread.pinned && thread.group === group).length}</span>}
            </span>
            {matches
              .filter((thread) => !thread.pinned && thread.group === group)
              .map(row)}
          </div>
        ))}
      </div>

      {loading ? <span role="status" className="text-sm text-[var(--yca-muted)]">Loading...</span> : matches.length === 0 && (
        <span
          role="status"
          className="text-[var(--yca-muted)] px-2 py-4 text-center text-xs"
        >
          {threads.length === 0
            ? emptyLabel
            : `No thread matches “${query}”`}
        </span>
      )}
    </div>
  );
}

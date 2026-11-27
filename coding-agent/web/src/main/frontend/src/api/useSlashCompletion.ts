import { useEffect, useRef, useState } from 'react';
import { call } from './rpc';

export type SlashSuggestion = {
  items: string[];
  replaceStart: number;
  replaceEnd: number;
  label: string;
};

export const NO_SUGGESTION: SlashSuggestion = { items: [], replaceStart: 0, replaceEnd: 0, label: '' };

/**
 * Fetches slash-command completions for the prompt line from the backend
 * (`slash.complete`). Debounced so typing doesn't spam the RPC; a sequence
 * guard drops out-of-order (stale) responses; the result is keyed on the
 * session id so switching tabs can never apply another tab's completions.
 */
export function useSlashCompletion(text: string, cursor: number, sessionId: string | undefined): SlashSuggestion {
  const [suggestion, setSuggestion] = useState<SlashSuggestion>(NO_SUGGESTION);
  const sequenceRef = useRef(0);
  const sessionRef = useRef(sessionId);
  useEffect(() => {
    sessionRef.current = sessionId;
  }, [sessionId]);

  useEffect(() => {
    // only complete when the caret token starts with '/' (or an @agent prefix),
    // mirroring the shared CompletionEngine's prompt grammar
    const seq = ++sequenceRef.current;
    setSuggestion(NO_SUGGESTION);
    // Invalidate even when leaving slash mode; an already in-flight RPC must not reopen it.
    if (!sessionId || !text.trim().startsWith('/')) {
      setSuggestion(NO_SUGGESTION);
      return;
    }
    const handle = setTimeout(() => {
      call<SlashSuggestion[]>('slash.complete', { id: sessionId, text, cursor })
        .then((results) => {
          // drop stale/late responses (a newer keystroke superseded this one)
          if (sequenceRef.current !== seq || sessionRef.current !== sessionId) {
            return;
          }
          const r = (results ?? [])[0];
          setSuggestion(r && r.items?.length ? r : NO_SUGGESTION);
        })
        .catch(() => {
          if (sequenceRef.current === seq) {
            setSuggestion(NO_SUGGESTION);
          }
        });
    }, 120);
    return () => {
      clearTimeout(handle);
      sequenceRef.current++;
    };
  }, [text, cursor, sessionId]);

  return suggestion;
}

/** Applies a suggestion to the prompt: replaces [replaceStart, replaceEnd) with the chosen item. */
export function applySuggestion(text: string, suggestion: SlashSuggestion, item: string): { text: string; cursor: number } {
  const next = text.slice(0, suggestion.replaceStart) + item + text.slice(suggestion.replaceEnd);
  return { text: next, cursor: suggestion.replaceStart + item.length };
}

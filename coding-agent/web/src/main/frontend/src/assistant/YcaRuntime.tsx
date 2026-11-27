import { type ReactNode, useEffect, useMemo } from 'react';
import { AssistantRuntimeProvider, useAui, useExternalStoreRuntime, type AppendMessage, type ThreadMessageLike, type ToolCallMessagePart } from '@assistant-ui/react';
import type { WebEvent } from '../api/useSessionStream';
import { createYcaMessageProjector } from './runtime/eventsToMessages';

import { parseToolArgs } from '../lib/toolDetails';

const convertMessage = (message: ThreadMessageLike): ThreadMessageLike => {
  if (typeof message.content === 'string') {
    return message;
  }
  return { ...message, content: message.content.map((part) => part.type === 'tool-call'
    // Supplying args bypasses the runtime's unbounded partial-JSON parser. Exact raw
    // argsText and metadata stay untouched; YCA's bounded/raw renderers own presentation.
    ? { ...part, args: part.args ?? (parseToolArgs(part.argsText) as ToolCallMessagePart['args'] | null) ?? {} }
    : part) };
};

/** A presentation adapter only: YCA RPC/SSE remains the sole owner of conversation history. */
export function YcaRuntime({ events, busy, initializing, sending, onNew, onCancel, children }: {
  events: WebEvent[];
  busy: boolean;
  initializing: boolean;
  sending: boolean;
  onNew: (text: string) => Promise<void>;
  onCancel: () => Promise<void>;
  children: ReactNode;
}) {
  const project = useMemo(() => createYcaMessageProjector(), []);
  const messages = useMemo(() => project(events), [project, events]);
  const runtime = useExternalStoreRuntime({
    messages,
    convertMessage,
    isRunning: busy,
    isLoading: initializing,
    isDisabled: initializing,
    isSendDisabled: sending,
    onNew: async (message: AppendMessage) => {
      const text = message.content.filter((part) => part.type === 'text').map((part) => part.text).join('\n');
      await onNew(text);
    },
    onCancel,
    // Deliberately no setMessages/edit/reload/branch/attachment adapters: the backend does not
    // implement these contracts. Prompt queueing stays server-owned, not a second local queue.
  });
  return <AssistantRuntimeProvider runtime={runtime}>{children}</AssistantRuntimeProvider>;
}

/** Keep programmatic completion/clearing and the primitive's trigger matcher on the same draft. */
export function ComposerDraft({ text }: { text: string }) {
  const aui = useAui();
  useEffect(() => {
    if (aui.composer().getState().text !== text) {
      aui.composer().setText(text);
    }
  }, [aui, text]);
  return null;
}

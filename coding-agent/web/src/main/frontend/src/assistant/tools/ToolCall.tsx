// Adapted from assistant-ui Elements tool-call.tsx (MIT).
// See LICENSE.assistant-ui here and documentation/src/main/minisite/content/features/ui-advanced.adoc.
import { useId, useRef, useState, type ReactNode } from 'react';
import { useScrollLock } from '@assistant-ui/react';

/** Controlled ToolCall Element with request/result slots instead of lossy strings.
 * Native controls replace Base UI; no runtime/thread provider is required.
 */
export function ToolCall({ label, query, status, open, onOpenChange, request, result, preview, children }: {
  label: string;
  query?: string;
  status: 'working' | 'interrupted' | 'pending' | 'failed' | 'completed';
  open: boolean;
  onOpenChange: (open: boolean) => void;
  request: ReactNode;
  result: ReactNode;
  preview?: ReactNode;
  children?: ReactNode;
}) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  // ToolFallbackRoot's scroll anchoring also works without a thread runtime.
  const lockScroll = useScrollLock(root, 200);
  const handleOpenChange = () => {
    lockScroll();
    onOpenChange(!open);
  };
  return <div ref={root} data-slot="tool-call" className={`yca-tool-card yca-tool-${status}`}>
    <button type="button" data-slot="tool-call-trigger" className="yca-tool-header" onClick={handleOpenChange} aria-expanded={open} aria-controls={id} title={open ? 'Collapse tool details' : 'Expand tool details'}>
      <span aria-hidden="true">{open ? '\u25be' : '\u25b8'}</span>
      <span className="yca-tool-status">{status}</span>
      <span className="yca-tool-name">{label}</span>
      {query && <span className="yca-tool-summary" title={query}>{query}</span>}
    </button>
    {!open && preview && <div className="yca-tool-body" data-slot="tool-call-diff">{preview}</div>}
    <div id={id} hidden={!open} data-slot="tool-call-content">
      {open && <div className="yca-tool-body">
        <section aria-label="Tool request"><h4>Request</h4>{request}</section>
        <section aria-label="Tool result">{result}</section>
        {children}
      </div>}
    </div>
  </div>;
}

/** Raw payloads stay unmounted until explicitly requested. */
export function ToolDisclosure({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return <details className="yca-tool-disclosure" onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary>{label}</summary>
    {open && children}
  </details>;
}

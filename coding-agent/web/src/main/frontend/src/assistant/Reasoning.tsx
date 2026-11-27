// Adapted from assistant-ui Elements reasoning.tsx, revision
// f89ae49df57862c3645493bce84aabb2b111bda0 (MIT; see tools/LICENSE.assistant-ui).
// Native disclosure controls replace the upstream Collapsible/CVA/icons. The upstream
// streaming/manual-open state and bottom-pinned preview behavior remain display-only.
import { type ReactNode, useEffect, useId, useRef, useState } from 'react';
import { useScrollLock } from '@assistant-ui/react';

export function Reasoning({ streaming, children, manualOpen, onManualOpenChange }: {
  streaming: boolean; children: ReactNode; manualOpen?: boolean | null; onManualOpenChange?: (open: boolean) => void;
}) {
  const [userOpen, setUserOpen] = useState<boolean | null>(null);
  const open = manualOpen ?? userOpen ?? streaming;
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const lockScroll = useScrollLock(root, 200);
  useEffect(() => {
    if (!streaming || !open) {
      return;
    }
    const scrollEl = viewport.current;
    const contentEl = content.current;
    if (!scrollEl || !contentEl) {
      return;
    }
    let pinned = true;
    let lastTop = scrollEl.scrollTop;
    let lastHeight = scrollEl.scrollHeight;
    const pin = () => {
      if (pinned) {
        scrollEl.scrollTop = scrollEl.scrollHeight;
      }
    };
    const onScroll = () => {
      const atBottom = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight <= 1;
      if (atBottom) {
        pinned = true;
      } else if (scrollEl.scrollTop < lastTop && scrollEl.scrollHeight === lastHeight) {
        pinned = false;
      }
      lastTop = scrollEl.scrollTop;
      lastHeight = scrollEl.scrollHeight;
    };
    pin();
    scrollEl.addEventListener('scroll', onScroll);
    const observer = new ResizeObserver(pin);
    observer.observe(contentEl);
    return () => {
      scrollEl.removeEventListener('scroll', onScroll);
      observer.disconnect();
    };
  }, [streaming, open]);
  return <div ref={root} data-slot="reasoning-root" className="rounded border-l-2 px-3 py-1 text-[15px]" style={{ borderColor: 'var(--yca-muted)', color: 'var(--yca-muted)' }}>
    <button type="button" data-slot="reasoning-trigger" aria-expanded={open} aria-controls={id}
      className="text-sm" onClick={() => { lockScroll(); setUserOpen(!open); onManualOpenChange?.(!open); }}>
      {open ? '- ' : '+ '}Reasoning{streaming ? ' (streaming)' : ''}
    </button>
    <div id={id} hidden={!open} ref={viewport} data-slot="reasoning-text" className="max-h-64 overflow-y-auto">
      {open && <div ref={content}>{children}</div>}
    </div>
  </div>;
}

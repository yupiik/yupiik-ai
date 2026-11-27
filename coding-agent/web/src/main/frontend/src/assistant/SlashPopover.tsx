import { useMemo, type RefObject } from 'react';
import { ComposerPrimitive, useAui } from '@assistant-ui/react';
import { applySuggestion, type SlashSuggestion } from '../api/useSlashCompletion';

/** Backend-driven completion, not the static slash adapter's execute-a-command behavior.
 * TriggerPopover owns disclosure, ARIA, highlighting and keyboard navigation. YCA owns
 * exact replacement ranges (including arguments and suffixes) and explicit submission.
 */
export function SlashPopover({ text, cursor, suggestion, visible, onAccept, inputRef }: {
  text: string;
  cursor: number;
  suggestion: SlashSuggestion;
  visible: boolean;
  onAccept: (text: string, cursor: number) => void;
  inputRef: RefObject<HTMLTextAreaElement | null>;
}) {
  const aui = useAui();
  const adapter = useMemo(() => {
    const items = suggestion.items.map((label, index) => ({ id: String(index), type: 'command', label }));
    return { categories: () => [], categoryItems: () => items, search: () => items };
  }, [suggestion]);
  return <ComposerPrimitive.TriggerPopover char="/" adapter={adapter}
    matcher={(current) => visible && current === text && current.trimStart().startsWith('/') && cursor > current.indexOf('/')
      ? { query: current, offset: current.indexOf('/'), endOffset: cursor } : null}
    aria-label={suggestion.label || 'Command suggestions'}
    className="absolute bottom-full left-0 z-10 mb-1 max-h-72 w-full max-w-md overflow-y-auto rounded border shadow-lg"
    style={{ background: 'var(--yca-panel)', borderColor: 'var(--yca-border)' }}>
    <ComposerPrimitive.TriggerPopover.Action onExecute={(item) => {
      const applied = applySuggestion(text, suggestion, item.label);
      // The stock directive inserts a trailing space. Backend ranges are authoritative instead.
      aui.composer().setText(applied.text);
      onAccept(applied.text, applied.cursor);
      requestAnimationFrame(() => {
        inputRef.current?.focus();
        inputRef.current?.setSelectionRange(applied.cursor, applied.cursor);
      });
    }} />
    {suggestion.label && <div className="border-b px-3 py-1 text-xs font-bold uppercase" style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-muted)' }}>{suggestion.label}</div>}
    <ComposerPrimitive.TriggerPopoverItems>{(items) => items.map((item, index) =>
      <ComposerPrimitive.TriggerPopoverItem key={item.id} item={item} index={index}
        onMouseDown={(event) => event.preventDefault()}
        className="block w-full truncate px-3 py-1 text-left text-sm data-[highlighted]:bg-white/10">{item.label}</ComposerPrimitive.TriggerPopoverItem>)}</ComposerPrimitive.TriggerPopoverItems>
  </ComposerPrimitive.TriggerPopover>;
}

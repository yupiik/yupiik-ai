import { useEffect, useId, useRef, useState } from 'react';

export type Visibility = 'shown' | 'collapsed' | 'hidden';

const options: { value: Visibility; label: string }[] = [
  { value: 'hidden', label: 'Hidden' },
  { value: 'collapsed', label: 'Collapsed' },
  { value: 'shown', label: 'Expanded' },
];

export function VisibilityMenu({ label, value, onChange }: {
  label: string;
  value: Visibility;
  onChange: (value: Visibility) => void;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const container = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const items = useRef<(HTMLButtonElement | null)[]>([]);
  const initialFocus = useRef(0);

  useEffect(() => {
    if (!open) {
      return;
    }
    items.current[initialFocus.current]?.focus();
    const onOutsideClick = (event: PointerEvent) => {
      if (!container.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', onOutsideClick);
    return () => document.removeEventListener('pointerdown', onOutsideClick);
  }, [open]);

  const close = () => {
    setOpen(false);
    trigger.current?.focus();
  };
  const show = (index = options.findIndex(option => option.value === value)) => {
    initialFocus.current = index;
    if (open) {
      items.current[index]?.focus();
    } else {
      setOpen(true);
    }
  };

  return <div ref={container} className="yca-visibility" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
      setOpen(false);
    }
  }} onKeyDown={event => {
    if (open && event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
  }}>
    <button ref={trigger} type="button" className="rounded px-1.5 hover:bg-white/5"
      aria-haspopup="menu" aria-expanded={open} aria-controls={id}
      title={`${label} visibility`}
      onClick={() => { if (open) { close(); } else { show(); } }}
      onKeyDown={event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault();
          show(event.key === 'ArrowDown' ? 0 : options.length - 1);
        }
      }}>
      {label}: {options.find(option => option.value === value)?.label} <span aria-hidden="true">{'\u25be'}</span>
    </button>
    <div id={id} role="menu" aria-label={`${label} visibility`} className="yca-visibility-menu" hidden={!open}
      onKeyDown={event => {
        const index = items.current.indexOf(document.activeElement as HTMLButtonElement);
        let next: number | undefined;
        if (event.key === 'ArrowDown') {
          next = (index + 1) % options.length;
        } else if (event.key === 'ArrowUp') {
          next = (index + options.length - 1) % options.length;
        } else if (event.key === 'Home') {
          next = 0;
        } else if (event.key === 'End') {
          next = options.length - 1;
        } else if (event.key === 'Tab') {
          close();
        }
        if (next !== undefined) {
          event.preventDefault();
          items.current[next]?.focus();
        }
      }}>
      {options.map((option, index) => <button key={option.value} ref={element => { items.current[index] = element; }}
        type="button" role="menuitemradio" aria-checked={value === option.value} tabIndex={-1}
        onClick={() => {
          close();
          onChange(option.value);
        }}>
        <span className="yca-visibility-check" aria-hidden="true">{value === option.value ? '\u2713' : ''}</span>
        {option.label}
      </button>)}
    </div>
  </div>;
}

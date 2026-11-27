import type { ComponentProps } from 'react';

// Small local SVG replacements for the selected upstream Elements' lucide imports.
// These presentation-only icons do not bring an icon library into the bundle.
function icon(path: string) {
  return function Icon(props: ComponentProps<'svg'>) {
    return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}><path d={path} /></svg>;
  };
}
export const CheckIcon = icon('M5 12l4 4L19 6');
export const XIcon = icon('M6 6l12 12M6 18L18 6');
export const MinusIcon = icon('M5 12h14');
export const Loader2Icon = icon('M20 12a8 8 0 1 1-8-8');
export const ChevronRightIcon = icon('M9 5l7 7-7 7');
export const PlugIcon = icon('M8 3v5m8-5v5M6 8h12v4a6 6 0 0 1-12 0V8m6 10v4');
export const SearchIcon = icon('M15 15l6 6M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0');
export const PinIcon = icon('M9 3h6l-1 7 4 4H6l4-4-1-7m3 11v8');
export const BotIcon = icon('M12 3v3M5 6h14v14H5V6m3 6h1m6 0h1m-7 5h6');
export const BrainIcon = icon('M12 5c-7-5-12 9-6 12 0 5 6 5 6 1V5m0 0c7-5 12 9 6 12 0 5-6 5-6 1M6 10l3 2m9-2-3 2');
export const PauseIcon = icon('M9 5v14M15 5v14');
export const RotateCcwIcon = icon('M3 4v6h6M3 10a9 9 0 1 1 1 8');

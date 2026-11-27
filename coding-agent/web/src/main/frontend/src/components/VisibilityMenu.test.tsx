// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { VisibilityMenu, type Visibility } from './VisibilityMenu';

let host: HTMLDivElement;
let root: Root;
let onChange: ReturnType<typeof vi.fn<(value: Visibility) => void>>;

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  onChange = vi.fn();
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});

function render(value: Visibility = 'collapsed', label = 'Tools') {
  act(() => root.render(<VisibilityMenu label={label} value={value} onChange={onChange} />));
}

function trigger(): HTMLButtonElement {
  return host.querySelector('button')!;
}

function menu(): HTMLDivElement {
  return host.querySelector('[role="menu"]')!;
}

function items(): HTMLButtonElement[] {
  return Array.from(host.querySelectorAll('[role="menuitemradio"]'));
}

function click(element: HTMLElement) {
  act(() => element.click());
}

function key(element: HTMLElement, value: string) {
  act(() => element.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true })));
}

describe('VisibilityMenu', () => {
  it('labels the reusable thoughts control and menu', () => {
    render('collapsed', 'Thoughts');
    expect(trigger().textContent).toContain('Thoughts: Collapsed');
    expect(menu().getAttribute('aria-label')).toBe('Thoughts visibility');
  });
  it.each([
    ['hidden', 'Hidden'], ['collapsed', 'Collapsed'], ['shown', 'Expanded'],
  ] as const)('renders the server-synced %s state and selected checkmark', (value, label) => {
    render(value);
    expect(trigger().textContent).toContain(`Tools: ${label}`);
    expect(trigger().getAttribute('aria-expanded')).toBe('false');
    expect(trigger().getAttribute('aria-controls')).toBe(menu().id);
    expect(menu().hidden).toBe(true);
    click(trigger());
    expect(menu().hidden).toBe(false);
    expect(trigger().getAttribute('aria-expanded')).toBe('true');
    expect(items().map(item => item.textContent?.trim())).toEqual([
      `${value === 'hidden' ? '\u2713' : ''}Hidden`,
      `${value === 'collapsed' ? '\u2713' : ''}Collapsed`,
      `${value === 'shown' ? '\u2713' : ''}Expanded`,
    ]);
    const selected = items().filter(item => item.getAttribute('aria-checked') === 'true');
    expect(selected).toHaveLength(1);
    expect(document.activeElement).toBe(selected[0]);
  });

  it.each([
    [0, 'hidden'], [1, 'collapsed'], [2, 'shown'],
  ] as const)('selects option %i, closes and restores trigger focus', (index, value) => {
    render();
    click(trigger());
    click(items()[index]);
    expect(onChange).toHaveBeenCalledExactlyOnceWith(value);
    expect(menu().hidden).toBe(true);
    expect(document.activeElement).toBe(trigger());
    // The control remains authoritative to server settings, not optimistic local state.
    render(value);
    expect(trigger().textContent).toContain(['Hidden', 'Collapsed', 'Expanded'][index]);
    click(trigger());
    expect(items()[index].getAttribute('aria-checked')).toBe('true');
  });

  it('supports arrow navigation, wrapping, Home and End', () => {
    render();
    key(trigger(), 'ArrowDown');
    expect(document.activeElement).toBe(items()[0]);
    key(items()[0], 'ArrowUp');
    expect(document.activeElement).toBe(items()[2]);
    key(items()[2], 'ArrowDown');
    expect(document.activeElement).toBe(items()[0]);
    key(items()[0], 'End');
    expect(document.activeElement).toBe(items()[2]);
    key(items()[2], 'Home');
    expect(document.activeElement).toBe(items()[0]);
    key(items()[0], 'Escape');
    key(trigger(), 'ArrowUp');
    expect(document.activeElement).toBe(items()[2]);
    expect(onChange).not.toHaveBeenCalled();
  });

  it.each(['Escape', 'Tab'])('closes with %s without changing visibility', value => {
    render();
    click(trigger());
    key(items()[1], value);
    expect(menu().hidden).toBe(true);
    expect(document.activeElement).toBe(trigger());
    expect(onChange).not.toHaveBeenCalled();
  });

  it('closes on outside pointerdown without stealing focus', () => {
    render();
    click(trigger());
    const outside = document.createElement('button');
    document.body.append(outside);
    act(() => {
      outside.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
      outside.focus();
    });
    expect(menu().hidden).toBe(true);
    expect(document.activeElement).toBe(outside);
    expect(onChange).not.toHaveBeenCalled();
    outside.remove();
  });

  it('closes when focus leaves the control or the trigger is clicked again', () => {
    render();
    click(trigger());
    click(trigger());
    expect(menu().hidden).toBe(true);
    click(trigger());
    act(() => document.activeElement && (document.activeElement as HTMLElement).blur());
    expect(menu().hidden).toBe(true);
    expect(onChange).not.toHaveBeenCalled();
  });
});

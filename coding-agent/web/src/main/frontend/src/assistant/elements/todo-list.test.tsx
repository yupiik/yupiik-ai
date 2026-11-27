// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TodoList, type TodoItem } from './todo-list';

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});
const longText = 'example-task-with-an-unbroken-identifier-'.repeat(12);
const items: TodoItem[] = [
  { id: 'done', text: 'Finished task', status: 'done' },
  { id: 'pending', text: 'Next task', status: 'pending', description: longText },
  { id: 'active', text: longText, status: 'active', description: 'Current details' },
  { id: 'failed', text: 'Failed task', status: 'failed', reason: longText },
  { id: 'cancelled', text: 'Cancelled task', status: 'cancelled' },
];
function render(maxVisible?: number) {
  act(() => root.render(<TodoList items={items} title={longText} description={longText} revision={7} maxVisible={maxVisible} />));
}

describe('TodoList sidebar overflow and expansion', () => {
  it('keeps full task text, details, reasons and statuses available in the expanded list', () => {
    render();
    expect(host.querySelectorAll('li')).toHaveLength(5);
    expect(host.textContent).toContain('1/4 · rev 7');
    expect(host.querySelector('[data-status="active"]')?.textContent).toContain(longText);
    expect(host.querySelector('[data-status="pending"] p')?.textContent).toBe(longText);
    expect(host.querySelector('[data-status="failed"] p')?.textContent).toBe(longText);
    expect(host.querySelector('[data-status="cancelled"] .sr-only')?.textContent).toBe('cancelled');
    expect(host.querySelector('[data-slot="todo-list"]')?.classList.contains('min-w-0')).toBe(true);
    expect(host.querySelector('[data-slot="todo-list"] > div')?.classList.contains('flex-wrap')).toBe(true);
    for (const content of host.querySelectorAll('li > div')) {
      expect(content.classList.contains('[overflow-wrap:anywhere]')).toBe(true);
      expect(content.classList.contains('min-w-0')).toBe(true);
    }
  });

  it('keeps active and failed items visible and restores all history and details on expansion', () => {
    render(1);
    const button = host.querySelector('button')!;
    expect(button.textContent).toBe('Show 2 more');
    expect(button.getAttribute('aria-controls')).toBe(host.querySelector('ul')?.id);
    expect(host.querySelectorAll('li')).toHaveLength(3);
    act(() => button.click());
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelectorAll('li')).toHaveLength(5);
    expect(host.querySelector('[data-status="pending"] p')?.textContent).toBe(longText);
    act(() => button.click());
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(host.querySelector('[data-status="cancelled"]')).toBeNull();
    expect(host.querySelector('[data-status="failed"] p')?.textContent).toBe(longText);
  });
});

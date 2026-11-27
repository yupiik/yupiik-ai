// @vitest-environment happy-dom
import React, { act } from 'react';
import { readFileSync } from 'node:fs';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionMetadata } from './SessionMetadata';

const css = readFileSync('src/index.css', 'utf8');
let host: HTMLDivElement;
let root: Root;
let style: HTMLStyleElement;
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  style = document.createElement('style');
  style.textContent = css.slice(css.indexOf('.yca-session-metadata {'), css.indexOf('/* TUI-aligned Markdown;'));
  document.head.append(style);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  style.remove();
  vi.unstubAllGlobals();
});

function render() {
  act(() => root.render(<SessionMetadata sessionId="session-example" permissionMode="auto_edit" onSetProject={() => {}} onSetModel={() => {}} onSetProvider={() => {}} onSetPermissions={() => {}} />));
}

describe('sidebar metadata layout', () => {
  it('dispatches full_yolo from the picker and updates the warning on sandbox state changes', () => {
    const onSetPermissions = vi.fn();
    const renderMode = (permissionMode: string, sandboxEnabled: boolean) => {
      act(() => root.render(<SessionMetadata sessionId="s" permissionMode={permissionMode} sandboxEnabled={sandboxEnabled}
        onSetProject={() => {}} onSetModel={() => {}} onSetProvider={() => {}} onSetPermissions={onSetPermissions} />));
    };
    const sandboxValue = () => [...host.querySelectorAll('.yca-metadata-row')]
      .find((row) => row.querySelector('dt')?.textContent === 'Sandbox')?.querySelector('dd');
    renderMode('yolo', true);
    expect(sandboxValue()?.textContent).toBe('Enabled');
    const select = host.querySelector('select[title="Tool permission level for this session"]')! as HTMLSelectElement;
    expect(select.nextElementSibling?.getAttribute('style')).toContain('color: var(--yca-warning)');
    expect(select.nextElementSibling?.textContent).toBe('!');
    renderMode('yolo', false);
    expect(sandboxValue()?.textContent).toBe('Disabled');
    expect(select.nextElementSibling?.getAttribute('style')).toContain('color: var(--yca-yolo)');
    act(() => {
      select.value = 'full_yolo';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(onSetPermissions).toHaveBeenCalledWith('full_yolo');
    renderMode('full_yolo', true);
    expect(sandboxValue()?.textContent).toBe('Bypassed (full_yolo)');
    expect(select.value).toBe('full_yolo');
    expect(select.nextElementSibling?.getAttribute('style')).toContain('color: var(--yca-yolo)');
    expect(select.nextElementSibling?.textContent).toBe('!');
    expect(select.selectedOptions[0].textContent).toContain('not recommended');
    renderMode('ask', true);
    expect(sandboxValue()?.textContent).toBe('Enabled');
    expect(select.nextElementSibling?.textContent).toBe('');
    expect(select.nextElementSibling?.getAttribute('style')).toContain('background: var(--yca-plan)');
  });

  it('centers metadata labels and values and keeps the permission dot trailing', () => {
    render();
    expect(getComputedStyle(host.querySelector('.yca-metadata-row')!).alignItems).toBe('center');
    const select = host.querySelector('select[title="Tool permission level for this session"]')!;
    expect(select.nextElementSibling?.getAttribute('aria-hidden')).toBe('true');
    expect(select.parentElement?.lastElementChild).toBe(select.nextElementSibling);
    expect(getComputedStyle(host.querySelector('.yca-metadata-value')!).overflowWrap).toBe('anywhere');
    expect(getComputedStyle(select).minWidth).toBe('0');
  });

});

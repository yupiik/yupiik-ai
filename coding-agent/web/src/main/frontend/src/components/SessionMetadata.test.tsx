import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { SessionMetadata, type SessionMetadataProps } from './SessionMetadata';

const defaults: SessionMetadataProps = {
  sessionId: 'session-123',
  onSetProject: () => {},
  onSetModel: () => {},
  onSetProvider: () => {},
  onSetPermissions: () => {},
};

function render(props: Partial<SessionMetadataProps> = {}): string {
  return renderToStaticMarkup(<SessionMetadata {...defaults} {...props} />);
}

function rows(html: string): string[] {
  return [...html.matchAll(/<div class="yca-metadata-row">(.*?)<\/div>/g)].map((match) => match[1]);
}

function row(html: string, label: string): string {
  const result = rows(html).find((value) => value.includes(`>${label}</`));
  expect(result, `row for ${label}`).toBeDefined();
  return result!;
}

function options(html: string): string[] {
  return [...html.matchAll(/<option value="([^"]*)"/g)].map((match) => match[1]);
}

describe('SessionMetadata', () => {
  it('renders semantic definition rows in compact metadata order with full value titles', () => {
    const html = render({
      sessionId: 'session-with-a-long-identifier',
      project: '/a/long/project/directory',
      gitBranch: 'feature/a-long-branch',
      provider: 'configured-provider',
      model: 'provider/a-long-model-name',
      flavor: 'high',
    });
    expect(html).toMatch(/^<dl class="yca-session-metadata">.*<\/dl>$/);
    expect(rows(html)).toHaveLength(8);
    expect(rows(html).map((value) => value.match(/<dt>(?:<label[^>]*>)?([^<]+)/)?.[1]))
      .toEqual(['Session', 'Working dir', 'Branch', 'Provider', 'Model', 'Flavor', 'Permissions', 'Sandbox']);
    for (const value of rows(html)) {
      expect(value).toMatch(/^<dt>.*<\/dt><dd(?: [^>]*)?>.*<\/dd>$/);
    }
    for (const [label, value] of [
      ['Session', 'session-with-a-long-identifier'],
      ['Working dir', '/a/long/project/directory'],
      ['Branch', 'feature/a-long-branch'],
      ['Provider', 'configured-provider'],
      ['Model', 'provider/a-long-model-name'],
    ]) {
      expect(row(html, label)).toContain(`title="${value}"`);
    }
    for (const label of ['Session', 'Branch', 'Flavor']) {
      expect(row(html, label)).toContain('class="yca-metadata-value"');
    }
    expect(row(html, 'Working dir')).toContain('class="yca-metadata-edit"');
    expect(row(html, 'Working dir')).toContain('value="/a/long/project/directory"');
    expect(row(html, 'Working dir')).toContain('<button type="button">Set</button>');
    expect(row(html, 'Permissions')).toContain('class="yca-metadata-edit"');
  });

  it('associates each editable label with its input or select using unique IDs', () => {
    const html = renderToStaticMarkup(<><SessionMetadata {...defaults} /><SessionMetadata {...defaults} /></>);
    const ids: string[] = [];
    for (const value of rows(html)) {
      const label = value.match(/<label for="([^"]+)">(.*?)<\/label>/);
      if (label) {
        const [, id, name] = label;
        expect(['Working dir', 'Model', 'Provider', 'Permissions']).toContain(name);
        const tag = name === 'Working dir' ? 'input' : 'select';
        expect(value).toContain(`<${tag} id="${id}" class="yca-metadata-control"`);
        ids.push(id);
      }
    }
    expect(ids).toHaveLength(8);
    expect(new Set(ids).size).toBe(8);
  });

  it('keeps current provider and model first and filters them out of candidates', () => {
    const html = render({
      model: 'current-model',
      modelCandidates: ['other-model', 'current-model', 'last-model'],
      provider: 'current-provider',
      providerCandidates: ['current-provider', 'other-provider'],
    });
    expect(options(row(html, 'Model'))).toEqual(['current-model', 'other-model', 'last-model']);
    expect(options(row(html, 'Provider'))).toEqual(['current-provider', 'other-provider']);
    expect(row(html, 'Model')).toContain('<option value="current-model" selected="">');
    expect(row(html, 'Provider')).toContain('<option value="current-provider" selected="">');
  });

  it('includes current options even when they are not candidates', () => {
    const html = render({ model: 'custom-model', provider: 'custom-provider' });
    expect(options(row(html, 'Model'))).toEqual(['custom-model']);
    expect(options(row(html, 'Provider'))).toEqual(['custom-provider']);
  });

  it('renders candidates without inventing placeholder options when current values are absent', () => {
    const html = render({ modelCandidates: ['model-a'], providerCandidates: ['provider-a'] });
    expect(options(row(html, 'Model'))).toEqual(['model-a']);
    expect(options(row(html, 'Provider'))).toEqual(['provider-a']);
  });

  it.each([undefined, ''])('omits empty optional rows (%s) and defaults permissions to ask', (empty) => {
    const html = render({ gitBranch: empty, flavor: empty, permissionMode: empty });
    expect(rows(html)).toHaveLength(6);
    expect(html).not.toContain('<dt>Branch</dt>');
    expect(html).not.toContain('<dt>Flavor</dt>');
    expect(row(html, 'Working dir')).toContain('value=""');
    expect(options(row(html, 'Model'))).toEqual([]);
    expect(options(row(html, 'Provider'))).toEqual([]);
    expect(options(row(html, 'Permissions'))).toEqual(['ask', 'auto_edit', 'yolo', 'full_yolo']);
    expect(row(html, 'Permissions')).toContain('<option value="ask" selected="">');
    expect(row(html, 'Permissions')).toContain('background:var(--yca-muted)');
  });

  it.each([
    ['ask', true, 'Enabled', 'success'],
    ['auto_edit', false, 'Disabled', 'yolo'],
    ['yolo', true, 'Enabled', 'success'],
    ['yolo', false, 'Disabled', 'yolo'],
    ['ask', undefined, 'Unknown', 'muted'],
    ['full_yolo', true, 'Bypassed (full_yolo)', 'yolo'],
    ['full_yolo', false, 'Bypassed (full_yolo)', 'yolo'],
    ['full_yolo', undefined, 'Bypassed (full_yolo)', 'yolo'],
  ] as const)('shows effective sandbox state for %s with enabled=%s', (permissionMode, sandboxEnabled, status, color) => {
    const sandbox = row(render({ permissionMode, sandboxEnabled }), 'Sandbox');
    expect(sandbox).toContain(`>${status}</dd>`);
    expect(sandbox).toContain(`color:var(--yca-${color})`);
    expect(sandbox).not.toContain('<select');
  });

  it.each([
    ['ask', 'plan'], ['auto_edit', 'success'],
  ])('preserves the selected %s permission and its dot color', (permissionMode, color) => {
    const permissions = row(render({ permissionMode }), 'Permissions');
    expect(permissions).toContain(`<option value="${permissionMode}" selected="">`);
    expect(permissions).toContain(`background:var(--yca-${color})`);
    expect(permissions).toContain('aria-hidden="true"');
    expect(permissions).toMatch(/<dd class="yca-metadata-edit"><select[^>]*>.*<\/select><span aria-hidden="true"[^>]*><\/span><\/dd>$/);
  });

  it.each([
    ['yolo', true, 'warning'], ['yolo', false, 'yolo'], ['yolo', undefined, 'yolo'],
    ['full_yolo', true, 'yolo'], ['full_yolo', false, 'yolo'], ['full_yolo', undefined, 'yolo'],
  ] as const)('warns for %s with sandbox enabled=%s', (permissionMode, sandboxEnabled, color) => {
    const permissions = row(render({ permissionMode, sandboxEnabled }), 'Permissions');
    expect(permissions).toContain(`<option value="${permissionMode}" selected="">`);
    expect(permissions).toContain(`color:var(--yca-${color})`);
    expect(permissions).toMatch(/<span aria-hidden="true"[^>]*>!<\/span>/);
    expect(permissions).toContain('full_yolo (not recommended)');
    if (permissionMode === 'full_yolo') {
      expect(permissions).toContain('title="Sandbox bypassed - not recommended"');
    }
  });
});

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('../index.css', import.meta.url), 'utf8');
// These contracts inspect authored CSS, without requiring a browser or importing Tailwind.
const rules = [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
  .map(([, selectors, body]) => ({
    selectors: selectors.trim().split(/,\s*(?![^()]*\))/).map((selector) => selector.trim()),
    declarations: Object.fromEntries(body.trim().split(';').filter((entry) => entry.trim()).map((entry) => {
      const colon = entry.indexOf(':');
      return [entry.slice(0, colon).trim(), entry.slice(colon + 1).trim()];
    })),
  }));

function declarations(selector: string): Record<string, string> {
  const matches = rules.filter((rule) => rule.selectors.includes(selector));
  expect(matches.length, `Missing CSS selector: ${selector}`).toBeGreaterThan(0);
  return Object.assign({}, ...matches.map((rule) => rule.declarations));
}

const dark = declarations(':root');
const light = { ...dark, ...declarations(":root[data-theme='light']") };

// Palette.java: accent, primary, success, warning, error, info, text, textMuted,
// border, borderActive, backgroundPanel, yolo (in record order).
const paletteSlots = ['accent', 'primary', 'success', 'warning', 'error', 'info', 'text', 'muted',
  'border', 'border-active', 'panel', 'yolo'];
const codeSlots = ['string', 'comment', 'number', 'keyword', 'type', 'tag', 'attr', 'annotation'];

describe('Tool presentation theme and keyboard contracts', () => {
  it('uses semantic theme colors for requested changes and lifecycle states', () => {
    expect(declarations('.yca-tool-card')).toMatchObject({ background: 'var(--yca-panel)', 'min-width': '0' });
    expect(declarations('.yca-diff-add')).toMatchObject({ color: 'var(--yca-success)', background: 'var(--yca-success-soft)' });
    expect(declarations('.yca-diff-remove')).toMatchObject({ color: 'var(--yca-error)', background: 'var(--yca-error-soft)' });
    expect(declarations('.yca-tool-failed')['border-color']).toBe('var(--yca-error)');
    expect(declarations('.yca-tool-working')['border-color']).toBe('var(--yca-accent)');
    expect(declarations('.yca-tool-pending')['border-color']).toBe('var(--yca-warning)');
  });

  it('retains significant whitespace, bounded scrolling, and visible focus rings', () => {
    expect(declarations('.yca-tool-card pre')).toMatchObject({ 'white-space': 'pre', overflow: 'auto', 'max-height': '24rem' });
    expect(declarations('.yca-tool-card button:focus-visible')['outline']).toBe('2px solid var(--yca-accent)');
    expect(declarations('.yca-tool-card summary:focus-visible')['outline']).toBe('2px solid var(--yca-accent)');
    expect(declarations('.yca-tool-text [tabindex]:focus-visible')['outline']).toBe('2px solid var(--yca-accent)');
  });
});

describe('TUI theme defaults', () => {
  it.each([
    ['dark', dark, ['#02abea', '#d858ff', '#46f72f', '#fbbf24', '#f87171', '#7ad2f7', '#eef0f3',
      '#9aa4b2', '#667587', '#7ad2f7', '#12141a', '#ff5555']],
    ['light', light, ['#056ebb', '#a84fc9', '#2f7a43', '#8a621f', '#d1383d', '#1f7a88', '#24262b',
      '#626873', '#8995a3', '#056ebb', '#ffffff', '#ff5555']],
  ] as const)('preserves the exact %s Palette defaults', (_name, theme, values) => {
    expect(paletteSlots.map((slot) => theme[`--yca-${slot}`])).toEqual(values);
  });

  it.each([
    ['dark', dark, ['#98c379', '#7f848e', '#d19a66', '#c678dd', '#61afef', '#e06c75', '#56b6c2', '#c586c0']],
    ['light', light, ['#2f7d33', '#a0a1a7', '#986801', '#a626a4', '#4078f2', '#e45649', '#0184bc', '#008080']],
  ] as const)('preserves the exact %s CodeTheme defaults, including contrast exceptions', (_name, theme, values) => {
    expect(codeSlots.map((slot) => theme[`--yca-code-${slot}`])).toEqual(values);
    expect(theme['--yca-pre-bg']).toBe('var(--yca-panel)');
    expect(theme['--yca-pre-text']).toBe('var(--yca-text)');
  });

  it.each([dark, light])('uses semantic aliases and theme-relative washes', (theme) => {
    expect(theme['--yca-build']).toBe('var(--yca-success)');
    expect(theme['--yca-plan']).toBe('var(--yca-warning)');
    for (const slot of ['success', 'warning', 'error', 'info', 'primary', 'accent', 'border-active', 'yolo']) {
      expect(theme[`--yca-${slot}-soft`]).toBe(`color-mix(in srgb, var(--yca-${slot}) 15%, transparent)`);
    }
    expect(theme['--yca-heading-accent']).toBe('var(--yca-accent)');
    expect(theme['--yca-heading-h2']).toBe('var(--yca-success)');
    expect(theme['--yca-heading-h3']).toBe('var(--yca-primary)');
    expect(theme['--yca-heading']).toBe('var(--yca-text)');
  });

  it('retains native theme schemes and web page surfaces', () => {
    expect(dark['color-scheme']).toBe('dark');
    expect(light['color-scheme']).toBe('light');
    expect(dark['--yca-bg']).toBe('#0f1115');
    expect(light['--yca-bg']).toBe('#f7f8fa');
    expect(declarations('body')['background']).toBe('var(--yca-bg)');
  });
});

describe('Transcript speaker and sidebar layout', () => {
  it('uses neutral turn surfaces with only a speaker-colored rail', () => {
    expect(declarations('.yca-turn')).toMatchObject({
      background: 'transparent', border: '0', 'border-left': '3px solid var(--yca-turn-tone)',
      'border-radius': '0', 'min-width': '0',
    });
    expect(declarations('.yca-turn-user')['--yca-turn-tone']).toBe('var(--yca-primary)');
    expect(declarations('.yca-turn-assistant')['--yca-turn-tone']).toBe('var(--yca-accent)');
    expect(css).not.toContain('--yca-user-bg');
  });

  it('keeps metadata labels beside values and allows controls to shrink', () => {
    expect(declarations('.yca-metadata-row')).toMatchObject({
      display: 'grid', 'grid-template-columns': '4.5rem minmax(0, 1fr)', 'min-width': '0', 'align-items': 'center',
    });
    expect(declarations('.yca-metadata-row > dd')).toMatchObject({ margin: '0', 'min-width': '0' });
    expect(declarations('.yca-metadata-value')['overflow-wrap']).toBe('anywhere');
    expect(declarations('.yca-metadata-control')).toMatchObject({ width: '100%', 'min-width': '0' });
    expect(declarations('.yca-metadata-edit > .yca-metadata-control')['flex']).toBe('1 1 0%');
    expect(declarations('.yca-metadata-edit > button')['flex-shrink']).toBe('0');
  });
});

describe('Markdown semantic CSS contracts', () => {
  it.each([
    ['user', 'var(--yca-primary)'], ['document', 'var(--yca-primary)'],
    ['assistant', 'var(--yca-accent)'], ['thought', 'currentColor'],
  ])('scopes %s strong tone on its Markdown root', (variant, tone) => {
    expect(declarations(`.aui-md[data-variant='${variant}']`)['--yca-strong-tone']).toBe(tone);
    expect(declarations('.aui-md strong')).toMatchObject({ color: 'var(--yca-strong-tone)', 'font-weight': '700' });
  });

  it('renders inline code as bold string-colored text without a chip', () => {
    expect(declarations('.aui-md')['--yca-inline-tone']).toBe('var(--yca-code-string)');
    expect(declarations('.aui-md :not(pre) > code')).toMatchObject({
      color: 'var(--yca-inline-tone)', 'font-weight': '700', background: 'transparent',
      border: '0', padding: '0', 'border-radius': '0',
    });
    expect(declarations(".aui-md[data-variant='thought']")).toMatchObject({
      color: 'var(--yca-muted)', '--yca-inline-tone': 'currentColor', '--yca-strong-tone': 'currentColor',
    });
  });

  it('preserves the heading hierarchy and lets nested strong inherit its heading tone', () => {
    for (const [level, token] of [[1, 'heading-accent'], [2, 'heading-h2'], [3, 'heading-h3'],
      [4, 'heading'], [5, 'heading'], [6, 'heading']] as const) {
      expect(declarations(`.aui-md h${level}`)).toMatchObject({
        color: `var(--yca-${token})`, '--yca-strong-tone': 'currentColor',
      });
    }
    expect(declarations(".aui-md[data-variant='thought'] :is(h1, h2, h3, h4, h5, h6, blockquote)")['color'])
      .toBe('inherit');
  });

  it('resets each quote rail independently and keeps normal body text without a wash', () => {
    expect(declarations('.aui-md blockquote')).toMatchObject({
      '--yca-quote-tone': 'var(--yca-muted)', 'border-left': '3px solid var(--yca-quote-tone)',
      color: 'var(--yca-text)', background: 'transparent',
    });
    // Direct-child title scope prevents an outer callout from recoloring a nested quote's title.
    expect(declarations('.aui-md blockquote > p.yca-callout-title')).toMatchObject({
      color: 'var(--yca-quote-tone)', 'font-weight': '700',
      '--yca-strong-tone': 'currentColor', '--yca-inline-tone': 'currentColor',
    });
  });

  it.each([['note', 'info'], ['tip', 'success'], ['important', 'primary'], ['warning', 'warning'], ['caution', 'error']])
    ('maps lowercase %s callouts to %s', (callout, slot) => {
      expect(declarations(`.aui-md blockquote[data-callout='${callout}']`)['--yca-quote-tone'])
        .toBe(`var(--yca-${slot})`);
    });

  it('keeps tables horizontally scrollable', () => {
    expect(declarations('.aui-md table')).toMatchObject({
      display: 'block', 'max-width': '100%', 'overflow-x': 'auto', 'border-collapse': 'collapse',
    });
  });

  it('uses muted list markers without muting item text', () => {
    expect(declarations('.aui-md li::marker')['color']).toBe('var(--yca-muted)');
    expect(declarations('.aui-md li')['color']).toBeUndefined();
  });
});

describe('Shared CodeBlock CSS contracts', () => {
  it('styles the full pre and resets its code child, inside and outside Markdown', () => {
    for (const selector of ['.aui-md pre', 'pre.yca-code-block']) {
      expect(declarations(selector)).toMatchObject({
        width: '100%', 'min-width': '0', 'box-sizing': 'border-box', 'overflow-x': 'auto',
        background: 'var(--yca-pre-bg)', color: 'var(--yca-pre-text)',
      });
    }
    for (const selector of ['.aui-md pre code', 'pre.yca-code-block code']) {
      expect(declarations(selector)).toMatchObject({
        display: 'block', background: 'transparent', color: 'inherit', border: '0', padding: '0',
        'font-weight': '400', 'white-space': 'pre', 'font-family': 'var(--yca-font-mono)',
      });
    }
    expect(declarations('.yca-tool-code')).toMatchObject({ 'max-height': '18rem', overflow: 'auto' });
    expect(declarations('pre.yca-tool-code.whitespace-pre-wrap code')).toMatchObject({
      'white-space': 'pre-wrap', 'overflow-wrap': 'anywhere',
    });
  });

  it.each([
    ['comment', 'comment'], ['prolog', 'comment'], ['doctype', 'comment'], ['cdata', 'comment'],
    ['string', 'string'], ['char', 'string'], ['attr-value', 'string'], ['regex', 'string'],
    ['number', 'number'], ['boolean', 'number'], ['constant', 'number'],
    ['keyword', 'keyword'], ['atrule', 'keyword'], ['class-name', 'type'], ['builtin', 'type'], ['function', 'type'],
    ['tag', 'tag'], ['deleted', 'tag'], ['attr-name', 'attr'], ['property', 'attr'], ['symbol', 'attr'],
    ['annotation', 'annotation'], ['decorator', 'annotation'], ['inserted', 'string'],
  ])('maps Prism %s to the TUI %s token', (token, slot) => {
    expect(declarations(`.yca-code-block .token.${token}`)['color']).toBe(`var(--yca-code-${slot})`);
  });
});

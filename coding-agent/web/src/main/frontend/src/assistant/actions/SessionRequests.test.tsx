// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionRequests } from './SessionRequests';
import type { InteractiveRequest } from './useInteractiveRequests';

let host: HTMLDivElement;
let root: Root;
let onAnswer: ReturnType<typeof vi.fn<(id: number, value: unknown) => void | Promise<void>>>;
const request = (params: Record<string, unknown>, method = 'session.ask', id = 1): InteractiveRequest => ({ id, method, params });

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  onAnswer = vi.fn().mockResolvedValue(undefined);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});

function render(value: InteractiveRequest | null) {
  act(() => root.render(<SessionRequests request={value} onAnswer={onAnswer} />));
}
function button(label: string): HTMLButtonElement {
  const result = Array.from(host.querySelectorAll('button')).find((element) => element.textContent?.trim() === label);
  expect(result, `button ${label}`).toBeDefined();
  return result!;
}
async function click(label: string) {
  await act(async () => { button(label).click(); });
}
function input(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  act(() => {
    const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

function ask(multiple = false) {
  return request({ questions: [{ id: 'target', prompt: 'Which target?', options: ['Alpha', 'Beta'], multiple }] });
}

describe('SessionRequests mounted interactions', () => {
  it('renders nothing without a request', () => {
    render(null);
    expect(host.innerHTML).toBe('');
  });

  it.each([
    ['Allow once', 'allow_once'], ['Allow session', 'allow_session'], ['Deny', 'deny'],
  ])('maps upstream approval %s to the exact backend token', async (label, token) => {
    render(request({ title: 'Run a tool?', tool: 'bash', detail: 'Side effects', args: { command: 'pwd' } }, 'session.approval'));
    expect(host.querySelector('[data-slot="approval-card"]')).not.toBeNull();
    expect(host.textContent).toContain('"command": "pwd"');
    expect(host.textContent).not.toContain('Always allow');
    await click(label);
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, token);
  });

  it.each([['SANDBOX', 'Sandbox approval'], ['PERMISSION', 'Tool approval']])('labels %s approval origin', (origin, label) => {
    render(request({ origin, title: 'Access resource', tool: 'sandbox' }, 'session.approval'));
    expect(host.querySelector('h2')?.textContent).toBe(label);
  });

  it('submits a single ask choice as an ID-keyed map and permits changing it', async () => {
    render(ask());
    expect(host.querySelector('[data-slot="option-list"]')).not.toBeNull();
    await click('Alpha');
    expect(onAnswer).not.toHaveBeenCalled();
    await click('Beta');
    expect(button('Alpha').getAttribute('aria-pressed')).toBe('false');
    expect(button('Beta').getAttribute('aria-pressed')).toBe('true');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, { target: 'Beta' });
  });

  it('submits a single ask multiple choice as an ID-keyed map with ordered options and trimmed own text last', async () => {
    render(ask(true));
    await click('Beta');
    await click('Alpha');
    await click('Type your own answer...');
    input(host.querySelector('textarea')!, '  Gamma  ');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, { target: ['Alpha', 'Beta', 'Gamma'] });
  });

  it('toggles multi selections off and ignores unselected own text', async () => {
    render(ask(true));
    await click('Alpha');
    await click('Alpha');
    input(host.querySelector('textarea')!, 'not selected');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, { target: [] });
  });

  it('submits trimmed own answer in place of a single option', async () => {
    render(ask());
    await click('Alpha');
    await click('Type your own answer...');
    input(host.querySelector('textarea')!, '  other answer  ');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, { target: 'other answer' });
  });

  it('maps an empty single ask answer to its original question ID', async () => {
    render(ask());
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, { target: '' });
  });

  it.each(['session.ask', 'session.question'])('uses an ID-keyed object for multiple %s questions without key-prefix collisions', async (method) => {
    render(request({ questions: [
      { id: 'a', prompt: 'First', options: ['A', 'B'], multiple: false },
      { id: 'a:b', prompt: 'Second', options: ['C', 'D'], multiple: true },
      { id: '__proto__', prompt: 'Third', options: [], multiple: false },
    ] }, method));
    await click('B');
    await click('D');
    input(host.querySelectorAll('textarea')[2], ' text ');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, JSON.parse('{"a":"B","a:b":["D"],"__proto__":"text"}'));
  });

  it.each([
    ['session.ask', { input: 'Ada' }],
    ['session.question', 'Ada'],
  ] as const)('uses ElicitationForm for optionless %s with the method-specific payload', async (method, expected) => {
    render(request({ questions: [{ id: 'input', prompt: 'Your name?', options: [], multiple: false }] }, method));
    expect(host.querySelector('[data-slot="elicitation-form"]')).not.toBeNull();
    input(host.querySelector('input')!, '  Ada  ');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, expected);
  });

  it.each([false, true])('keeps a single session.question choice scalar/array (multiple=%s)', async (multiple) => {
    render({ ...ask(multiple), method: 'session.question' });
    await click('Beta');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, multiple ? ['Beta'] : 'Beta');
  });

  it.each(['session.ask', 'session.question'])('picker sends exact label for %s, including empty labels', async (method) => {
    render(request({ prompt: 'Model', options: ['', 'model/B'] }, method));
    expect(button('Select').disabled).toBe(true);
    await click('model/B');
    await click('Select');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, 'model/B');
    onAnswer.mockClear();
    render(request({ prompt: 'Model', options: [''] }, method, 2));
    const empty = host.querySelector('[data-slot="option-list"] button') as HTMLButtonElement;
    await act(async () => { empty.click(); });
    expect(button('Select').disabled).toBe(false);
    await click('Select');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(2, '');
  });

  it.each(['session.ask', 'session.question'])('prompt-only confirmation sends booleans for %s', async (method) => {
    render(request({ prompt: 'Continue?' }, method));
    await click('Yes');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, true);
    onAnswer.mockClear();
    render(request({ prompt: 'Continue?' }, method, 2));
    await click('No');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(2, false);
  });

  it.each([
    { questions: [{ id: 'x', prompt: 'Choice?', options: ['A'] }] },
    { questions: [{ id: 'input', prompt: 'Input?', options: [] }] },
    { options: ['A'], prompt: 'Pick' },
    { prompt: 'Confirm?' },
  ])('cancel sends null for every question shape %#', async (params) => {
    render(request(params));
    await click('Cancel');
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, null);
  });

  it('retains the draft, announces asynchronous errors, unlocks and retries', async () => {
    let reject!: (reason: Error) => void;
    onAnswer.mockReturnValueOnce(new Promise<void>((_, failure) => { reject = failure; }));
    render(ask());
    await click('Beta');
    await click('Submit');
    expect(host.querySelector('dialog, [role="dialog"]')?.getAttribute('aria-busy')).toBe('true');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledTimes(1);
    await act(async () => { reject(new Error('Network unavailable')); });
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Network unavailable');
    expect(button('Beta').getAttribute('aria-pressed')).toBe('true');
    await click('Submit');
    expect(onAnswer).toHaveBeenCalledTimes(2);
    expect(onAnswer).toHaveBeenLastCalledWith(1, { target: 'Beta' });
  });

  it('catches synchronous callback errors too', async () => {
    onAnswer.mockImplementation(() => { throw new Error('Synchronous failure'); });
    render(ask());
    await click('Submit');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Synchronous failure');
  });

  it('resets drafts for a new request or session even when request IDs are reused', async () => {
    render({ ...ask(), sessionId: 'one' });
    await click('Alpha');
    render({ ...ask(), sessionId: 'two' });
    expect(button('Alpha').getAttribute('aria-pressed')).toBe('false');
    render(ask(false));
    input(host.querySelector('textarea')!, 'old');
    render({ ...ask(), id: 2 });
    expect(host.querySelector('textarea')!.value).toBe('');
  });

  it('does not apply a stale submission error to the next request', async () => {
    let reject!: (error: Error) => void;
    onAnswer.mockReturnValueOnce(new Promise<void>((_, failure) => { reject = failure; }));
    render(ask());
    await click('Submit');
    render({ ...ask(), id: 2 });
    await act(async () => { reject(new Error('old error')); });
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector('[role="dialog"]')?.getAttribute('aria-busy')).toBe('false');
  });

  it('labels the modal, traps keyboard focus, denies on Escape and restores focus', async () => {
    const previous = document.createElement('button');
    document.body.append(previous);
    previous.focus();
    render(request({ title: 'Run?' }, 'session.approval'));
    const dialog = host.querySelector('[role="dialog"]')!;
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(document.getElementById(dialog.getAttribute('aria-labelledby')!)?.textContent).toBe('Tool approval');
    expect(document.activeElement).toBe(button('Deny'));
    act(() => button('Deny').dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true })));
    expect(document.activeElement).toBe(button('Allow once'));
    act(() => button('Allow once').dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })));
    expect(document.activeElement).toBe(button('Deny'));
    await act(async () => { dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); });
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, 'deny');
    render(null);
    expect(document.activeElement).toBe(previous);
    previous.remove();
  });

  it('Escape cancels questions without sending an empty answer and backdrop does not dismiss', async () => {
    render(ask());
    await act(async () => { (host.firstElementChild as HTMLElement).click(); });
    expect(onAnswer).not.toHaveBeenCalled();
    await act(async () => { host.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); });
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(1, null);
  });
});

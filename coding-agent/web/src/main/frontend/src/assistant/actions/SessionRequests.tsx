import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { ApprovalCard } from './elements/approval-card';
import { ElicitationForm } from './elements/elicitation-form';
import { OptionList } from './elements/option-list';
import { ghostButton, inkButton } from './elements/surfaces';
import type { AnswerRequest, InteractiveRequest, RequestState } from './useInteractiveRequests';
import './actions.css';

type Question = { id: string; prompt: string; options: string[]; multiple: boolean };
type Draft = { selected: string[]; text: string };
const OWN = 'own';

function text(value: unknown, fallback = '') {
  return typeof value === 'string' ? value : fallback;
}

function options(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function questions(params: Record<string, unknown>): Question[] {
  if (!Array.isArray(params.questions)) {
    return [];
  }
  return params.questions.filter((q) => q && typeof q === 'object').map((q) => ({
    id: text(q.id), prompt: text(q.prompt, 'Question'), options: options(q.options), multiple: q.multiple === true,
  }));
}

export interface SessionRequestsProps {
  request: RequestState;
  onAnswer: AnswerRequest;
  /** Optional queue length from useInteractiveRequests. */
  pendingCount?: number;
}

/** Drop-in replacement for RequestModal; no assistant runtime provider required. */
export function SessionRequests({ request, ...props }: SessionRequestsProps) {
  if (!request) {
    return null;
  }
  return <RequestDialog key={`${request.sessionId ?? ''}:${request.id}`} request={request} {...props} />;
}

function RequestDialog({ request, onAnswer, pendingCount = 1 }: Omit<SessionRequestsProps, 'request'> & { request: InteractiveRequest }) {
  const titleId = useId();
  const dialog = useRef<HTMLDivElement>(null);
  const lock = useRef(false);
  const alive = useRef(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [picker, setPicker] = useState<string[]>([]);
  const params = request.params;
  const approval = request.method === 'session.approval';
  const qs = questions(params);
  const isPicker = !approval && qs.length === 0 && Array.isArray(params.options);
  const confirmation = !approval && !isPicker && qs.length === 0;
  const freeText = qs.length === 1 && qs[0].options.length === 0 && !qs[0].multiple;

  useEffect(() => {
    alive.current = true;
    const previous = document.activeElement;
    const node = dialog.current;
    (node?.querySelector<HTMLElement>('button:not(:disabled), input, textarea') ?? node)?.focus();
    return () => {
      alive.current = false;
      if (previous instanceof HTMLElement && previous.isConnected && (!document.activeElement || document.activeElement === document.body || node?.contains(document.activeElement))) {
        previous.focus();
      }
    };
  }, []);

  const answer = async (value: unknown) => {
    if (lock.current) {
      return;
    }
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      await onAnswer(request.id, value);
      if (alive.current) {
        setSubmitted(true);
      }
    } catch (failure) {
      lock.current = false;
      if (alive.current) {
        setError(failure instanceof Error ? failure.message : String(failure));
        setBusy(false);
      }
    }
  };
  const cancel = () => { void answer(approval ? 'deny' : null); };
  const draft = (index: number): Draft => drafts[index] ?? { selected: [], text: '' };
  const change = (index: number, update: Partial<Draft>) => {
    setDrafts((current) => ({ ...current, [index]: { ...draft(index), ...update } }));
  };
  const submit = () => {
    const entries = qs.map((q, index) => {
      const value = draft(index);
      const own = value.text.trim();
      const picked = q.options.filter((_, optionIndex) => value.selected.includes(String(optionIndex)));
      if (q.multiple) {
        if (value.selected.includes(OWN) && own) {
          picked.push(own);
        }
        return [q.id, picked] as const;
      }
      return [q.id, value.selected.includes(OWN) && own ? own : q.options.length === 0 ? own : picked[0] ?? ''] as const;
    });
    // feedbackGate (session.ask) always expects original question IDs, even for
    // one question. session.question keeps readLine's scalar/array contract.
    void answer(request.method === 'session.question' && entries.length === 1 ? entries[0][1] : Object.fromEntries(entries));
  };
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      cancel();
    }
    if (event.key === 'Tab') {
      const focusable = Array.from(dialog.current?.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), [tabindex="0"]',
      ) ?? []);
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!first) {
        event.preventDefault();
        dialog.current?.focus();
      } else if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  };

  return (
    <div className="yca-session-requests fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={(event) => event.stopPropagation()}>
      <div ref={dialog} role="dialog" aria-modal="true" aria-labelledby={titleId} aria-busy={busy} tabIndex={-1}
        onKeyDown={onKeyDown} className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-2xl border p-4 shadow-xl"
        style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel-2)', color: 'var(--yca-text)' }}>
        <h2 id={titleId} className="mb-3 text-sm font-semibold">{approval ? (params.origin === 'SANDBOX' ? 'Sandbox approval' : 'Tool approval') : isPicker ? text(params.prompt, 'Select an option') : confirmation ? text(params.prompt, 'Confirm') : 'Questions'}</h2>
        {pendingCount > 1 && <p className="mb-2 text-xs" role="status">{pendingCount} requests waiting</p>}
        <fieldset disabled={busy} className="min-w-0 border-0 p-0">
          {approval ? (
            <ApprovalCard state="request" title={text(params.title, 'Approve this tool call?')} subtitle={text(params.tool)}
              description={text(params.detail)}
              details={params.args && typeof params.args === 'object' ? [{ label: 'Arguments', value: JSON.stringify(params.args, null, 2) }] : undefined}
              alwaysAllowLabel="Allow session" onAllowOnce={() => { void answer('allow_once'); }}
              onAlwaysAllow={() => { void answer('allow_session'); }} onDeny={cancel}
              className="max-w-none whitespace-pre-wrap break-words" />
          ) : isPicker ? (
            <>
              <OptionList aria-label={text(params.prompt, 'Select an option')} options={options(params.options).map((label, index) => ({ id: String(index), label }))}
                value={picker} onValueChange={setPicker} disabled={busy} className="max-w-none" />
              <div className="mt-3 flex justify-end gap-2">
                <button type="button" className={`${ghostButton} px-3 py-2 text-xs`} onClick={cancel}>Cancel</button>
                <button type="button" className={`${inkButton} px-3 py-2 text-xs`} disabled={picker.length === 0} onClick={() => { void answer(options(params.options)[Number(picker[0])]); }}>Select</button>
              </div>
            </>
          ) : confirmation ? (
            <div className="flex justify-end gap-2">
              <button type="button" className={`${ghostButton} px-3 py-2 text-xs`} onClick={cancel}>Cancel</button>
              <button type="button" className={`${ghostButton} px-3 py-2 text-xs`} onClick={() => { void answer(false); }}>No</button>
              <button type="button" className={`${inkButton} px-3 py-2 text-xs`} onClick={() => { void answer(true); }}>Yes</button>
            </div>
          ) : freeText ? (
            <ElicitationForm server="Assistant" message={qs[0].prompt} state="request" acceptLabel="Submit" declineLabel="Cancel"
              fields={[{ name: 'answer', label: qs[0].prompt, value: draft(0).text, kind: 'text' }]}
              onFieldChange={(_, value) => change(0, { text: value })} onAccept={submit} onDecline={cancel} className="max-w-none" />
          ) : (
            <>
              {qs.map((q, index) => (
                <fieldset key={index} className="mb-4 min-w-0">
                  <legend className="mb-2 text-sm font-medium">{q.prompt}</legend>
                  <OptionList aria-label={q.prompt} options={[
                    ...q.options.map((label, optionIndex) => ({ id: String(optionIndex), label })),
                    { id: OWN, label: 'Type your own answer...' },
                  ]} selectionMode={q.multiple ? 'multiple' : 'single'} value={draft(index).selected}
                    onValueChange={(selected) => change(index, { selected })} disabled={busy} className="max-w-none" />
                  <label className="mt-2 block text-xs">
                    Your answer for {q.prompt}
                    <textarea rows={2} value={draft(index).text} onChange={(event) => change(index, { text: event.target.value })}
                      className="mt-1 w-full rounded border border-[var(--yca-border)] bg-[var(--yca-panel)] p-2 text-sm" />
                  </label>
                </fieldset>
              ))}
              <div className="flex justify-end gap-2">
                <button type="button" className={`${ghostButton} px-3 py-2 text-xs`} onClick={cancel}>Cancel</button>
                <button type="button" className={`${inkButton} px-3 py-2 text-xs`} onClick={submit}>Submit</button>
              </div>
            </>
          )}
        </fieldset>
        {busy && <p role="status" className="mt-3 text-xs">{submitted ? 'Answer sent.' : 'Sending answer...'}</p>}
        {error && <p role="alert" className="mt-3 whitespace-pre-wrap text-sm text-[var(--yca-error)]">Could not send answer: {error} You can retry.</p>}
      </div>
    </div>
  );
}

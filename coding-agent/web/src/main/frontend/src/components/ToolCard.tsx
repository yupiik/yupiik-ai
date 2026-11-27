import { memo, useId, useMemo, useState } from 'react';
import { ToolCall, ToolDisclosure } from '../assistant/tools/ToolCall';
import { DiffViewer, type DiffMode } from '../assistant/tools/DiffViewer';
import { canRenderDiff, fileChangePatch, type FileChange } from '../lib/fileChanges';
import type { Block } from '../api/eventLog';
import { isToolVisuallyFailed } from '../lib/toolPresentation';
import { guessPathLanguage } from '../lib/toolOutput';
import {
  BUILTIN_FIELDS, displayValue, isBuiltin, isValidEditRequest, parsePatch, parseToolArgs, presentToolOutput, summarizeToolArgs, TEXT_PAGE_SIZE, textPage,
  type BuiltinName,
} from '../lib/toolDetails';
import { CodeBlock } from './CodeBlock';
import { SkillResult } from './SkillResult';

type ToolBlock = Extract<Block, { kind: 'tool' }>;
const RESULT_LABELS: Record<BuiltinName, string> = {
  bash: 'Command output', read: 'File or directory output', glob: 'Matching paths', grep: 'Search output',
  edit_file: 'Replacement report', write_file: 'Write report', apply_patch: 'Patch report',
  webfetch: 'Fetched content', websearch: 'Search results', write_todo: 'Todo report', ask_user: 'Answers',
  skill: 'Skill content', spawn_subagent: 'Spawn report', check_subagents: 'Subagent report',
  list_subagent_options: 'Available options', compress: 'Context report', memory_write: 'Memory report',
  maven_knowledge_content: 'Knowledge content', maven_repository_knowledge: 'Index report',
  code_resolve: 'Symbol relations or artifacts', code_trace_calls: 'Call graph', code_find_code: 'Matching symbols',
  code_check_graph: 'Graph diagnostics', read_symbol: 'Symbol source',
};

/** A bounded window, not a permanent truncation. Even the final character remains reachable. */
export function ProgressiveText({ text, label, language, diff = false, diffMode = 'simple', path, oldSource, unnumbered = false }: {
  text: string; label: string; language?: string; diff?: boolean; diffMode?: DiffMode; path?: string; oldSource?: string; unnumbered?: boolean;
}) {
  const [page, setPage] = useState(0);
  const id = useId();
  const fullDiff = diff && diffMode !== 'simple' && canRenderDiff(text);
  const window = fullDiff ? { text, start: 0, end: text.length, more: false } : textPage(text, page);
  const startsAtLine = window.start === 0 || text[window.start - 1] === '\n';
  return <div className="yca-tool-text">
    {text.length === 0 ? <p className="yca-tool-muted">Empty string (0 characters)</p> : (
      diff ? <DiffViewer id={id} tabIndex={0} role="region" className="yca-code-block yca-tool-diff" aria-label={label} text={window.text} startsAtLine={startsAtLine} mode={fullDiff ? diffMode : 'simple'} path={path} oldSource={oldSource} unnumbered={unnumbered} /> : <div id={id} tabIndex={0} role="region" aria-label={label}><CodeBlock code={window.text} language={language} className="yca-tool-code" /></div>
    )}
    {!fullDiff && text.length > TEXT_PAGE_SIZE && <div className="yca-tool-paging">
      <button type="button" disabled={window.start === 0} aria-label={`Show previous ${label}`} aria-controls={id} onClick={() => setPage(Math.max(0, Math.floor(window.start / TEXT_PAGE_SIZE) - 1))}>Show previous</button>
      <span aria-live="polite">Characters {window.start + 1}-{window.end} of {text.length}</span>
      <button type="button" disabled={!window.more} aria-label={`Show more ${label}`} aria-controls={id} onClick={() => setPage(Math.floor(window.start / TEXT_PAGE_SIZE) + 1)}>Show more</button>
    </div>}
  </div>;
}

function AppliedFile({ file, mode, diffOnly }: { file: FileChange; mode: DiffMode; diffOnly: boolean }) {
  const patch = useMemo(() => fileChangePatch(file), [file]);
  return <section className="yca-tool-patch-file">
    <h5>{file.kind}: {file.path}</h5>
    {patch ? <ProgressiveText text={patch} label={`Applied diff for ${file.path}`} diff diffMode={mode} path={file.path} oldSource={file.oldText ?? ''} />
      : diffOnly ? <p className="yca-tool-muted">Diff preview unavailable. Expand tool details to inspect captured text.</p>
        : file.newText !== undefined ? <>
        <p className="yca-tool-muted">Diff preview unavailable or too large; captured text follows.</p>
        {file.oldText !== null && <ExactText label="Captured before" text={file.oldText!} />}
        <ExactText label="Captured after" text={file.newText} />
      </> : <p className="yca-tool-muted">Applied change; before/after text was not captured or is unavailable for display.</p>}
  </section>;
}

function AppliedChanges({ files, mode, diffOnly = false }: { files: FileChange[]; mode: DiffMode; diffOnly?: boolean }) {
  const [page, setPage] = useState(0);
  const start = Math.min(page * 5, Math.max(0, Math.ceil(files.length / 5) - 1) * 5);
  return <section aria-label="Applied file changes">
    <h4>Applied file changes</h4>
    <p className="yca-tool-muted">Captured during execution, not inferred from the request. Partial changes can remain even if the tool failed.</p>
    {files.slice(start, start + 5).map((file, index) => <AppliedFile key={`${start + index}:${file.path}`} file={file} mode={mode} diffOnly={diffOnly} />)}
    {files.length > 5 && <div className="yca-tool-paging">
      <button type="button" disabled={start === 0} onClick={() => setPage(Math.max(0, page - 1))}>Previous changed files</button>
      <span aria-live="polite">Files {start + 1}-{Math.min(start + 5, files.length)} of {files.length}</span>
      <button type="button" disabled={start + 5 >= files.length} onClick={() => setPage(page + 1)}>More changed files</button>
    </div>}
  </section>;
}

function ExactText({ label, text, language }: { label: string; text: string; language?: string }) {
  return <section className="yca-tool-exact">
    <h5>{label} <span className="yca-tool-muted">({text.length} characters)</span></h5>
    <ProgressiveText text={text} label={label} language={language} />
  </section>;
}

function PatchRequest({ patch, diffMode }: { patch: string; diffMode: DiffMode }) {
  const preview = useMemo(() => parsePatch(patch), [patch]);
  const [page, setPage] = useState(0);
  const start = Math.min(page * 5, Math.max(0, Math.ceil(preview.files.length / 5) - 1) * 5);
  return <>
    <p className="yca-tool-muted">Requested patch, not an applied diff. Counts describe supplied +/- lines; deleted file contents may not be supplied.</p>
    {preview.warning && <p className="yca-tool-warning">{preview.warning}</p>}
    {preview.files.slice(start, start + 5).map((file, index) => <section className="yca-tool-patch-file" key={start + index}>
      <h5>{file.operation}: {file.path}{file.destination !== undefined ? ` -> ${file.destination}` : ''} <span className="yca-diff-add">+{file.added}</span> <span className="yca-diff-remove">-{file.removed}</span></h5>
      <ProgressiveText text={diffMode !== 'simple' && !file.unnumbered && /^@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@/.test(file.diff)
        ? `diff --git a/file b/file\n--- ${file.operation === 'add' ? '/dev/null' : 'a/file'}\n+++ ${file.operation === 'delete' ? '/dev/null' : 'b/file'}\n${file.diff}` : file.diff}
        label={`Requested patch for ${file.path}`} diff diffMode={diffMode} path={file.path} unnumbered={file.unnumbered} />
    </section>)}
    {preview.files.length > 5 && <div className="yca-tool-paging">
      <button type="button" disabled={start === 0} onClick={() => setPage(Math.max(0, Math.floor(start / 5) - 1))}>Show previous files</button>
      <span aria-live="polite">Files {start + 1}-{Math.min(start + 5, preview.files.length)} of {preview.files.length}</span>
      <button type="button" disabled={start + 5 >= preview.files.length} onClick={() => setPage(Math.floor(start / 5) + 1)}>Show more files</button>
    </div>}
  </>;
}

function RequestDetails({ name, raw, diffMode }: { name: string; raw?: string; diffMode: DiffMode }) {
  const args = useMemo(() => parseToolArgs(raw), [raw]);
  if (!args) {
    return <p className="yca-tool-muted">{raw === undefined ? 'Arguments unavailable in this event.' : 'Arguments are malformed, non-object, or too large for a structured preview. Use raw arguments.'}</p>;
  }
  if (!isBuiltin(name)) {
    // Never guess external tool semantics from shared field names such as "content" or "patch".
    return <ProgressiveText label="External request JSON" text={displayValue(args)} language="json" />;
  }
  const fields: readonly string[] = BUILTIN_FIELDS[name].filter((key) => Object.prototype.hasOwnProperty.call(args, key));
  const validEdit = name === 'edit_file' && isValidEditRequest(args);
  const fileLanguage = typeof args.path === 'string' ? guessPathLanguage(args.path) ?? undefined : undefined;
  return <>
    {validEdit && <p className="yca-tool-muted">Requested exact replacement: {args.replaceAll === true ? 'all occurrences' : 'one unique match'}. Whitespace is significant; no file context or applied line numbers are inferred.</p>}
    {name === 'edit_file' && !validEdit && <p className="yca-tool-warning">Invalid or missing replacement fields; no replacement intent inferred. Inspect raw arguments.</p>}
    {name === 'write_file' && <p className="yca-tool-muted">Requested full file content (create or overwrite), not a diff against existing content.</p>}
    {fields.length === 0 && <p className="yca-tool-muted">{name === 'code_check_graph' ? 'Check the current code graph; no parameters.' : 'No recognized request fields supplied.'}</p>}
    <dl className="yca-tool-fields">{fields.map((key) => {
      const value = args[key];
      if (name === 'apply_patch' && key === 'patch' && typeof value === 'string') {
        return <div key={key}><dt>patch</dt><dd><PatchRequest patch={value} diffMode={diffMode} /></dd></div>;
      }
      if (validEdit && (key === 'oldString' || key === 'newString') && typeof value === 'string') {
        return <div key={key}><dt>{key}</dt><dd><ExactText label={key === 'oldString' ? 'Before: Exact text to find (remove matched text)' : value === '' ? 'Replacement: delete matched text' : 'After: Requested replacement'} text={value} language={fileLanguage} /></dd></div>;
      }
      const language = name === 'write_file' && key === 'content' && typeof value === 'string' ? fileLanguage
        : name === 'bash' && key === 'command' && typeof value === 'string' ? 'bash'
        : typeof value === 'object' ? 'json' : undefined;
      return <div key={key}><dt>{key}</dt><dd><ProgressiveText label={`Request ${key}`} text={displayValue(value)} language={language} /></dd></div>;
    })}</dl>
    {Object.keys(args).some((key) => !fields.includes(key)) && <p className="yca-tool-muted">Additional fields are available in raw arguments.</p>}
  </>;
}

function replacementSide(marker: string, text: string) {
  if (text === '') {
    return '';
  }
  const parts = text.split('\n');
  const finalNewline = text.endsWith('\n');
  if (finalNewline) {
    parts.pop();
  }
  return parts.map(line => `${marker}${line}\n`).join('') + (finalNewline ? '' : '\\ No newline at end of string\n');
}

export const ToolCard = memo(function ToolCard({ block, collapsed, onToggle, diffMode = 'auto' }: {
  block: ToolBlock; collapsed: boolean; onToggle: () => void; diffMode?: DiffMode;
}) {
  const failed = isToolVisuallyFailed(block);
  const status = block.state === 'running' ? 'working' : block.state === 'interrupted' ? 'interrupted' : block.pending ? 'pending' : failed ? 'failed' : 'completed';
  const summary = useMemo(() => summarizeToolArgs(block.name, block.args), [block.name, block.args]);
  // Empty final output is authoritative. Preserve exact whitespace in streamed and final content.
  const output = block.output ?? block.content;
  const presentation = useMemo(() => presentToolOutput(block.name, output, block.args), [block.name, output, block.args]);
  const final = block.state === 'done' && !block.pending;
  const resultLabel = isBuiltin(block.name) ? RESULT_LABELS[block.name] : 'External tool output';
  const args = useMemo(() => parseToolArgs(block.args), [block.args]);
  // Collapsed tools reveal only known diff sections, never the rest of the request/result.
  const validEdit = block.name === 'edit_file' && args !== undefined && args !== null && isValidEditRequest(args);
  const hasDiff = presentation.diff || (block.files?.length ?? 0) > 0 || validEdit || (block.name === 'apply_patch' && typeof args?.patch === 'string');
  const diffPreview = collapsed && hasDiff ? <>
    {validEdit && args && <section aria-label="Requested replacement">
      <p className="yca-tool-muted">Requested exact replacement: {args.replaceAll === true ? 'all occurrences' : 'one unique match'}. Snippets only, not applied file changes or file line numbers.</p>
      <ProgressiveText label={`Requested replacement for ${args.path}`} diff diffMode={diffMode} unnumbered path={args.path as string}
        text={`@@ Requested snippets @@\n${replacementSide('-', args.oldString as string)}${replacementSide('+', args.newString as string)}`} />
    </section>}
    {block.name === 'apply_patch' && typeof args?.patch === 'string' && <section aria-label="Requested patch">
      <PatchRequest patch={args.patch} diffMode={diffMode} />
    </section>}
    {presentation.diff && <ProgressiveText label={resultLabel} text={presentation.text} diff diffMode={diffMode} />}
    {block.files && block.files.length > 0 && <AppliedChanges files={block.files} mode={diffMode} diffOnly />}
  </> : undefined;
  return <ToolCall label={block.name} query={summary} status={status} open={!collapsed} onOpenChange={onToggle}
    preview={diffPreview}
    request={<RequestDetails name={block.name} raw={block.args} diffMode={diffMode} />}
    result={<>
      <h4>{final ? 'Result' : 'Result preview'} <span className="yca-tool-muted">{resultLabel}</span></h4>
      {!final && <p className="yca-tool-muted">{block.state === 'interrupted' ? 'Execution interrupted; output may be incomplete.' : block.pending ? 'Still pending; this is not a final result.' : 'Running; output may be incomplete.'}</p>}
      {failed && <p className="yca-tool-warning">Tool failed. The request above is intent, not confirmation that changes were applied.</p>}
      {output !== '' ? (block.name === 'skill' && final && block.success === true
        ? <SkillResult content={output} />
        : <ProgressiveText label={resultLabel} text={presentation.text} language={presentation.language} diff={presentation.diff} diffMode={diffMode} />) : <p className="yca-tool-muted">{block.output !== undefined ? 'Empty output.' : 'No output available.'}</p>}
      {block.files && block.files.length > 0 && <AppliedChanges files={block.files} mode={diffMode} />}
    </>}
  >
    <ToolDisclosure label="Raw arguments"><ProgressiveText label="Raw arguments" text={block.args ?? 'Arguments unavailable in this event.'} /></ToolDisclosure>
    <ToolDisclosure label="Raw output"><ProgressiveText label="Raw output" text={presentation.raw} /></ToolDisclosure>
  </ToolCall>;
});

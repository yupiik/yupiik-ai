import { guessToolLanguage } from './toolOutput';

// Presentation only: requests describe intent, never proof that a mutation succeeded.
export const BUILTIN_FIELDS = {
  bash: ['command', 'workdir', 'timeout_seconds'],
  read: ['path', 'offset', 'limit'],
  glob: ['pattern', 'path', 'hidden', 'limit'],
  grep: ['pattern', 'path', 'include', 'literal', 'ignoreCase', 'limit', 'maxResults'],
  edit_file: ['path', 'replaceAll', 'oldString', 'newString'],
  write_file: ['path', 'content'],
  apply_patch: ['patch'],
  webfetch: ['url', 'format', 'timeout_seconds'],
  websearch: ['query', 'num_results'],
  write_todo: ['todos'],
  ask_user: ['questions'],
  skill: ['name'],
  spawn_subagent: ['name', 'task', 'agent', 'model', 'max_turns', 'tools'],
  check_subagents: ['action', 'subagent_id', 'max_wait_ms'],
  list_subagent_options: ['kind', 'query', 'provider', 'offset', 'limit'],
  compress: ['action', 'topic', 'content', 'blockId', 'focus', 'keep_last'],
  memory_write: ['fact', 'scope', 'keep', 'p', 'topic', 'path'],
  maven_knowledge_content: ['gav', 'file'],
  maven_repository_knowledge: ['operation', 'gavs', 'force'],
  code_resolve: ['op', 'symbol', 'relation', 'artifact', 'depth', 'max'],
  code_trace_calls: ['symbol', 'direction', 'edgeTypes', 'depth', 'max'],
  code_find_code: ['query', 'kind', 'max'],
  code_check_graph: [],
  read_symbol: ['symbol', 'context'],
} as const;

export type BuiltinName = keyof typeof BUILTIN_FIELDS;
export function isBuiltin(name: string): name is BuiltinName {
  return Object.prototype.hasOwnProperty.call(BUILTIN_FIELDS, name);
}

// Avoid parsing arbitrarily large/untrusted JSON (including deeply nested values).
// The untouched source is still available through the paged raw disclosure.
export const MAX_STRUCTURED_ARGS = 1_000_000;
export function parseToolArgs(raw?: string): Record<string, unknown> | null {
  if (!raw || raw.length > MAX_STRUCTURED_ARGS) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

export function displayValue(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  } catch {
    return '[Value too deeply nested to format; see raw arguments]';
  }
}

export function isValidEditRequest(args: Record<string, unknown>): boolean {
  return typeof args.path === 'string' && typeof args.oldString === 'string' && typeof args.newString === 'string'
    && (args.replaceAll === undefined || typeof args.replaceAll === 'boolean');
}

export type OutputPresentation = { raw: string; text: string; language?: string; diff?: boolean };

/** Pure, bounded presentation. Raw output is never reformatted or inferred from request intent. */
export function presentToolOutput(name: string, raw: string, argsJson?: string): OutputPresentation {
  const exact: OutputPresentation = { raw, text: raw };
  if (raw.length <= MAX_STRUCTURED_ARGS) {
    try {
      const value: unknown = JSON.parse(raw);
      if (value !== null && typeof value === 'object') {
        const text = JSON.stringify(value, null, 2);
        if (text.length <= MAX_STRUCTURED_ARGS) {
          return { raw, text, language: 'json' };
        }
      }
    } catch {
      // Malformed JSON or excessive nesting: retain the exact output.
    }
    const framed = /^(?:diff --git [^\n]+\r?\n(?:index [^\n]+\r?\n)?)?--- [^\n]+\r?\n\+\+\+ [^\n]+\r?\n@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@/.test(raw)
      || (/^\*\*\* Begin Patch\r?\n/.test(raw) && /\n\*\*\* End Patch(?:\r?\n)?$/.test(raw));
    if (framed) {
      const patch = parsePatch(raw);
      if (!patch.warning && patch.files.length > 0) {
        return { ...exact, diff: true };
      }
    }
  }
  const args = parseToolArgs(argsJson);
  if (name === 'skill') {
    return { ...exact, language: 'markdown' };
  }
  if (name === 'webfetch') {
    return { ...exact, language: args?.format === 'html' ? 'markup' : args?.format === 'markdown' ? 'markdown' : undefined };
  }
  // Only these builtins can supply path/command hints. External tools stay content-only.
  if (name === 'read' || name === 'bash') {
    const key = name === 'read' ? 'path' : 'command';
    const hints = typeof args?.[key] === 'string' ? JSON.stringify({ [key]: args[key] }) : undefined;
    return { ...exact, language: guessToolLanguage(raw, hints) ?? undefined };
  }
  return exact;
}

export function summarizeToolArgs(name: string, raw?: string): string {
  const args = parseToolArgs(raw);
  if (!args || !isBuiltin(name)) {
    return '';
  }
  if (name === 'apply_patch' && typeof args.patch === 'string') {
    const patch = parsePatch(args.patch);
    return patch.files.slice(0, 3).map((file) => `${file.operation} ${file.path}`).join(' | ').slice(0, 240);
  }
  if (name === 'write_todo' || name === 'ask_user') {
    const value = args[name === 'write_todo' ? 'todos' : 'questions'];
    return Array.isArray(value) ? `${value.length} ${name === 'write_todo' ? 'todos' : 'questions'} requested` : '';
  }
  const keys = BUILTIN_FIELDS[name].filter((key) => !['oldString', 'newString', 'content'].includes(key));
  const parts = keys.flatMap((key) => {
    const value = args[key];
    if (value === undefined) {
      return [];
    }
    // Bound before serialization; large arrays/objects belong in request details, not headers.
    const text = typeof value === 'object'
      ? (Array.isArray(value) ? `${value.length} items` : 'object') : String(value).slice(0, 160);
    return [`${key}: ${text.replace(/\s+/g, ' ')}`];
  });
  if (name === 'edit_file' && isValidEditRequest(args)) {
    parts.push(args.replaceAll === true ? 'all occurrences' : 'unique match');
    if (args.newString === '') {
      parts.push('delete requested text');
    }
  }
  return parts.join(' | ').slice(0, 240);
}

export type PatchFile = {
  path: string;
  destination?: string;
  operation: 'add' | 'delete' | 'update' | 'move';
  added: number;
  removed: number;
  diff: string;
  unnumbered?: true;
};
export type PatchPreview = { files: PatchFile[]; warning?: string };

/** A preview parser, not a patch validator. Keep hunk context verbatim, and never infer applied state. */
export function parsePatch(patch: string): PatchPreview {
  if (patch.length > MAX_STRUCTURED_ARGS) {
    return { files: [], warning: 'Patch too large for structured preview; inspect the complete raw arguments.' };
  }
  const files: PatchFile[] = [];
  const lines = patch.split('\n');
  let file: PatchFile | undefined;
  let body: string[] = [];
  let inHunk = false;
  let remainingOld = 0;
  let remainingNew = 0;
  let openCode = false;
  let unrecognized = false;
  const flush = () => {
    if (file) {
      if (!openCode && (!inHunk || remainingOld !== 0 || remainingNew !== 0)) {
        unrecognized = true;
      }
      file.diff = body.join('\n');
      files.push(file);
    }
    file = undefined;
    body = [];
    inHunk = false;
  };
  for (let i = 0; i < lines.length; i++) {
    const rawLine = lines[i];
    const line = rawLine.replace(/\r$/, '');
    if (!openCode && (line.startsWith('diff ') || line.startsWith('index ')) && (!inHunk || (remainingOld === 0 && remainingNew === 0))) {
      flush();
      continue;
    }
    const operation = /^\*\*\* (Add|Delete|Update) File:\s?(.*)$/.exec(line);
    if (operation) {
      flush();
      openCode = true;
      file = { path: operation[2], operation: operation[1] === 'Add' ? 'add' : operation[1] === 'Delete' ? 'delete' : 'update', added: 0, removed: 0, diff: '', unnumbered: true };
      inHunk = operation[1] === 'Add';
      continue;
    }
    // A ---/+++ pair inside a hunk can be literal removed/added content.
    if (line.startsWith('--- ') && lines[i + 1]?.startsWith('+++ ') && (!inHunk || (!openCode && remainingOld <= 0 && remainingNew <= 0))) {
      flush();
      openCode = false;
      const oldPath = line.slice(4).split('\t')[0];
      const newPath = lines[++i].replace(/\r$/, '').slice(4).split('\t')[0];
      const path = (oldPath === '/dev/null' ? newPath : oldPath).replace(/^[ab]\//, '');
      const destination = newPath.replace(/^[ab]\//, '');
      file = { path, operation: oldPath === '/dev/null' ? 'add' : newPath === '/dev/null' ? 'delete' : path !== destination ? 'move' : 'update', added: 0, removed: 0, diff: '' };
      if (file.operation === 'move') {
        file.destination = destination;
      }
      continue;
    }
    if (line.startsWith('*** Move to:') && file && openCode) {
      file.destination = line.slice('*** Move to:'.length).replace(/^ /, '');
      file.operation = 'move';
      continue;
    }
    if (line === '*** Begin Patch' || line === '*** End Patch') {
      continue;
    }
    if (file) {
      body.push(rawLine);
      if (line.startsWith('@@')) {
        if (!openCode && inHunk && (remainingOld !== 0 || remainingNew !== 0)) {
          unrecognized = true;
        }
        inHunk = true;
        const range = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(line);
        if (range) {
          remainingOld = Number(range[2] ?? 1);
          remainingNew = Number(range[4] ?? 1);
        } else if (!openCode) {
          unrecognized = true;
        }
      } else if (inHunk) {
        if (line.startsWith('+')) {
          file.added++;
          remainingNew--;
        } else if (line.startsWith('-')) {
          file.removed++;
          remainingOld--;
        } else if (line.startsWith(' ')) {
          remainingOld--;
          remainingNew--;
        } else if (line !== '' && line !== '\\ No newline at end of file' && line !== '*** End of File') {
          unrecognized = true;
        }
      } else if (line.trim() && line !== '*** End of File') {
        unrecognized = true;
      }
    } else if (line.trim() && !line.startsWith('diff ') && !line.startsWith('index ')) {
      unrecognized = true;
    }
  }
  flush();
  return { files, warning: !files.length || unrecognized ? 'Some patch syntax could not be previewed; inspect raw arguments for the complete request.' : undefined };
}

export const TEXT_PAGE_SIZE = 4000;
export function textPage(text: string, page: number): { text: string; start: number; end: number; more: boolean } {
  const start = Math.min(Math.max(0, page) * TEXT_PAGE_SIZE, Math.max(0, Math.ceil(text.length / TEXT_PAGE_SIZE) - 1) * TEXT_PAGE_SIZE);
  const end = Math.min(text.length, start + TEXT_PAGE_SIZE);
  return { text: text.slice(start, end), start, end, more: end < text.length };
}

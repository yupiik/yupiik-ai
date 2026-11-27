import { createTwoFilesPatch } from 'diff';

export type FileChange = {
  path: string;
  kind: 'create' | 'update' | 'delete';
  oldText?: string | null;
  newText?: string;
};

/** Validate the optional UI payload; absent content is not an empty or newly created file. */
export function parseFileChanges(value: unknown): FileChange[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item): FileChange[] => {
    if (!item || typeof item !== 'object' || typeof item.path !== 'string'
      || !['create', 'update', 'delete'].includes(item.kind)) {
      return [];
    }
    const file: FileChange = { path: item.path, kind: item.kind };
    if (typeof item.newText === 'string'
      && (item.kind === 'create' ? item.oldText === null : typeof item.oldText === 'string')) {
      file.oldText = item.oldText;
      file.newText = item.newText;
    }
    return [file];
  });
}

// Bound synchronous diff work as well as DOM size. Larger captures remain available as paged text.
export const MAX_DIFF_CHARACTERS = 100_000;
export const MAX_DIFF_LINES = 2_000;
export function canRenderDiff(text: string): boolean {
  return text.length <= MAX_DIFF_CHARACTERS && text.split('\n').length <= MAX_DIFF_LINES;
}

/** Uses captured text only. Constant header paths prevent unusual filenames injecting patch syntax. */
export function fileChangePatch(file: FileChange): string | undefined {
  if (file.newText === undefined || file.oldText === undefined) {
    return undefined;
  }
  const before = file.oldText ?? '';
  if (!canRenderDiff(before + file.newText)) {
    return undefined;
  }
  const oldPath = file.kind === 'create' ? '/dev/null' : 'a/file';
  const newPath = file.kind === 'delete' ? '/dev/null' : 'b/file';
  const patch = createTwoFilesPatch(oldPath, newPath, before, file.newText, undefined, undefined, { context: 3, timeout: 100 });
  if (!patch) {
    return undefined;
  }
  // react-diff-view requires git framing, not jsdiff's generic separator header.
  return `diff --git a/file b/file\n${patch.slice(patch.indexOf('--- '))}`;
}

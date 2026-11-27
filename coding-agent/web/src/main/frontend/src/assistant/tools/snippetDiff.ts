import { parseDiff, type FileData } from 'react-diff-view';

export type SnippetFile = FileData & { unnumbered: true; endOfFile: boolean };

/** Adapt bounded, explicitly unnumbered patch bodies to react-diff-view.
 * Coordinates are local to this preview, never file locations: the caller must
 * hide gutters. Original anchors are kept as decorations, not parsed as ranges.
 */
export function snippetDiff(text: string): SnippetFile | undefined {
  const lines = text.split('\n');
  if (lines.at(-1) === '') {
    lines.pop();
  }
  const hunks: string[] = [];
  const anchors: string[] = [];
  let body: string[] = [];
  let anchor = '';
  let oldLine = 1;
  let newLine = 1;
  let oldCount = 0;
  let newCount = 0;
  let changed = false;
  let endOfFile = false;
  let previous = '';
  const flush = () => {
    if (body.length > 0) {
      hunks.push(`@@ -${oldLine},${oldCount} +${newLine},${newCount} @@\n${body.join('\n')}\n`);
      anchors.push(anchor);
      oldLine += oldCount;
      newLine += newCount;
    }
    body = [];
    oldCount = 0;
    newCount = 0;
    previous = '';
  };
  for (const raw of lines) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
    if (endOfFile) {
      return undefined;
    }
    if (line.startsWith('@@')) {
      flush();
      anchor = line;
    } else if (line === '*** End of File') {
      endOfFile = true;
    } else if (line === '\\ No newline at end of file' || line === '\\ No newline at end of string') {
      if (previous !== '+' && previous !== '-' && previous !== ' ') {
        return undefined;
      }
      body.push('\\ No newline at end of file');
      previous = '';
    } else if (line.startsWith('+') || line.startsWith('-') || line.startsWith(' ')) {
      body.push(line);
      previous = line[0];
      if (previous !== '+') {
        oldCount++;
      }
      if (previous !== '-') {
        newCount++;
      }
      changed ||= previous !== ' ';
    } else {
      // Unknown syntax (including incomplete/paged lines) stays literal.
      return undefined;
    }
  }
  flush();
  if (!changed) {
    return undefined;
  }
  const [file] = parseDiff(`diff --git a/file b/file\n--- a/file\n+++ b/file\n${hunks.join('')}`, { nearbySequences: 'zip' });
  if (!file || file.hunks.length !== anchors.length) {
    return undefined;
  }
  file.hunks.forEach((hunk, index) => { hunk.content = anchors[index]; });
  return { ...file, unnumbered: true, endOfFile };
}

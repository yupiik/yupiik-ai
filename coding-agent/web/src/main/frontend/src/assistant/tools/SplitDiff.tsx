import { useMemo, type HTMLAttributes, type ReactNode } from 'react';
import { Diff, Hunk, Decoration, parseDiff, tokenize, type FileData, type TokenizeOptions } from 'react-diff-view';
import { refractor, syntaxLanguage } from '../../lib/syntaxHighlighting';
import { guessPathLanguage } from '../../lib/toolOutput';
import { parsePatch } from '../../lib/toolDetails';
import { canRenderDiff, MAX_DIFF_LINES } from '../../lib/fileChanges';
import { snippetDiff, type SnippetFile } from './snippetDiff';
import 'react-diff-view/style/index.css';
import './diff.css';

// react-diff-view's API expects Refractor 3's node array. Refractor 5 (already
// used by PrismLight) returns the same HAST nodes inside a root instead.
const diffRefractor = {
  highlight: (text: string, language: string) => refractor.highlight(text, language).children,
} as unknown as Extract<TokenizeOptions, { highlight: true }>['refractor'];

export default function SplitDiff({ text, startsAtLine, path, oldSource, unnumbered = false, fallback, ...props }: HTMLAttributes<HTMLElement> & {
  text: string; startsAtLine?: boolean; path?: string; oldSource?: string; unnumbered?: boolean; fallback: ReactNode;
}) {
  const files = useMemo(() => {
    if (startsAtLine === false || !canRenderDiff(text)) {
      return [];
    }
    try {
      let parsed: (FileData & Partial<Pick<SnippetFile, 'unnumbered' | 'endOfFile'>>)[];
      if (unnumbered) {
        const file = snippetDiff(text);
        parsed = file ? [file] : [];
      } else {
        const preview = parsePatch(text);
        // The renderer is not a validator. Partial/malformed patches stay literal.
        if (preview.warning) {
          return [];
        }
        if (preview.files.every(file => file.unnumbered)) {
          parsed = [];
          for (const request of preview.files) {
            const file = snippetDiff(request.diff);
            if (!file) {
              return [];
            }
            parsed.push({ ...file, oldPath: request.path, newPath: request.destination ?? request.path });
          }
        } else {
          const patch = text.startsWith('diff --git ') ? text : `diff --git a/file b/file\n${text}`;
          parsed = parseDiff(patch, { nearbySequences: 'zip' });
        }
      }
      if (parsed.some((file) => file.hunks.some((hunk) =>
        ![hunk.oldStart, hunk.newStart, hunk.oldLines, hunk.newLines,
          hunk.oldStart + hunk.oldLines, hunk.newStart + hunk.newLines]
          .every((value) => Number.isSafeInteger(value) && value >= 0)))) {
        return [];
      }
      return parsed.map((file) => {
        const language = syntaxLanguage(guessPathLanguage(path ?? file.newPath ?? file.oldPath) ?? undefined);
        let tokens;
        // Without source, tokenize expands sparse patches to their highest line
        // coordinate. A tiny patch at line ten million must not allocate ten
        // million blank lines. Keep the split view, but omit expensive colors.
        const boundedHighlight = (!oldSource || canRenderDiff(oldSource)) && file.hunks.every((hunk) =>
          hunk.oldStart + hunk.oldLines <= MAX_DIFF_LINES + 1 && hunk.newStart + hunk.newLines <= MAX_DIFF_LINES + 1);
        if (language && boundedHighlight) {
          try {
            tokens = tokenize(file.hunks, { highlight: true, refractor: diffRefractor, language, oldSource: file.unnumbered ? undefined : oldSource });
          } catch {
            // An unsupported grammar must never hide an otherwise valid diff.
          }
        }
        return { file, tokens };
      });
    } catch {
      return [];
    }
  }, [text, startsAtLine, path, oldSource, unnumbered]);
  if (!files.length || files.some(({ file }) => file.hunks.length === 0)) {
    return fallback;
  }
  return <div {...props} className={`yca-split-diff ${props.className ?? ''}`} data-slot="diff-viewer" data-mode="side-by-side">
    {files.map(({ file, tokens }, index) => <div key={index}>
      {files.length > 1 && <h5>{file.newPath === '/dev/null' ? file.oldPath : file.newPath}</h5>}
      {file.unnumbered && <p className="yca-tool-muted">Snippet preview; file line numbers unavailable.</p>}
      <Diff viewType="split" diffType={file.type} hunks={file.hunks} tokens={tokens} gutterType={file.unnumbered ? 'none' : 'default'}>
        {(hunks) => hunks.flatMap((hunk, index) => [
          ...(file.unnumbered && hunk.content ? [<Decoration key={`anchor-${index}`}>{hunk.content}</Decoration>] : []),
          <Hunk key={`hunk-${index}`} hunk={hunk} />,
        ])}
      </Diff>
      {file.endOfFile && <p className="yca-tool-muted">End of file anchor.</p>}
      {(file.oldEndingNewLine === false || file.newEndingNewLine === false) && <p className="yca-tool-muted">
        {file.oldEndingNewLine === false && 'Before: no final newline. '}
        {file.newEndingNewLine === false && 'After: no final newline.'}
      </p>}
    </div>)}
  </div>;
}

import type { Block } from '../api/eventLog';

/** Visual failure only: a shell exit status does not change the tool execution's success contract. */
export function isToolVisuallyFailed(block: Extract<Block, { kind: 'tool' }>): boolean {
  if (block.state !== 'done' || block.pending === true) {
    return false;
  }
  if (block.success === false) {
    return true;
  }
  if (block.name !== 'bash' || !block.output) {
    return false;
  }
  // Inspect only the final result, never streamed content or the renderer's truncated preview.
  // Accept a terminal LF/CRLF, but not whitespace, prose, or additional lines after the footer.
  const footer = /(?:^|\n)Exit code: (-?\d+)(?:\r?\n)?$/.exec(block.output);
  const code = footer === null ? NaN : Number(footer[1]);
  return footer !== null
    && footer.index + footer[0].length === block.output.length
    && Number.isInteger(code) && code >= -2147483648 && code <= 2147483647
    && code !== 0;
}

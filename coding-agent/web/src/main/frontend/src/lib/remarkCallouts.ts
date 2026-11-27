import type { Blockquote, Nodes, Root } from 'mdast';

const CALLOUT_MARKER = /^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\](\n|$)/;

// Work on parsed blockquotes, never on source text: fenced/inline code stays literal.
export function remarkCallouts() {
  return (tree: Root): void => {
    function visit(node: Nodes): void {
      if (node.type === 'blockquote') {
        transform(node);
      }
      if ('children' in node) {
        for (const child of node.children) {
          visit(child);
        }
      }
    }
    visit(tree);
  };
}

function transform(quote: Blockquote): void {
  const paragraph = quote.children[0];
  if (paragraph?.type !== 'paragraph') {
    return;
  }
  const first = paragraph.children[0];
  if (first?.type !== 'text') {
    return;
  }
  const match = CALLOUT_MARKER.exec(first.value);
  if (!match) {
    return;
  }
  // A marker followed by inline formatting on the same line is ordinary prose.
  if (!match[2] && paragraph.children.length > 1 && paragraph.children[1].type !== 'break') {
    return;
  }
  const title = match[1];
  first.value = first.value.slice(match[0].length);
  if (!first.value) {
    paragraph.children.shift();
    if (!match[2] && paragraph.children[0]?.type === 'break') {
      paragraph.children.shift();
    }
  }
  if (paragraph.children.length === 0) {
    quote.children.shift();
  }
  quote.data = {
    ...quote.data,
    hProperties: { ...quote.data?.hProperties, 'data-callout': title.toLowerCase() },
  };
  quote.children.unshift({
    type: 'paragraph',
    data: { hProperties: { className: ['yca-callout-title'] } },
    children: [{ type: 'text', value: title }],
  });
}

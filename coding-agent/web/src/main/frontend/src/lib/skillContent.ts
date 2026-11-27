export type SkillContent = { body: string; frontMatter?: string };

/** Separate only a closed, leading YAML block; do not parse or reinterpret its values. */
export function parseSkillContent(content: string): SkillContent {
  const opening = /^(?:\uFEFF)?---[ \t]*\r?\n/.exec(content);
  if (!opening) {
    return { body: content };
  }
  const closing = /^(?:---|\.\.\.)[ \t]*(?:\r?\n|$)/gm;
  closing.lastIndex = opening[0].length;
  const match = closing.exec(content);
  if (!match) {
    return { body: content };
  }
  return {
    frontMatter: content.slice(opening[0].length, match.index),
    body: content.slice(match.index + match[0].length),
  };
}

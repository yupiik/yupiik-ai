import { useMemo } from 'react';
import { parseSkillContent } from '../lib/skillContent';
import { CodeBlock } from './CodeBlock';
import { MarkdownContent } from './MarkdownContent';

export function SkillResult({ content }: { content: string }) {
  const { frontMatter, body } = useMemo(() => parseSkillContent(content), [content]);
  return <>
    {frontMatter !== undefined && <section aria-label="Skill front matter">
      <h5>YAML front matter</h5>
      <CodeBlock code={frontMatter} language="yaml" />
    </section>}
    <MarkdownContent content={body} variant="document" />
  </>;
}

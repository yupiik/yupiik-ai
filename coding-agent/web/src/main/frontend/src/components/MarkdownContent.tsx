import React from 'react';
import { TextMessagePartProvider } from '@assistant-ui/react';
import { MarkdownTextPrimitive, type MarkdownTextPrimitiveProps } from '@assistant-ui/react-markdown';
import remarkGfm from 'remark-gfm';
import { remarkCallouts } from '../lib/remarkCallouts';
import { CodeBlock } from './CodeBlock';

const components: MarkdownTextPrimitiveProps['components'] = {
  a: ({ node, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer" />,
  CodeHeader: () => null,
  SyntaxHighlighter: ({ code, language }) => <CodeBlock code={code} language={language || undefined} />,
};

export function MarkdownContent({ content, variant = 'document' }: {
  content: string;
  variant?: 'user' | 'assistant' | 'document' | 'thought';
}) {
  return (
    <TextMessagePartProvider text={content}>
      <MarkdownTextPrimitive
        className="aui-md"
        containerProps={{ 'data-status': undefined, 'data-variant': variant } as MarkdownTextPrimitiveProps['containerProps']}
        smooth={false}
        remarkPlugins={[remarkGfm, remarkCallouts]}
        components={components}
      />
    </TextMessagePartProvider>
  );
}

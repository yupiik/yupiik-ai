import React from 'react';
import { makePrismLightSyntaxHighlighter } from '@assistant-ui/react-syntax-highlighter';
import { syntaxLanguage } from '../lib/syntaxHighlighting';

// The assistant-ui factory delegates tokenization to the registered PrismLight
// instance and lets callers supply the actual pre/code elements (no nested pre).
const Highlight = makePrismLightSyntaxHighlighter({ useInlineStyles: false });

export function CodeBlock({ code, language, className }: {
  code: string;
  language?: string;
  className?: string;
}) {
  const preClassName = ['yca-code-block', className].filter(Boolean).join(' ');
  const grammar = syntaxLanguage(language);
  const codeClassName = language ? `language-${language}` : undefined;
  if (!grammar) {
    return <pre className={preClassName}><code className={codeClassName}>{code}</code></pre>;
  }
  return (
    <Highlight
      language={grammar}
      code={code}
      components={{
        Pre: ({ node, className: ignoredClassName, ...props }) => <pre {...props} className={preClassName} />,
        Code: ({ node, className: ignoredClassName, ...props }) => <code {...props} className={codeClassName} />,
      }}
    />
  );
}

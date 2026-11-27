import { refractor } from 'refractor/core';
import java from 'react-syntax-highlighter/dist/esm/languages/prism/java';
import typescript from 'react-syntax-highlighter/dist/esm/languages/prism/typescript';
import javascript from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import python from 'react-syntax-highlighter/dist/esm/languages/prism/python';
import bash from 'react-syntax-highlighter/dist/esm/languages/prism/bash';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import markup from 'react-syntax-highlighter/dist/esm/languages/prism/markup';
import json from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import yaml from 'react-syntax-highlighter/dist/esm/languages/prism/yaml';
import asciidoc from 'react-syntax-highlighter/dist/esm/languages/prism/asciidoc';
import markdown from 'react-syntax-highlighter/dist/esm/languages/prism/markdown';

// PrismLight also uses refractor/core: register once for code blocks and diffs,
// without pulling in a second tokenizer or the all-languages entry point.
for (const grammar of [java, typescript, javascript, python, bash, sql, markup, json, yaml, asciidoc, markdown]) {
  refractor.register(grammar);
}

const languages = new Map([
  ['java', 'java'],
  ['typescript', 'typescript'], ['ts', 'typescript'],
  ['javascript', 'javascript'], ['js', 'javascript'],
  ['python', 'python'], ['py', 'python'],
  ['bash', 'bash'], ['sh', 'bash'], ['shell', 'bash'],
  ['sql', 'sql'],
  ['markup', 'markup'], ['xml', 'markup'], ['html', 'markup'], ['svg', 'markup'],
  ['json', 'json'], ['yaml', 'yaml'],
  ['asciidoc', 'asciidoc'], ['adoc', 'asciidoc'],
  ['markdown', 'markdown'], ['md', 'markdown'],
]);

export function syntaxLanguage(language?: string): string | undefined {
  return language === undefined ? undefined : languages.get(language);
}

export { refractor };

import { describe, expect, it } from 'vitest';
import { guessToolLanguage, guessPathLanguage, summarizeArgs } from './toolOutput';

describe('guessPathLanguage', () => {
  it('maps code file extensions to Prism grammars', () => {
    expect(guessPathLanguage('/a/b/Foo.java')).toBe('java');
    expect(guessPathLanguage('script.py')).toBe('python');
    expect(guessPathLanguage('src/App.tsx')).toBe('typescript');
    expect(guessPathLanguage('data.json')).toBe('json');
    expect(guessPathLanguage('conf.yaml')).toBe('yaml');
    expect(guessPathLanguage('index.html')).toBe('markup');
    expect(guessPathLanguage('run.sh')).toBe('bash');
    expect(guessPathLanguage('query.sql')).toBe('sql');
    expect(guessPathLanguage('README.md')).toBe('markdown');
    expect(guessPathLanguage('CHANGELOG.markdown')).toBe('markdown');
    expect(guessPathLanguage('README.adoc')).toBe('asciidoc');
    expect(guessPathLanguage('doc.asciidoc')).toBe('asciidoc');
  });

  it('returns null for unknown extensions and bare paths', () => {
    expect(guessPathLanguage('README.txt')).toBeNull();
    expect(guessPathLanguage('noextension')).toBeNull();
    expect(guessPathLanguage(null)).toBeNull();
    expect(guessPathLanguage('')).toBeNull();
  });
});

describe('guessToolLanguage', () => {
  it('read_file output is highlighted by its path extension (java)', () => {
    const out = 'package demo;\nclass Foo {\n  void bar() {}\n}';
    expect(guessToolLanguage(out, '{"path":"src/main/java/demo/Foo.java"}')).toBe('java');
  });

  it('read_file output is highlighted by its path extension (python)', () => {
    const out = 'def hello():\n    return "hi"';
    expect(guessToolLanguage(out, '{"path":"scripts/hello.py"}')).toBe('python');
  });

  it('read_file output is highlighted by its path extension (typescript)', () => {
    const out = 'const x: number = 1;';
    expect(guessToolLanguage(out, '{"path":"src/util.ts"}')).toBe('typescript');
  });

  it('bash command drives python output', () => {
    const out = 'print("hi")\nprint("there")';
    expect(guessToolLanguage(out, '{"command":"python3 script.py"}')).toBe('python');
  });

  it('bash with a shell wrapper follows the inner command (node)', () => {
    const out = 'console.log(1);\nconsole.log(2);';
    expect(guessToolLanguage(out, '{"command":"sh -c \\"node app.js\\""}')).toBe('javascript');
  });

  it('sql statements are detected from the output itself', () => {
    const out = 'SELECT id FROM users WHERE active = 1;';
    expect(guessToolLanguage(out, '{"command":"psql -d db"}')).toBe('sql');
  });

  it('python traceback fingerprints are detected even without a path or command', () => {
    const out = 'Traceback (most recent call last):\n  File "x.py", line 3, in <module>\n    print(1/0)';
    expect(guessToolLanguage(out)).toBe('python');
  });

  it('json output on a read_file with a .json path is detected as json', () => {
    const out = '{"a": 1, "b": [2, 3]}';
    expect(guessToolLanguage(out, '{"path":"conf/app.json"}')).toBe('json');
  });

  it('read_file output of a markdown document is highlighted as markdown', () => {
    const out = '# Title\n\nSome **bold** text with `code`.\n\n- item\n';
    expect(guessToolLanguage(out, '{"path":"README.md"}')).toBe('markdown');
  });

  it('read_file output of an asciidoc document is highlighted as asciidoc', () => {
    const out = '= Title\n\n:author: me\n\n== Section\n\n*item*\n';
    expect(guessToolLanguage(out, '{"path":"docs/guide.adoc"}')).toBe('asciidoc');
  });

  it('plain prose with no signal stays null (falls back to the plain pre)', () => {
    const out = 'The build completed successfully in 12.4s. Nothing to configure.';
    expect(guessToolLanguage(out)).toBeNull();
  });

  it('blank output returns null', () => {
    expect(guessToolLanguage('')).toBeNull();
    expect(guessToolLanguage('   ')).toBeNull();
    expect(guessToolLanguage(null)).toBeNull();
  });
});

describe('summarizeArgs', () => {
  it('extracts the bash command of a tool', () => {
    expect(summarizeArgs('{"command":"ls --color=none","workdir":"/x"}')).toBe('ls --color=none /x');
  });

  it('extracts the path (and lines range) of a file tool', () => {
    expect(summarizeArgs('{"path":"src/main/java/demo/Foo.java","lines":"1-20"}')).toBe(
      'src/main/java/demo/Foo.java 1-20',
    );
  });

  it('accepts a numeric or array lines field', () => {
    expect(summarizeArgs('{"path":"a.py","lines":5}')).toBe('a.py 5');
    expect(summarizeArgs('{"path":"a.py","lines":[10,20]}')).toBe('a.py 10-20');
  });

  it('orders command before path before workdir before lines', () => {
    expect(summarizeArgs('{"path":"p","command":"c","workdir":"w","lines":"3"}')).toBe('c p w 3');
  });

  it('returns empty for missing, blank or trivial args', () => {
    expect(summarizeArgs(undefined)).toBe('');
    expect(summarizeArgs(null)).toBe('');
    expect(summarizeArgs('')).toBe('');
    expect(summarizeArgs('{}')).toBe('');
    expect(summarizeArgs('{"question":"what up"}')).toBe('');
  });

  it('returns empty for malformed JSON', () => {
    expect(summarizeArgs('{not json')).toBe('');
  });

  it('ignores non-string values for the known keys', () => {
    expect(summarizeArgs('{"command":42}')).toBe('');
    expect(summarizeArgs('{"path":null}')).toBe('');
    expect(summarizeArgs('{"command":"ok","path":null}')).toBe('ok');
  });

  it('shows the glob pattern ahead of the base path (Issue #3)', () => {
    expect(summarizeArgs('{"pattern":"**/*.java","path":"."}')).toBe('**/*.java .');
    expect(summarizeArgs('{"pattern":"src/**","path":"/repo/src"}')).toBe('src/** /repo/src');
  });

  it('shows the grep pattern (and keeps path) ahead of the include filter (Issue #3)', () => {
    expect(summarizeArgs('{"pattern":"foo","include":"*.java","path":"."}')).toBe('foo .');
    // a bare include (no pattern) still gets a tiny label
    expect(summarizeArgs('{"include":"*.java"}')).toBe('in *.java');
  });

  it('shows web_search query and web_fetch url (Issue #3)', () => {
    expect(summarizeArgs('{"query":"maven shade plugin"}')).toBe('maven shade plugin');
    expect(summarizeArgs('{"url":"https://yupiik.io/","format":"markdown"}')).toBe('https://yupiik.io/');
  });

  it('shows maven, subagent, skill, memory and action primaries (Issue #3)', () => {
    expect(summarizeArgs('{"gav":"org.group:art:1.0"}')).toBe('org.group:art:1.0');
    expect(summarizeArgs('{"operation":"index"}')).toBe('index');
    expect(summarizeArgs('{"action":"poll"}')).toBe('poll');
    expect(summarizeArgs('{"name":"my-skill"}')).toBe('my-skill');
    expect(summarizeArgs('{"fact":"the build needs JDK 21"}')).toBe('the build needs JDK 21');
    // spawn_subagent: name leads over task
    expect(summarizeArgs('{"name":"builder","task":"compile everything"}')).toBe('builder');
  });

  it('renders a diff-style old → new hint for edit_file instead of the body (Issue #3)', () => {
    expect(summarizeArgs('{"path":"a.java","oldString":"int a","newString":"long a"}')).toBe('a.java int a → long a');
  });

  it('caps long old/new snippets so the one-line header stays short (Issue #3)', () => {
    const long = 'x'.repeat(120);
    const sum = summarizeArgs(`{"path":"f.js","oldString":"${long}","newString":"y"}`);
    expect(sum).toMatch(/^f\.js x{24}… → y$/);
    expect(sum.length).toBeLessThan(80);
  });

  it('renders a diff header for apply_patch and a content marker for write_file (Issue #3)', () => {
    const patch = '--- a/A.txt\n+++ b/A.txt\n@@ -1 +1 @@\n-old\n+new';
    expect(summarizeArgs(JSON.stringify({ patch }))).toBe('--- a/A.txt');
    expect(summarizeArgs('{"path":"conf/x.json","content":"{\\"a\\":1}"}')).toBe('conf/x.json content');
  });

  it('falls back to the diff header when a rewrite tool carries only patch (Issue #3)', () => {
    expect(summarizeArgs('{"patch":"+++ b/A.txt"}')).toBe('+++ b/A.txt');
  });
});

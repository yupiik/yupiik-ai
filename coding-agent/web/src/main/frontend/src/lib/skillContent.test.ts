import { describe, expect, it } from 'vitest';
import { parseSkillContent } from './skillContent';

describe('parseSkillContent', () => {
  it.each(['# Skill\n\n**Instructions**\n', '', '\n---\nname: later\n---\n', '---\nname: unclosed\n', '```yaml\n---\nname: example\n---\n```'])('preserves content without closed leading front matter: %j', (content) => {
    expect(parseSkillContent(content)).toEqual({ body: content });
  });

  it.each(['\n', '\r\n'])('separates front matter while preserving whitespace with %j line endings', (eol) => {
    const frontMatter = `name: example${eol}description: |${eol}  Keep **literal** text.${eol}`;
    const body = `${eol}# Instructions${eol}${eol}\tBody  ${eol}---${eol}More`;
    expect(parseSkillContent(`---${eol}${frontMatter}---${eol}${body}`)).toEqual({ frontMatter, body });
  });

  it('supports a BOM and YAML end marker without interpreting metadata', () => {
    expect(parseSkillContent('\uFEFF---\ninvalid: [yaml\n...\n# Body')).toEqual({ frontMatter: 'invalid: [yaml\n', body: '# Body' });
  });

  it('supports empty front matter and a closing delimiter at end of input', () => {
    expect(parseSkillContent('---\n---')).toEqual({ frontMatter: '', body: '' });
  });

  it('does not mistake delimiter-like metadata for a closing delimiter', () => {
    expect(parseSkillContent('---\nname: example\n---suffix\n  ---\n---\n# Body')).toEqual({ frontMatter: 'name: example\n---suffix\n  ---\n', body: '# Body' });
  });
});

import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import typescript from 'highlight.js/lib/languages/typescript';
import json from 'highlight.js/lib/languages/json';
import xml from 'highlight.js/lib/languages/xml';
import python from 'highlight.js/lib/languages/python';
import bash from 'highlight.js/lib/languages/bash';
import sql from 'highlight.js/lib/languages/sql';
import cpp from 'highlight.js/lib/languages/cpp';
import c from 'highlight.js/lib/languages/c';
import markdown from 'highlight.js/lib/languages/markdown';
import yaml from 'highlight.js/lib/languages/yaml';
import diff from 'highlight.js/lib/languages/diff';
import css from 'highlight.js/lib/languages/css';
import 'highlight.js/styles/github.css';
import type { Components } from 'react-markdown';

// 按需注册常用语言 (减小打包体积)
hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('json', json);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('html', xml);
hljs.registerLanguage('python', python);
hljs.registerLanguage('bash', bash);
hljs.registerLanguage('shell', bash);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('cpp', cpp);
hljs.registerLanguage('c', c);
hljs.registerLanguage('markdown', markdown);
hljs.registerLanguage('yaml', yaml);
hljs.registerLanguage('diff', diff);
hljs.registerLanguage('css', css);

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function highlightCode(code: string, lang?: string): string {
  const text = code.replace(/\n$/, '');
  if (lang && hljs.getLanguage(lang)) {
    try {
      return hljs.highlight(text, { language: lang }).value;
    } catch {
      /* fallthrough */
    }
  }
  try {
    return hljs.highlightAuto(text).value;
  } catch {
    return escapeHtml(text);
  }
}

const components: Components = {
  code({ className, children, ...props }) {
    const match = /language-(\w+)/.exec(className || '');
    const isInline = !className && !String(children).includes('\n');
    const code = String(children).replace(/\n$/, '');

    if (isInline) {
      return (
        <code className={className} {...props}>
          {children}
        </code>
      );
    }
    const lang = match ? match[1] : '';
    return (
      <pre className="relative">
        {lang && (
          <span className="absolute right-2 top-1 text-[10px] uppercase tracking-wide text-slate-400">
            {lang}
          </span>
        )}
        <code
          className={`hljs language-${lang || 'plaintext'}`}
          dangerouslySetInnerHTML={{ __html: highlightCode(code, lang) }}
        />
      </pre>
    );
  },
  a({ href, children }) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary underline">
        {children}
      </a>
    );
  },
};

export function Markdown({ content }: { content: string }) {
  if (!content) return null;
  return (
    <div className="chat-md">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {content}
      </ReactMarkdown>
    </div>
  );
}

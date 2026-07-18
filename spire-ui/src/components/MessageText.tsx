import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * Renders conversation/comment text as markdown (code fences, inline code, bold, lists, links).
 * Safe by default: react-markdown does not render raw HTML (no rehype-raw), and links are pinned to
 * http(s) + opened in a new tab with a hardened rel. Used everywhere a message body is shown.
 */
export function MessageText({ children }: { children: string }) {
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a({ href, children: text }) {
            const safe = href && /^https?:\/\//i.test(href) ? href : undefined;
            return (
              <a href={safe} target="_blank" rel="noopener noreferrer nofollow">
                {text}
              </a>
            );
          },
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}

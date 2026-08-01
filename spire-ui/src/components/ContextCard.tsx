import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, ExternalLink } from 'lucide-react';
import { fetchReviewContext, fetchPrDescription, type ContextItem, type ReviewContext } from '../api';
import { safeHttpUrl } from '../render';
import Tooltip from './Tooltip';

const COMMENTS_MARKER = 'Recent comments:';
// The marker is matched WITHOUT its leading newlines: one provider strips the body, so a ticket
// with no description at all starts directly with the marker and would otherwise fall through as
// prose. Matching at a line start covers both shapes.
const COMMENTS_MARKER_LINE = new RegExp(`^${COMMENTS_MARKER}$`, 'm');

/**
 * Providers append comments into the item body under a fixed marker rather than as structured
 * data, so the split happens here. A test per provider pins the marker; if one ever changes its
 * wording, that test fails rather than this quietly showing an empty comments section.
 */
export function splitComments(body: string): { detail: string; comments: string | null } {
  const match = COMMENTS_MARKER_LINE.exec(body);
  if (!match) return { detail: body, comments: null };
  return {
    detail: body.slice(0, match.index).trim(),
    comments: body.slice(match.index + COMMENTS_MARKER.length).trim(),
  };
}

// A comment header looks like "- author: text". A comment's OWN markdown list (e.g. "- point one")
// starts the same way but has no colon before the line breaks — matching on the colon keeps those
// nested bullets from inflating the count.
const COMMENT_HEADER = /^- [^:]+:/;

function commentCount(comments: string): number {
  return comments.split('\n').filter((line) => COMMENT_HEADER.test(line)).length;
}

function ContextItemRow({ item }: { item: ContextItem }) {
  const [open, setOpen] = useState(false);
  const [showComments, setShowComments] = useState(false);
  const { detail, comments } = splitComments(item.body);
  const count = comments ? commentCount(comments) : 0;
  const href = safeHttpUrl(item.uri ?? undefined);

  return (
    <div className="ctx-item">
      <div className="ctx-item-row">
        <button className="ctx-item-head" aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          <span className="ctx-item-kind">{item.kind}</span>
          <span className="ctx-item-title">{item.title}</span>
        </button>
        {href && (
          <Tooltip label="Open in the issue tracker">
            <a
              className="icon-btn"
              href={href}
              target="_blank"
              rel="noreferrer noopener"
              aria-label="Open in the issue tracker"
            >
              <ExternalLink size={16} />
            </a>
          </Tooltip>
        )}
      </div>
      {open && (
        <div className="ctx-item-body">
          <pre className="ctx-detail">{detail}</pre>
          {comments && (
            <>
              <button
                className="ctx-comments-toggle"
                aria-expanded={showComments}
                onClick={() => setShowComments(!showComments)}
              >
                {showComments ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                {count} comment{count === 1 ? '' : 's'}
              </button>
              {showComments && <pre className="ctx-comments">{comments}</pre>}
            </>
          )}
        </div>
      )}
    </div>
  );
}

interface ContextCardProps {
  workspace: string;
  slug: string;
  pr: number;
  // The review's current head commit. Threaded through so the effect below refetches when the
  // review advances to a new run — without it, this card kept showing the previous run's items
  // after a re-run while every sibling card live-updated.
  sha: string;
}

export default function ContextCard({ workspace, slug, pr, sha }: ContextCardProps) {
  const [context, setContext] = useState<ReviewContext | null>(null);
  // Kept separate from an empty ReviewContext: "we asked and there was nothing" and "we could not
  // ask" must never render the same message, or a rejected credential / 5xx looks identical to the
  // normal no-provider-configured path.
  const [loadFailed, setLoadFailed] = useState(false);
  const [description, setDescription] = useState<string | null>(null);
  // Distinct from "description === null" (not fetched yet): a failed fetch (revoked credential,
  // network error) must not render as "this pull request has no description".
  const [descriptionFailed, setDescriptionFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoadFailed(false);
    void fetchReviewContext(workspace, slug, pr)
      .then((c) => {
        if (!cancelled) setContext(c);
      })
      .catch((err) => {
        if (!cancelled) {
          console.error('Failed to load review context', err);
          setLoadFailed(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [workspace, slug, pr, sha]);

  // The description is shown outright rather than behind a toggle, so it is fetched with the card.
  // That costs one SCM call per page view — the price of having the text that explains why a given
  // issue was pulled in visible without a click.
  useEffect(() => {
    let cancelled = false;
    setDescriptionFailed(false);
    void fetchPrDescription(workspace, slug, pr)
      .then((d) => {
        if (!cancelled) setDescription(d);
      })
      .catch((err) => {
        if (!cancelled) {
          console.error('Failed to load the pull request description', err);
          setDescriptionFailed(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [workspace, slug, pr, sha]);

  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Context</h3>
        <span className="badge">as given to the model</span>
      </div>
      <div className="body">
        <div className="ctx-desc">
          <div className="ctx-desc-label">Pull request description</div>
          {descriptionFailed ? (
            <div className="ctx-empty">The pull request description could not be loaded.</div>
          ) : (
            <>
              <pre className="ctx-detail">{description ?? '—'}</pre>
              {/* The items above are as-reviewed; this one is not. Saying so is the condition on
                  which showing live text at all was acceptable. */}
              <div className="ctx-desc-live">Current pull request text, fetched live</div>
            </>
          )}
        </div>

        {context === null && !loadFailed && <div className="ctx-empty">Loading…</div>}
        {loadFailed && <div className="ctx-empty">Could not load the context for this review.</div>}
        {!loadFailed && context !== null && context.items.length === 0 && (
          <div className="ctx-empty">No context was resolved for this review.</div>
        )}
        {context?.items.map((item, i) => <ContextItemRow key={item.uri ?? i} item={item} />)}
      </div>
    </div>
  );
}

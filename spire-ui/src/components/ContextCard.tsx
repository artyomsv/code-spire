import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { fetchReviewContext, fetchPrDescription, type ContextItem, type ReviewContext } from '../api';
import { safeHttpUrl } from '../render';

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
          <a className="ctx-item-link" href={href} target="_blank" rel="noreferrer noopener">
            Open
          </a>
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
  const [descriptionOpen, setDescriptionOpen] = useState(false);
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

  async function toggleDescription() {
    const next = !descriptionOpen;
    setDescriptionOpen(next);
    if (next && description === null && !descriptionFailed) {
      try {
        setDescription(await fetchPrDescription(workspace, slug, pr));
      } catch (err) {
        console.error('Failed to load the pull request description', err);
        setDescriptionFailed(true);
      }
    }
  }

  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Context</h3>
        <span className="badge">as given to the model</span>
      </div>
      <div className="body">
        <button className="ctx-desc-toggle" aria-expanded={descriptionOpen} onClick={() => void toggleDescription()}>
          {descriptionOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          Pull request description
        </button>
        {descriptionOpen && (
          descriptionFailed ? (
            <div className="ctx-empty">The pull request description could not be loaded.</div>
          ) : (
            <>
              <div className="ctx-desc-live">Current pull request text, fetched live</div>
              <pre className="ctx-detail">{description ?? '—'}</pre>
            </>
          )
        )}

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

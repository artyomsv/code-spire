import { useEffect, useRef, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { invalidateLive } from '../liveCache';
import { ChevronDown, ChevronRight, ExternalLink } from 'lucide-react';
import { fetchReviewContext, fetchPrDescription, prDescriptionKey, reviewContextKey, type ContextItem, type ReviewContext } from '../api';
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
  // Whether the pipeline has finished assembling context for THIS run.
  //
  // `sha` alone only covers a new run. Within one run it never moves — context is assembled at the
  // commit the card already mounted on — so a page opened while the review was still running
  // fetched once, found nothing, and never asked again. It stayed empty until a manual refresh
  // while every sibling card updated live. This flips false -> true exactly once per run, which is
  // the one moment there is something new to fetch.
  contextReady: boolean;
}

export default function ContextCard({ workspace, slug, pr, sha, contextReady }: ContextCardProps) {
  const client = useQueryClient();
  // Old items stay on screen while a new head's context is read, as they did before.
  const contextKey = reviewContextKey(workspace, slug, pr, sha);
  const contextRead = useQuery({
    queryKey: contextKey,
    queryFn: async () => {
      try {
        return await fetchReviewContext(workspace, slug, pr);
      } catch (err) {
        console.error('Failed to load review context', err);
        throw err;
      }
    },
    placeholderData: keepPreviousData,
  });
  // The last items read for this pull request stay on screen when a new commit's read fails, beside the failure, as
  // they did before (review of PR 361, M-3). Placeholder data covers only the wait, not the failure.
  const review = `${workspace}/${slug}/${pr}`;
  const [kept, setKept] = useState<{ review: string; context: ReviewContext } | null>(null);
  useEffect(() => {
    if (contextRead.data && !contextRead.isPlaceholderData) setKept({ review, context: contextRead.data });
  }, [contextRead.data, contextRead.isPlaceholderData, review]);
  const context: ReviewContext | null = contextRead.data ?? (kept?.review === review ? kept.context : null);
  // Kept separate from an empty ReviewContext: "we asked and there was nothing" and "we could not
  // ask" must never render the same message, or a rejected credential / 5xx looks identical to the
  // normal no-provider-configured path.
  const loadFailed = contextRead.isError;

  // The context stage finishing is the moment there is something new to read; mounting is not.
  const readyBefore = useRef(contextReady);
  useEffect(() => {
    if (readyBefore.current === contextReady) return;
    readyBefore.current = contextReady;
    void invalidateLive(client, reviewContextKey(workspace, slug, pr, sha));
  }, [client, contextReady, workspace, slug, pr, sha]);

  // The description is shown outright rather than behind a toggle, so it is fetched with the card.
  // That costs one SCM call per page view — the price of having the text that explains why a given
  // issue was pulled in visible without a click.
  //
  // `contextReady` is deliberately NOT a dependency here, unlike the effect above: the pull
  // request's description does not change because the pipeline assembled context, and this is the
  // one fetch on the page that spends an SCM call. Refetching it on a stage change would buy
  // nothing and cost a call per stage.
  const descriptionRead = useQuery({
    queryKey: prDescriptionKey(workspace, slug, pr, sha),
    queryFn: async () => {
      try {
        return await fetchPrDescription(workspace, slug, pr);
      } catch (err) {
        console.error('Failed to load the pull request description', err);
        throw err;
      }
    },
  });
  const description: string | null = descriptionRead.data ?? null;
  // Distinct from "description === null" (not fetched yet): a failed fetch (revoked credential,
  // network error) must not render as "this pull request has no description".
  const descriptionFailed = descriptionRead.isError;

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
            <pre className="ctx-detail">{description ?? '—'}</pre>
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

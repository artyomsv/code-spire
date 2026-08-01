import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { fetchReviewContext, fetchPrDescription, type ContextItem, type ReviewContext } from '../api';

const COMMENTS_MARKER = 'Recent comments:';

/**
 * Providers append comments into the item body under a fixed marker rather than as structured
 * data, so the split happens here. A test per provider pins the marker; if one ever changes its
 * wording, that test fails rather than this quietly showing an empty comments section.
 *
 * The marker is matched WITHOUT its leading newlines: one provider strips the body, so a ticket
 * with no description at all starts directly with the marker and would otherwise fall through as
 * prose. Matching at a line start covers both shapes.
 */
export function splitComments(body: string): { detail: string; comments: string | null } {
  const match = /^Recent comments:$/m.exec(body);
  if (!match || match.index === undefined) return { detail: body, comments: null };
  return {
    detail: body.slice(0, match.index).trim(),
    comments: body.slice(match.index + COMMENTS_MARKER.length).trim(),
  };
}

function commentCount(comments: string): number {
  return comments.split('\n').filter((line) => line.startsWith('- ')).length;
}

function ContextItemRow({ item }: { item: ContextItem }) {
  const [open, setOpen] = useState(false);
  const [showComments, setShowComments] = useState(false);
  const { detail, comments } = splitComments(item.body);

  return (
    <div className="ctx-item">
      <button className="ctx-item-head" onClick={() => setOpen(!open)}>
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span className="ctx-item-kind">{item.kind}</span>
        <span className="ctx-item-title">{item.title}</span>
      </button>
      {open && (
        <div className="ctx-item-body">
          <pre className="ctx-detail">{detail}</pre>
          {comments && (
            <>
              <button className="ctx-comments-toggle" onClick={() => setShowComments(!showComments)}>
                {showComments ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                {commentCount(comments)} comments
              </button>
              {showComments && <pre className="ctx-comments">{comments}</pre>}
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default function ContextCard({
  workspace,
  slug,
  pr,
}: {
  workspace: string;
  slug: string;
  pr: number;
}) {
  const [context, setContext] = useState<ReviewContext | null>(null);
  const [descriptionOpen, setDescriptionOpen] = useState(false);
  const [description, setDescription] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchReviewContext(workspace, slug, pr)
      .then((c) => {
        if (!cancelled) setContext(c);
      })
      .catch(() => {
        if (!cancelled) setContext({ items: [], contributingSources: [], missingSources: [] });
      });
    return () => {
      cancelled = true;
    };
  }, [workspace, slug, pr]);

  async function toggleDescription() {
    const next = !descriptionOpen;
    setDescriptionOpen(next);
    if (next && description === null) {
      setDescription(await fetchPrDescription(workspace, slug, pr).catch(() => null));
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
        <button className="ctx-desc-toggle" onClick={() => void toggleDescription()}>
          {descriptionOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          Pull request description
        </button>
        {descriptionOpen && <pre className="ctx-detail">{description ?? '—'}</pre>}

        {context === null && <div className="ctx-empty">Loading…</div>}
        {context !== null && context.items.length === 0 && (
          <div className="ctx-empty">No context was resolved for this review.</div>
        )}
        {context?.items.map((item, i) => <ContextItemRow key={item.uri ?? i} item={item} />)}
      </div>
    </div>
  );
}

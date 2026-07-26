import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { generalDiscussionCard } from './render';
import type { ReviewDetail, ReviewEvent } from './api';

/**
 * A conversation the bot did not start owns no finding, so the findings card cannot nest it and it
 * lands in General discussion. When the thread sits on a diff line, saying so is the only thing that
 * distinguishes "someone commented on line 9" from a general remark about the pull request — the
 * read model knew the location for a while before anything showed it.
 */
function detailWith(events: ReviewEvent[]): ReviewDetail {
  return {
    workspace: 'ws',
    slug: 'repo',
    pr: 7,
    findingsList: [],
    reconciliation: [],
    events,
  } as unknown as ReviewDetail;
}

const turn = (over: Partial<ReviewEvent>): ReviewEvent =>
  ({
    ts: '2026-07-26T20:00:00Z',
    at: '+0.0s',
    lane: 'integration',
    type: 'AuthorReplied',
    det: '@alice: is this really a problem?',
    threadRef: 'human-thread-1',
    threadKind: 'mention',
    ...over,
  }) as ReviewEvent;

describe('generalDiscussionCard — thread location', () => {
  it('shows where a located thread sits', () => {
    const html = renderToStaticMarkup(
      <>{generalDiscussionCard(detailWith([turn({ loc: 'src/App.java:9' })]))}</>,
    );
    expect(html).toContain('src/App.java:9');
  });

  it('shows no location for a thread that has none', () => {
    const html = renderToStaticMarkup(<>{generalDiscussionCard(detailWith([turn({})]))}</>);
    expect(html).not.toContain('general-thread-loc');
  });

  /** The opener may predate the location; any turn in the group carrying one is enough. */
  it('finds the location on a later turn of the same thread', () => {
    const html = renderToStaticMarkup(
      <>
        {generalDiscussionCard(
          detailWith([
            turn({}),
            turn({ type: 'FollowUpGenerated', det: 'answer', loc: 'src/App.java:42' }),
          ]),
        )}
      </>,
    );
    expect(html).toContain('src/App.java:42');
  });
});

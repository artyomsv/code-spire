import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchReviewContext, fetchPrDescription } from './api';

afterEach(() => vi.unstubAllGlobals());

const ok = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body } as Response);

/**
 * Every call goes through `apiFetch`, which marks it script-initiated. That marker is not decoration:
 * without it an unauthenticated call is answered with a redirect to the identity provider, which the
 * browser follows cross-origin and reports as an opaque "failed to fetch" — so the app can never tell
 * "you need to log in" from "the service is broken". Asserted rather than ignored, because a call that
 * silently loses it fails in a way that looks like an outage.
 */
const scripted = { headers: { 'X-Requested-With': 'JavaScript' } };

describe('context section api', () => {
  it('reads a review context from the worker route', async () => {
    const fetchMock = ok({ items: [], contributingSources: [], missingSources: [] });
    vi.stubGlobal('fetch', fetchMock);

    await fetchReviewContext('acme', 'widgets', 7);

    expect(fetchMock).toHaveBeenCalledWith('/wk/review-context/acme/widgets/7', scripted);
  });

  it('reads a description from the orchestrator route', async () => {
    const fetchMock = ok({ description: 'Implements #7' });
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchPrDescription('acme', 'widgets', 7)).toBe('Implements #7');
    expect(fetchMock).toHaveBeenCalledWith('/api/reviews/acme/widgets/7/description', scripted);
  });

  /** A repo or branch name can contain characters that change a URL's meaning if unescaped. */
  it('encodes path segments', async () => {
    const fetchMock = ok({ items: [], contributingSources: [], missingSources: [] });
    vi.stubGlobal('fetch', fetchMock);

    await fetchReviewContext('acme corp', 'wid/gets', 7);

    expect(fetchMock).toHaveBeenCalledWith('/wk/review-context/acme%20corp/wid%2Fgets/7', scripted);
  });
});

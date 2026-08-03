import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchReviewContext, fetchPrDescription } from './api';

afterEach(() => vi.unstubAllGlobals());

const ok = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body } as Response);

describe('context section api', () => {
  it('reads a review context from the worker route', async () => {
    const fetchMock = ok({ items: [], contributingSources: [], missingSources: [] });
    vi.stubGlobal('fetch', fetchMock);

    await fetchReviewContext('acme', 'widgets', 7);

    expect(fetchMock).toHaveBeenCalledWith('/wk/review-context/acme/widgets/7');
  });

  it('reads a description from the orchestrator route', async () => {
    const fetchMock = ok({ description: 'Implements #7' });
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchPrDescription('acme', 'widgets', 7)).toBe('Implements #7');
    expect(fetchMock).toHaveBeenCalledWith('/api/reviews/acme/widgets/7/description');
  });

  /** A repo or branch name can contain characters that change a URL's meaning if unescaped. */
  it('encodes path segments', async () => {
    const fetchMock = ok({ items: [], contributingSources: [], missingSources: [] });
    vi.stubGlobal('fetch', fetchMock);

    await fetchReviewContext('acme corp', 'wid/gets', 7);

    expect(fetchMock).toHaveBeenCalledWith('/wk/review-context/acme%20corp/wid%2Fgets/7');
  });
});

import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  acceptPromptDefault, fetchPrompt, fetchPrompts, fetchPromptScopes, previewPrompt, resetPrompt, savePrompt,
} from './api';

afterEach(() => vi.unstubAllGlobals());

// See api.contextsection.test.ts for why the script marker is asserted rather than ignored.
const scripted = { 'X-Requested-With': 'JavaScript' };

const view = {
  kind: 'review', scope: '*', inheritedFrom: 'default', customized: false, system: '', body: '',
  updatedAt: null, palette: [], lockedSuffixPreview: '', baseKnown: true, defaultDrifted: false,
  currentDefaultSystem: '', currentDefaultBody: '', baseSystem: null, baseBody: null,
};

const ok = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body } as Response);

/**
 * Every prompt endpoint now takes a `scope`, threaded onto every api.ts function as an optional
 * trailing argument defaulting to GLOBAL_SCOPE ('*'). Each of these functions is always mocked
 * (`vi.spyOn(api, ...)`) in the component tests, so the actual `?scope=` query-string construction
 * inside api.ts is invisible to them -- these tests are the only ones that can catch a dropped or
 * mis-encoded scope parameter.
 */
describe('prompt api scope threading', () => {
  it('fetchPrompts defaults to global and threads an explicit scope', async () => {
    const fetchMock = ok([view]);
    vi.stubGlobal('fetch', fetchMock);

    await fetchPrompts();
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts?scope=*', { headers: scripted });

    await fetchPrompts('acme/widgets');
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts?scope=acme%2Fwidgets', { headers: scripted });
  });

  it('fetchPrompt defaults to global and threads an explicit scope', async () => {
    const fetchMock = ok(view);
    vi.stubGlobal('fetch', fetchMock);

    await fetchPrompt('review');
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts/review?scope=*', { headers: scripted });

    await fetchPrompt('review', 'acme/widgets');
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts/review?scope=acme%2Fwidgets', { headers: scripted });
  });

  it('fetchPromptScopes reads the deployment-wide scope list (no scope of its own)', async () => {
    const fetchMock = ok(['acme/widgets']);
    vi.stubGlobal('fetch', fetchMock);

    await fetchPromptScopes();
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts/scopes', { headers: scripted });
  });

  it('savePrompt threads scope alongside the system/body payload', async () => {
    const fetchMock = ok(view);
    vi.stubGlobal('fetch', fetchMock);

    await savePrompt('review', 'sys', 'body', 'acme/widgets');
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts/review?scope=acme%2Fwidgets', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...scripted },
      body: JSON.stringify({ system: 'sys', body: 'body' }),
    });
  });

  it('resetPrompt threads scope', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204 } as Response);
    vi.stubGlobal('fetch', fetchMock);

    await resetPrompt('review', 'acme/widgets');
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts/review?scope=acme%2Fwidgets', {
      method: 'DELETE', headers: scripted,
    });
  });

  it('acceptPromptDefault threads scope', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204 } as Response);
    vi.stubGlobal('fetch', fetchMock);

    await acceptPromptDefault('review', 'acme/widgets');
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts/review/accept-default?scope=acme%2Fwidgets', {
      method: 'POST', headers: scripted,
    });
  });

  it('previewPrompt threads scope after its existing trailing reviewId argument', async () => {
    const fetchMock = ok({ system: '', user: '', errors: [], sampleReviewId: null, unavailableReason: null });
    vi.stubGlobal('fetch', fetchMock);

    await previewPrompt('review', 'sys', 'body', undefined, 'acme/widgets');
    expect(fetchMock).toHaveBeenCalledWith('/api/prompts/review/preview?scope=acme%2Fwidgets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...scripted },
      body: JSON.stringify({ system: 'sys', body: 'body', reviewId: undefined }),
    });
  });
});

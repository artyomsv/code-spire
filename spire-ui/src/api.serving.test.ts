import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchServingAccounts } from './api';

afterEach(() => vi.unstubAllGlobals());

// See api.contextsection.test.ts for why the script marker is asserted rather than ignored.
const scripted = { headers: { 'X-Requested-With': 'JavaScript' } };

const accounts = {
  type: 'github',
  workspace: 'TEST-group/sub',
  reviewer: { state: 'missing', id: null, name: null, botUsername: null, botAccountId: null },
  factory: { state: 'missing', id: null, name: null, botUsername: null, botAccountId: null },
};

const ok = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body } as Response);

/**
 * `fetchServingAccounts` is mocked in every component test that renders a serving chip, so the
 * query-string construction inside api.ts is invisible to them — this is the only place a dropped
 * or mis-encoded parameter can be caught.
 */
describe('serving accounts api', () => {
  /**
   * A GitLab workspace is a group path, and a subgroup puts a slash inside one parameter. Unescaped
   * it reads as another path segment and the route no longer matches, so the owner here is nested.
   */
  it('sends the forge type and the workspace as encoded query parameters', async () => {
    const fetchMock = ok(accounts);
    vi.stubGlobal('fetch', fetchMock);

    await fetchServingAccounts('github', 'TEST-group/sub');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/providers/serving?type=github&workspace=TEST-group%2Fsub',
      scripted,
    );
  });

  /**
   * The Repositories screen asks this once per workspace on every load. A refusal must arrive as a
   * rejection carrying the endpoint's own name, not a resolved value the chip would render as fact.
   */
  it('rejects with the endpoint’s message when the response is not ok', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        headers: new Headers({ 'content-type': 'text/html' }),
        text: async () => '<html>error</html>',
      } as Response),
    );

    await expect(fetchServingAccounts('github', 'TEST-acme')).rejects.toThrow(
      /Failed to load the accounts serving this workspace/,
    );
  });
});

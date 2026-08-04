import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from './App';

/**
 * `App` is composition — a rail, a topbar and a `Routes` table — so this covers the one piece of
 * logic it actually owns: the topbar title, computed from the pathname through an eight-branch
 * ternary. A route added to the table without a matching title branch silently renders "Reviews",
 * which looks like a working page on the wrong screen rather than like a bug.
 *
 * It doubles as a smoke test: every route here has to mount its screen without throwing.
 */

/**
 * jsdom has no WebSocket, and App opens three (the live reviews list plus the attention panel's two
 * feeds). This stub only has to exist and stay quiet — unlike the harnesses in `useLiveReviews` and
 * `AttentionBell`, nothing here pushes a frame, so it deliberately does not grow their fixture APIs.
 */
class SilentSocket {
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public url: string) {}
  close() {}
}

/**
 * Self-labelling fixture (TEST-* throughout) for the one route that needs a real object rather than
 * an empty list: the detail screen renders a six-step pipeline from `stages`, so an empty payload
 * throws rather than degrading. Kept minimal — only what the shell needs to mount.
 */
const detail = {
  id: 'review::TEST-WS/TEST-REPO#1',
  workspace: 'TEST-WS',
  slug: 'TEST-REPO',
  repo: 'TEST-REPO',
  pr: 1,
  title: 'TEST fixture pull request',
  author: 'TEST-AUTHOR',
  authorId: 'TEST-0',
  branch: 'TEST-branch',
  base: 'main',
  sha: 'TESTSHA00000',
  htmlUrl: 'https://example.invalid/TEST-WS/TEST-REPO/pull/1',
  providerType: 'github',
  prState: 'OPEN',
  status: 'completed',
  stage: 5,
  findings: 0,
  blockerCount: 0,
  costMillicents: 0,
  model: '',
  llmType: '',
  updatedAt: '2026-08-02T00:00:00Z',
  openFindings: 0,
  openBlockers: 0,
  attempt: 1,
  stages: ['done', 'done', 'done', 'done', 'done', 'done'],
  timings: ['', '', '', '', '', ''],
  findingsList: [],
  usage: null,
  llmCalls: [],
  note: null,
  errorDetail: null,
  events: [],
};

/**
 * Every screen loads through the same global fetch on an `/api/...` path, so one stub serves all of
 * them — but rendering the whole shell pulls in each child's own endpoint too, and a payload of the
 * wrong SHAPE crashes rather than degrading. An empty array is the right default (a list screen
 * renders its empty state; an object screen reads undefined fields and renders placeholders, while
 * the reverse default `{}` would crash any `.map`/`.filter`); the object-shaped endpoints the detail
 * screen and its cards need are named explicitly.
 */
/**
 * Who the shell thinks is signed in. Mutable because the rail and the settings routes are gated on
 * it: an admin sees the Configure section, a viewer must not, and both have to be renderable from
 * the same fixture. Defaults to an admin so the route table below covers every screen.
 */
const ADMIN_SESSION = {
  authEnabled: true,
  authenticated: true,
  user: 'dev-operator',
  roles: ['spire-admin', 'spire-viewer'],
};
const VIEWER_SESSION = { authEnabled: true, authenticated: true, user: 'dev-viewer', roles: ['spire-viewer'] };
let session: unknown = ADMIN_SESSION;

function payloadFor(url: string): unknown {
  if (/\/api\/me$/.test(url)) return session;
  // The worker owns /wk — its own prefix, so its session cookie never reaches the other services.
  if (/\/wk\/review-context\//.test(url)) {
    return { items: [], contributingSources: [], missingSources: [] };
  }
  if (/\/description$/.test(url)) return { description: null };
  if (/\/api\/reviews\/[^/]+\/[^/]+\/\d+$/.test(url)) return detail;
  return [];
}

const jsonResponse = (payload: unknown) => ({
  ok: true,
  status: 200,
  json: async () => payload,
  text: async () => JSON.stringify(payload),
});

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );

const ROUTES: ReadonlyArray<{ path: string; title: string; nav: string }> = [
  { path: '/', title: 'Reviews', nav: 'Reviews' },
  { path: '/settings/general', title: 'General', nav: 'General' },
  { path: '/settings/context', title: 'Context', nav: 'Context' },
  { path: '/settings/providers', title: 'Repositories', nav: 'Repositories' },
  { path: '/settings/webhooks', title: 'Webhooks', nav: 'Webhooks' },
  { path: '/settings/llm', title: 'LLM', nav: 'LLM' },
  { path: '/settings/prompts', title: 'Prompts', nav: 'Prompts' },
  { path: '/settings/dlq', title: 'Dead-letter', nav: 'Dead-letter' },
];

describe('App — routing shell', () => {
  beforeEach(() => {
    session = ADMIN_SESSION;
    vi.stubGlobal('WebSocket', SilentSocket);
    // jsdom implements no media queries; the reviews list asks about reduced motion on mount.
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }),
    );
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(jsonResponse(payloadFor(url)))));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each(ROUTES)('renders $title at $path', async ({ path, title, nav }) => {
    renderAt(path);

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(title);
    // The rail marks exactly the screen being shown, so a mismatched title and highlight can't pass.
    const active = document.querySelector('.nav a.active');
    expect(active).toHaveTextContent(nav);
    // The title and the highlight are both derived from the pathname, so they would still look
    // right if the <Route> itself were missing and nothing mounted. Every screen roots at
    // `.content`, so this is what actually proves the route matched.
    expect(document.querySelector('main .content')).toBeInTheDocument();
  });

  /** The detail route carries params; its title is fixed rather than derived from the repo. */
  it('renders the detail title and screen for a review route', async () => {
    renderAt('/r/TEST-WS/TEST-REPO/1');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Review detail');
    await waitFor(() => expect(document.querySelector('main .content')).toBeInTheDocument());
    expect(await screen.findByText('TEST fixture pull request')).toBeInTheDocument();
  });

  /**
   * Registering from a settings screen has to land the operator on the reviews list, or the review
   * they just created is created invisibly — the dialog closes onto the page they were already on.
   */
  it('navigates to the reviews list when a PR is registered from a settings screen', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ reviewId: 'TEST-review-1', workspace: 'TEST-WS', slug: 'TEST-REPO', pr: 1 }),
        text: async () => '{}',
      }),
    );
    renderAt('/settings/providers');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Repositories');

    fireEvent.click(screen.getByRole('button', { name: /register pr/i }));
    const dialog = await screen.findByRole('dialog');
    const form = within(dialog);
    fireEvent.change(form.getByLabelText('Workspace'), { target: { value: 'TEST-WS' } });
    fireEvent.change(form.getByLabelText('Repository'), { target: { value: 'TEST-REPO' } });
    fireEvent.change(form.getByLabelText(/pr #/i), { target: { value: '1' } });
    fireEvent.click(form.getByRole('button', { name: /^register$/i }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Reviews'),
    );
  });
});

/**
 * A viewer operates the dashboard; they do not see how it is wired. The API is the authority and
 * refuses every configuration read with a 403 — these cover the interface's side of that, which is
 * not to leave a viewer looking at controls and pages that can only fail.
 */
describe('App — what a viewer may see', () => {
  beforeEach(() => {
    vi.stubGlobal('WebSocket', SilentSocket);
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }),
    );
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(jsonResponse(payloadFor(url)))));
    session = VIEWER_SESSION;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const rail = () => document.querySelector('.nav') as HTMLElement;

  it('shows Reviews but not the Configure section', async () => {
    renderAt('/');

    // Wait on the section going away rather than on its absence: `admin` starts true while /api/me
    // is in flight, so asserting immediately would pass against the pre-answer render and never
    // exercise the gate at all.
    await waitFor(() => expect(within(rail()).queryByText('Configure')).not.toBeInTheDocument());
    expect(within(rail()).getByText('Reviews')).toBeInTheDocument();
    for (const label of ['General', 'Context', 'Repositories', 'Webhooks', 'LLM', 'Prompts', 'Dead-letter']) {
      expect(within(rail()).queryByText(label)).not.toBeInTheDocument();
    }
  });

  /** Spending money is admin, and so is the mode switch — its GET is refused too, so it cannot render a state. */
  it('offers neither Register PR nor the review-mode toggle', async () => {
    renderAt('/');

    // Wait for the RAIL, not for the absence. The shell renders a placeholder until the session
    // arrives, so asserting straight away would pass against a screen with nothing on it at all.
    await waitFor(() => expect(rail()).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /register pr/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /observe|active/i })).not.toBeInTheDocument();
  });

  /**
   * The nav is a courtesy; the routes are the reachable surface. A bookmark, a pasted link or the
   * back button arrives at a settings path without ever passing the rail. Said plainly rather than
   * redirected: a silent bounce to another screen is indistinguishable from a broken link.
   */
  it.each(['/settings/general', '/settings/providers', '/settings/llm', '/settings/dlq'])(
    'tells a viewer at %s that the page is not theirs',
    async (path) => {
      renderAt(path);

      expect(await screen.findByText('Not available to your role')).toBeInTheDocument();
      // The screen itself must not have mounted — a guard that renders the page and then covers it
      // has already fired its requests.
      expect(screen.queryByRole('button', { name: /add provider/i })).not.toBeInTheDocument();
    },
  );

  /** The same paths still render for an admin — so the guard is a boundary and not a blanket. */
  it('still renders a configuration screen for an admin', async () => {
    session = ADMIN_SESSION;
    renderAt('/settings/llm');

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('LLM');
    await waitFor(() => expect(within(rail()).getByText('Configure')).toBeInTheDocument());
    expect(screen.queryByText('Not available to your role')).not.toBeInTheDocument();
  });
});

/**
 * The regression this exists for: with `/api/me` unanswered, the shell used to render as though the
 * operator were an admin and withdraw it all a fraction of a second later — so a viewer saw, and
 * could click, every administrative control. Nothing privileged may appear before the role is known,
 * for anybody.
 */
describe('App — before the session is known', () => {
  beforeEach(() => {
    vi.stubGlobal('WebSocket', SilentSocket);
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }),
    );
    // /api/me never settles, holding the shell in its unknown state for the whole test. Every other
    // endpoint answers normally, so anything privileged that renders here did so on an assumption.
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        /\/api\/me$/.test(url) ? new Promise(() => {}) : Promise.resolve(jsonResponse(payloadFor(url))),
      ),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders no dashboard at all, privileged or otherwise', async () => {
    renderAt('/');

    // Not "the admin controls are hidden" but "there is no shell yet". The previous behaviour
    // rendered the whole dashboard here and corrected itself once the answer arrived, which is what
    // made an unauthenticated visit flash the interface before jumping to the identity provider.
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    expect(document.querySelector('.nav')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /register pr/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /observe|active/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  });

  /**
   * The reported glitch: an unauthenticated visit showed the dashboard for a fraction of a second and
   * then jumped to the identity provider. `/api/me` had already said a login was needed — nothing acted
   * on it, so the redirect waited for a REST call to be refused or a socket to close, whichever lost.
   */
  it('shows no dashboard and heads for a login when nobody is signed in', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve(
          jsonResponse(/\/api\/me$/.test(url) ? { authEnabled: true, authenticated: false, user: '', roles: [] } : payloadFor(url)),
        ),
      ),
    );

    renderAt('/');

    await waitFor(() => expect(assign).toHaveBeenCalledWith('/api/auth/login'));
    expect(screen.getByText('Signing in…')).toBeInTheDocument();
    expect(document.querySelector('.nav')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  });

  /** A guarded route waits rather than guessing in either direction — no page, and no accusation. */
  it('neither renders nor refuses a configuration screen until the role is known', async () => {
    renderAt('/settings/llm');

    await waitFor(() => expect(screen.getByText('Loading…')).toBeInTheDocument());
    expect(screen.queryByText('Not available to your role')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /add provider/i })).not.toBeInTheDocument();
  });
});

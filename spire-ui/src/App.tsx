import { useEffect, useState, type ReactElement } from 'react';
import { Route, Routes, useLocation, useNavigate } from 'react-router';
import { FileText, GitPullRequest, LogOut } from 'lucide-react';
import Tooltip from './components/Tooltip';
import AttentionBell from './components/AttentionBell';
import ReviewsList from './components/ReviewsList';
import ReviewDetail from './components/ReviewDetail';
import RegisterPrDialog from './components/RegisterPrDialog';
import ReviewModeToggle from './components/ReviewModeToggle';
import SettingsGeneral from './components/SettingsGeneral';
import SettingsProviders from './components/SettingsProviders';
import SettingsLlmProviders from './components/SettingsLlmProviders';
import SettingsContextProviders from './components/SettingsContextProviders';
import SettingsWebhookRepos from './components/SettingsWebhookRepos';
import SettingsDlq from './components/SettingsDlq';
import PromptsSettings from './components/PromptsSettings';
import PromptDetail from './components/PromptDetail';
import RequireRole from './components/RequireRole';
import { useLiveReviews } from './useLiveReviews';
import { useMe } from './hooks/useMe';
import { canAdminister, ensureServiceSessions, goToFullLogin, goToLogout, needsLogin } from './auth';

function toggleTheme() {
  const root = document.documentElement;
  const cur =
    root.getAttribute('data-theme') ||
    (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  root.setAttribute('data-theme', cur === 'dark' ? 'light' : 'dark');
}

export default function App() {
  const { reviews, loading, error } = useLiveReviews();
  const location = useLocation();
  const navigate = useNavigate();
  const onGeneral = location.pathname.startsWith('/settings/general');
  const onProviders = location.pathname.startsWith('/settings/providers');
  const onLlm = location.pathname.startsWith('/settings/llm');
  const onContext = location.pathname.startsWith('/settings/context');
  const onWebhooks = location.pathname.startsWith('/settings/webhooks');
  const onDlq = location.pathname.startsWith('/settings/dlq');
  const onPrompts = location.pathname.startsWith('/settings/prompts');
  const onSettings = onGeneral || onProviders || onLlm || onContext || onWebhooks || onDlq || onPrompts;
  const onReviews = location.pathname === '/';
  const title = location.pathname.startsWith('/r/')
    ? 'Review detail'
    : onGeneral
      ? 'General'
      : onProviders
        ? 'Repositories'
        : onLlm
          ? 'LLM'
          : onContext
            ? 'Context'
            : onWebhooks
              ? 'Webhooks'
              : onDlq
                ? 'Dead-letter'
                : onPrompts
                  ? 'Prompts'
                  : 'Reviews';
  const { me, loading: sessionLoading } = useMe();
  /**
   * Go to a login as soon as `/api/me` says one is needed — the answer the app already has.
   *
   * Previously nothing acted on it: the redirect happened when a REST call was refused or a socket
   * closed, whichever lost first. So an unauthenticated visit rendered the whole dashboard and then
   * jumped to the identity provider a fraction of a second later, which reads as the application
   * having second thoughts. The session state was known before any of that; it simply was not used.
   */
  useEffect(() => {
    if (needsLogin(me)) goToFullLogin();
  }, [me]);

  /**
   * Obtain the gateway's and the worker's sessions as soon as we know we have our own.
   *
   * Each service is a separate OIDC client with a cookie scoped to its own prefix (ADR-022), so
   * signing in to the dashboard mints `/api` and nothing else. Every call to `/gw` or `/wk` then
   * answered with a redirect that `fetch` cannot follow — the Webhooks screen reported "failed to
   * fetch", a review's Context card failed alone on an otherwise working page, and the attention
   * socket declared the gateway down. Done here, once, rather than on first use: the attention panel
   * opens its gateway socket on every page, so first use is immediately.
   */
  useEffect(() => {
    if (!me?.authEnabled || !me.authenticated) return;
    void ensureServiceSessions();
  }, [me]);
  /**
   * False until the session is known, not true. Every admin control below is gated on this, so the
   * shell opens with nothing privileged offered and adds it once the role is in hand — never the
   * reverse. Showing the admin surface to a viewer for the ~200ms before `/api/me` answers, then
   * withdrawing it, looks like the application malfunctioning.
   */
  const admin = canAdminister(me);
  const [registerOpen, setRegisterOpen] = useState(false);

  /**
   * A configuration screen behind its role. Hiding the nav is not enough on its own — a bookmark, a
   * pasted link or the back button reaches a route without ever passing the rail — and every one of
   * these screens loads by calling an endpoint a viewer is refused, so unguarded they would render as
   * a page of failed requests. {@link RequireRole} owns the three-state logic; this is only shorthand
   * for the eight routes that share the same requirement.
   */
  const configure = (screen: ReactElement) => <RequireRole role="spire-admin">{screen}</RequireRole>;

  /**
   * Nothing of the dashboard until we know whose it is.
   *
   * Every hook above has already run, so this is a render decision only — the early return is what
   * stops protected content existing on screen before the session is established. Two states share
   * it: the answer has not arrived, and the answer was "log in first" (the effect above is already
   * navigating). Rendering the shell in either case is what produced the flash.
   */
  if (sessionLoading || needsLogin(me)) {
    return (
      <div className="app">
        <main className="main">
          <section className="content">
            <div style={{ color: 'var(--text-3)', fontSize: 13 }}>
              {needsLogin(me) ? 'Signing in…' : 'Loading…'}
            </div>
          </section>
        </main>
      </div>
    );
  }

  return (
    <div className="app">
      <aside className="rail">
        <div className="brand">
          <svg width="22" height="24" viewBox="0 0 22 24" fill="none" aria-hidden="true">
            <path d="M11 1 L15 15 L11 13 L7 15 Z" fill="var(--iris)" />
            <path d="M11 13 L15 15 L11 23 L7 15 Z" fill="var(--iris)" opacity="0.45" />
          </svg>
          <span className="word">
            code<b>·</b>spire
          </span>
        </div>
        <nav className="nav">
          <div className="label">Operate</div>
          <a className={onSettings ? '' : 'active'} href="#/">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <rect x="1.5" y="2.5" width="13" height="3" rx="1" stroke="currentColor" strokeWidth="1.4" />
              <rect x="1.5" y="7" width="13" height="3" rx="1" stroke="currentColor" strokeWidth="1.4" />
              <rect x="1.5" y="11.5" width="13" height="2.5" rx="1" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Reviews
          </a>
          {/* Configuration is admin-only in full, reads included — the registries describe every
              repository, inference endpoint and context source this deployment reaches. A viewer
              gets Reviews and nothing else here. Hidden rather than shown-and-refused: the API
              answers 403 regardless of what is rendered, so a nav entry that can only lead to a
              refusal is noise. */}
          {admin && (
          <>
          <div className="label">Configure</div>
          <a className={onGeneral ? 'active' : ''} href="#/settings/general">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <path d="M2 4h9M2 8h12M2 12h6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
              <circle cx="13" cy="4" r="1.6" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="9" cy="12" r="1.6" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            General
          </a>
          <a className={onContext ? 'active' : ''} href="#/settings/context">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <ellipse cx="8" cy="3.6" rx="5" ry="2.1" stroke="currentColor" strokeWidth="1.4" />
              <path d="M3 3.6v8.8c0 1.16 2.24 2.1 5 2.1s5-.94 5-2.1V3.6" stroke="currentColor" strokeWidth="1.4" />
              <path d="M3 8c0 1.16 2.24 2.1 5 2.1s5-.94 5-2.1" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Context
          </a>
          <a className={onProviders ? 'active' : ''} href="#/settings/providers">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <circle cx="4" cy="3.5" r="1.9" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="4" cy="12.5" r="1.9" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="12" cy="3.5" r="1.9" stroke="currentColor" strokeWidth="1.4" />
              <path d="M4 5.4v5.2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
              <path d="M12 5.4c0 3.4-3.5 3.6-6 5.1" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
            </svg>
            Repositories
          </a>
          <a className={onWebhooks ? 'active' : ''} href="#/settings/webhooks">
            <svg className="ic" viewBox="0 0 24 24" fill="none">
              <path
                d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"
                stroke="currentColor"
                strokeWidth="2.1"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              <path
                d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"
                stroke="currentColor"
                strokeWidth="2.1"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            Webhooks
          </a>
          <a className={onLlm ? 'active' : ''} href="#/settings/llm">
            <svg
              className="ic"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.1"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M12 8V4H8" />
              <rect width="16" height="12" x="4" y="8" rx="2" />
              <path d="M2 14h2" />
              <path d="M20 14h2" />
              <path d="M15 13v2" />
              <path d="M9 13v2" />
            </svg>
            LLM
          </a>
          <a className={onPrompts ? 'active' : ''} href="#/settings/prompts">
            <FileText className="ic" />
            Prompts
          </a>
          {/* A dead-letter row carries the raw payload of the message that failed — a wire record
              quoting source or carrying a brokered credential. It was admin-only before the rest of
              this section was, and for a second reason: not merely configuration, but disclosure. */}
          <a className={onDlq ? 'active' : ''} href="#/settings/dlq">
            <svg
              className="ic"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.4"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <rect x="2" y="2" width="12" height="12" rx="2.5" />
              <path d="M8 5.4v3.4" />
              <circle cx="8" cy="11" r="0.8" fill="currentColor" stroke="none" />
            </svg>
            Dead-letter
          </a>
          </>
          )}
        </nav>
        <div className="spacer"></div>
        {/* Reads as well as writes: the mode endpoint is admin-only, so for a viewer this control
            could only render an error where a state belongs. */}
        {admin && <ReviewModeToggle />}
        <div className="foot">
          spire-orchestrator
          <br />
          <span className="mono">:34080</span> · connected
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <h1 id="topTitle">{title}</h1>
          <span className="live" id="liveBadge">
            <span className="dot"></span>LIVE
          </span>
          <div className="grow"></div>
          {/* Registering a pull request starts a review, which spends money — admin only. Hidden
              rather than disabled: a control that is always refused is noise, and the API is still
              the authority (it answers 403 regardless of what is rendered). */}
          {admin && (
            <Tooltip label="Register PR">
              <button className="iconbtn pr" aria-label="Register PR" onClick={() => setRegisterOpen(true)}>
                <GitPullRequest size={17} />
              </button>
            </Tooltip>
          )}
          <AttentionBell />
          {me?.authEnabled && me.authenticated && (
            <Tooltip label={`Sign out ${me.user}`}>
              <button className="iconbtn" aria-label={`Sign out ${me.user}`} onClick={goToLogout}>
                <LogOut size={16} />
              </button>
            </Tooltip>
          )}
          <Tooltip label="Toggle theme">
            <button className="iconbtn" id="themeBtn" aria-label="Toggle theme" onClick={toggleTheme}>
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path
                  d="M13 9.5A5.5 5.5 0 0 1 6.5 3a5.5 5.5 0 1 0 6.5 6.5Z"
                  stroke="currentColor"
                  strokeWidth="1.3"
                  fill="none"
                />
              </svg>
            </button>
          </Tooltip>
        </header>

        <Routes>
          <Route path="/" element={<ReviewsList reviews={reviews} loading={loading} error={error} />} />
          <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={reviews} />} />
          <Route path="/settings/general" element={configure(<SettingsGeneral />)} />
          <Route path="/settings/providers" element={configure(<SettingsProviders />)} />
          <Route path="/settings/webhooks" element={configure(<SettingsWebhookRepos />)} />
          <Route path="/settings/llm" element={configure(<SettingsLlmProviders />)} />
          <Route path="/settings/context" element={configure(<SettingsContextProviders />)} />
          <Route path="/settings/prompts" element={configure(<PromptsSettings />)} />
          <Route path="/settings/prompts/:kind" element={configure(<PromptDetail />)} />
          <Route path="/settings/dlq" element={configure(<SettingsDlq />)} />
        </Routes>
      </main>

      {registerOpen && (
        <RegisterPrDialog
          onClose={() => setRegisterOpen(false)}
          onRegistered={() => {
            // Registering from anywhere but the Reviews list — jump there so the
            // new review is visible in the live list once the dialog closes.
            if (!onReviews) navigate('/');
          }}
        />
      )}
    </div>
  );
}

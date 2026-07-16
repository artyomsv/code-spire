import { useState } from 'react';
import { Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { GitPullRequest } from 'lucide-react';
import Tooltip from './components/Tooltip';
import ReviewsList from './components/ReviewsList';
import ReviewDetail from './components/ReviewDetail';
import RegisterPrDialog from './components/RegisterPrDialog';
import ReviewModeToggle from './components/ReviewModeToggle';
import SettingsGeneral from './components/SettingsGeneral';
import SettingsProviders from './components/SettingsProviders';
import SettingsLlmProviders from './components/SettingsLlmProviders';
import SettingsContextProviders from './components/SettingsContextProviders';
import SettingsWebhookRepos from './components/SettingsWebhookRepos';
import { useLiveReviews } from './useLiveReviews';

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
  const onSettings = onGeneral || onProviders || onLlm || onContext || onWebhooks;
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
              : 'Reviews';
  const [registerOpen, setRegisterOpen] = useState(false);

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
        </nav>
        <div className="spacer"></div>
        <ReviewModeToggle />
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
          <Tooltip label="Register PR">
            <button className="iconbtn pr" aria-label="Register PR" onClick={() => setRegisterOpen(true)}>
              <GitPullRequest size={17} />
            </button>
          </Tooltip>
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
          <Route path="/settings/general" element={<SettingsGeneral />} />
          <Route path="/settings/providers" element={<SettingsProviders />} />
          <Route path="/settings/webhooks" element={<SettingsWebhookRepos />} />
          <Route path="/settings/llm" element={<SettingsLlmProviders />} />
          <Route path="/settings/context" element={<SettingsContextProviders />} />
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

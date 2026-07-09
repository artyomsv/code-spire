import { useState } from 'react';
import { Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { GitPullRequest } from 'lucide-react';
import Tooltip from './components/Tooltip';
import ReviewsList from './components/ReviewsList';
import ReviewDetail from './components/ReviewDetail';
import RegisterPrDialog from './components/RegisterPrDialog';
import ReviewModeToggle from './components/ReviewModeToggle';
import SettingsProviders from './components/SettingsProviders';
import SettingsLlmProviders from './components/SettingsLlmProviders';
import SettingsContextProviders from './components/SettingsContextProviders';
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
  const onProviders = location.pathname.startsWith('/settings/providers');
  const onLlm = location.pathname.startsWith('/settings/llm');
  const onContext = location.pathname.startsWith('/settings/context');
  const onSettings = onProviders || onLlm || onContext;
  const onReviews = location.pathname === '/';
  const title = location.pathname.startsWith('/r/')
    ? 'Review detail'
    : onProviders
      ? 'Repositories'
      : onLlm
        ? 'LLM'
        : onContext
          ? 'Context'
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
          <a className={onLlm ? 'active' : ''} href="#/settings/llm">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <path
                d="M7.6 2.2 8.7 5.4 11.9 6.5 8.7 7.6 7.6 10.8 6.5 7.6 3.3 6.5 6.5 5.4z"
                stroke="currentColor"
                strokeWidth="1.3"
                strokeLinejoin="round"
              />
              <path
                d="M12 9.6 12.55 11.05 14 11.6 12.55 12.15 12 13.6 11.45 12.15 10 11.6 11.45 11.05z"
                stroke="currentColor"
                strokeWidth="1.1"
                strokeLinejoin="round"
              />
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
          <Route path="/settings/providers" element={<SettingsProviders />} />
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

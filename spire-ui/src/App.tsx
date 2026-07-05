import { Route, Routes, useLocation } from 'react-router-dom';
import ReviewsList from './components/ReviewsList';
import ReviewDetail from './components/ReviewDetail';
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
  const title = location.pathname.startsWith('/r/') ? 'Review detail' : 'Reviews';

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
          <a className="active">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <rect x="1.5" y="2.5" width="13" height="3" rx="1" stroke="currentColor" strokeWidth="1.4" />
              <rect x="1.5" y="7" width="13" height="3" rx="1" stroke="currentColor" strokeWidth="1.4" />
              <rect x="1.5" y="11.5" width="13" height="2.5" rx="1" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Reviews
          </a>
          <a className="disabled">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <path d="M2 8h12M8 2v12" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Dead letters<span className="tag">P2</span>
          </a>
          <a className="disabled">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Repositories<span className="tag">P2</span>
          </a>
          <div className="label">Configure</div>
          <a className="disabled">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <circle cx="8" cy="8" r="2.4" stroke="currentColor" strokeWidth="1.4" />
              <path d="M8 1.5v2M8 12.5v2M14.5 8h-2M3.5 8h-2" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Rules &amp; context<span className="tag">P2</span>
          </a>
          <a className="disabled">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <path d="M4 8l3 3 5-6" stroke="currentColor" strokeWidth="1.4" fill="none" />
            </svg>
            Providers<span className="tag">P2</span>
          </a>
        </nav>
        <div className="spacer"></div>
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
          <button className="iconbtn" id="themeBtn" title="Toggle theme" aria-label="Toggle theme" onClick={toggleTheme}>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path
                d="M13 9.5A5.5 5.5 0 0 1 6.5 3a5.5 5.5 0 1 0 6.5 6.5Z"
                stroke="currentColor"
                strokeWidth="1.3"
                fill="none"
              />
            </svg>
          </button>
        </header>

        <Routes>
          <Route path="/" element={<ReviewsList reviews={reviews} loading={loading} error={error} />} />
          <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={reviews} />} />
        </Routes>
      </main>
    </div>
  );
}

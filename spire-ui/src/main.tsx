import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { createQueryClient } from './queryClient';
import { recordPageLoad } from './authLog';
import './index.css';

// Before anything renders: a reload that nothing in the app asked for (F5, the dev server, the
// browser) shows up in the sign-in log only as a load with no login before it.
const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
recordPageLoad(navigation?.type ?? 'unknown', document.referrer);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={createQueryClient()}>
      <HashRouter>
        <App />
      </HashRouter>
    </QueryClientProvider>
  </StrictMode>,
);

import { QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { createQueryClient } from '../queryClient';

/**
 * A `render` wrapper with a fresh query cache. Pass it as `{ wrapper: queryWrapper() }`: a wrapper survives
 * `rerender`, so the cache does too, and a new call gives the next test an empty one.
 */
export function queryWrapper() {
  const client = createQueryClient();
  return function QueryWrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

/** `render` inside a fresh query cache, unless `options.wrapper` brings one to share. */
export function renderWithQuery(ui: ReactElement, options: RenderOptions = {}) {
  return render(ui, { wrapper: queryWrapper(), ...options });
}

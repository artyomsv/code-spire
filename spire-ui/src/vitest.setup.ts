// Extends vitest's `expect` with DOM matchers (toBeInTheDocument, etc.) for component tests
// rendered into jsdom via @testing-library/react. Lives under src/ (not the project root) so
// the ambient `Assertion` augmentation is part of the tsconfig "include" set `tsc --noEmit` checks.
import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// jsdom implements no media queries at all, so a component that asks about one throws unless
// something supplies `matchMedia` — the reviews list checks prefers-reduced-motion on mount.
//
// Supplying it HERE, rather than only per-test, is what makes it reliable. A test that stubs it in
// `beforeEach` and drops it in `afterEach` is racing React: mount effects are PASSIVE and flush
// asynchronously, and vitest runs a file's own `afterEach` before this one — so `unstubAllGlobals`
// can remove the stub while a component is still mounted and `cleanup()` below has yet to unmount
// it. A pending effect then lands on an undefined global. It fails only when the flush is slow
// enough to cross that boundary, which is why it showed up as an intermittent CI failure on
// branches that touch no UI code at all.
//
// Assigned directly instead of through `vi.stubGlobal` on purpose: `vi.unstubAllGlobals()` restores
// whatever was in place when a test stubbed over it, so this stays the floor beneath any per-test
// stub rather than being torn down with it.
window.matchMedia = (query: string): MediaQueryList => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => {},
  removeListener: () => {},
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => false,
});

// @testing-library/react's built-in auto-cleanup only registers when it finds a *global*
// afterEach (jest-style). This project runs vitest without `test.globals: true` (tests import
// afterEach/describe/it explicitly), so that auto-detection never fires — unmount explicitly or
// one test's rendered DOM leaks into the next test's `screen` queries.
//
// Mock state leaks the same way, one layer over. `vi.spyOn` re-wraps the *same* module function,
// so a second `spyOn` in a later test returns the existing spy with its call history intact: an
// `expect(create).not.toHaveBeenCalled()` then passes or fails on whether an EARLIER test in the
// file happened to call it, and `mock.calls[0]` may belong to a different test entirely. Restoring
// here makes each test's mock state its own, so a suite cannot silently depend on its own ordering.
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

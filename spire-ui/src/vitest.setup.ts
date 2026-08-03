// Extends vitest's `expect` with DOM matchers (toBeInTheDocument, etc.) for component tests
// rendered into jsdom via @testing-library/react. Lives under src/ (not the project root) so
// the ambient `Assertion` augmentation is part of the tsconfig "include" set `tsc --noEmit` checks.
import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

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

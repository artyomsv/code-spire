// Extends vitest's `expect` with DOM matchers (toBeInTheDocument, etc.) for component tests
// rendered into jsdom via @testing-library/react. Lives under src/ (not the project root) so
// the ambient `Assertion` augmentation is part of the tsconfig "include" set `tsc --noEmit` checks.
import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// @testing-library/react's built-in auto-cleanup only registers when it finds a *global*
// afterEach (jest-style). This project runs vitest without `test.globals: true` (tests import
// afterEach/describe/it explicitly), so that auto-detection never fires — unmount explicitly or
// one test's rendered DOM leaks into the next test's `screen` queries.
afterEach(() => {
  cleanup();
});

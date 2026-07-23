// Extends vitest's `expect` with DOM matchers (toBeInTheDocument, etc.) for component tests
// rendered into jsdom via @testing-library/react. Lives under src/ (not the project root) so
// the ambient `Assertion` augmentation is part of the tsconfig "include" set `tsc --noEmit` checks.
import '@testing-library/jest-dom/vitest';

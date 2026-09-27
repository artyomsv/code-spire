import { expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The LLM dialogs are taller than a laptop window: the model dialog ran off screen with its buttons half
 * hidden, and its price inputs sat at different heights (feedback, 2026-09-27). Layout cannot be measured
 * in jsdom, so this holds the two rules that fix it and the markup that uses them.
 */
const SRC = join(__dirname);
const read = (file: string) => readFileSync(join(SRC, file), 'utf8');

it('caps a tall dialog at the window and keeps its buttons in view', () => {
  const css = read('index.css');
  expect(css.length).toBeGreaterThan(0);
  expect(css).toMatch(/\.modal\.tall \{[^}]*max-height: calc\(100vh - 40px\)/);
  expect(css).toMatch(/\.modal\.tall > \.modal-body \{[^}]*overflow-y: auto/);
  expect(css).toMatch(/\.modal\.tall \.modal-actions \{[^}]*position: sticky/);
  expect(read('components/SettingsLlmModelForm.tsx')).toContain('className="modal tall"');
  expect(read('components/SettingsLlmProviders.tsx')).toContain('className="modal tall"');
});

it('lets the two price labels of a row share one height so the inputs line up', () => {
  const css = read('index.css');
  expect(css).toMatch(/\.rate-field \{[^}]*grid-row: span 3;[^}]*grid-template-rows: subgrid/);
  expect(css).toMatch(/\.rate-field > label\.field \{[^}]*display: contents/);
  expect(css).toMatch(/\.rate-label \{[^}]*align-self: end/);
  const fields = read('components/SettingsLlmModelRateFields.tsx');
  expect(fields).toContain('<div className="rate-grid">');
  expect(fields).toContain('<div className="rate-field">');
  expect(fields).toContain('<span className="rate-label">');
});

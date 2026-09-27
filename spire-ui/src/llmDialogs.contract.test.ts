import { expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The LLM dialogs are taller than a laptop window: the model dialog ran off screen with its buttons half
 * hidden, and its price inputs sat at different heights (feedback, 2026-09-27). The operator then chose a
 * two-pane model dialog with narrow price inputs. Layout cannot be measured in jsdom, so this holds the
 * rules and the markup that uses them.
 */
const SRC = join(__dirname);
const read = (file: string) => readFileSync(join(SRC, file), 'utf8');

it('caps a tall dialog at the window and keeps its buttons in view', () => {
  const css = read('index.css');
  expect(css.length).toBeGreaterThan(0);
  expect(css).toMatch(/\.modal\.tall \{[^}]*max-height: calc\(100vh - 40px\)/);
  expect(css).toMatch(/\.modal\.tall > \.modal-body \{[^}]*overflow-y: auto/);
  expect(css).toMatch(/\.modal\.tall \.modal-actions \{[^}]*position: sticky/);
  expect(read('components/SettingsLlmModelForm.tsx')).toContain('className="modal tall model-dialog"');
  expect(read('components/SettingsLlmProviders.tsx')).toContain('className="modal tall"');
});

it('puts the model and its prices side by side, with narrow price inputs', () => {
  const css = read('index.css');
  expect(css).toMatch(/\.modal\.model-dialog \{ max-width: 880px; \}/);
  expect(css).toMatch(/\.model-panes \{[^}]*grid-template-columns: minmax\(0, 1fr\) 300px/);
  expect(css).toMatch(/\.price-item \{[^}]*grid-template-columns: minmax\(0, 1fr\) 110px/);
  // On a narrow screen the prices pane moves under the model.
  expect(css).toMatch(/@media \(max-width: 760px\) \{\s*\.model-panes \{ grid-template-columns: 1fr; \}/);
  const form = read('components/SettingsLlmModelForm.tsx');
  expect(form).toContain('<div className="model-panes">');
  expect(form).toContain('<section className="model-prices" aria-label="Prices">');
  expect(read('components/SettingsLlmModelRateFields.tsx')).toContain('<div className="price-item">');
});

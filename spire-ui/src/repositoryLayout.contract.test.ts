import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, it } from 'vitest';

it('retains the operator requested webhook width and vertical serving layout', () => {
  const css = readFileSync(join(process.cwd(), 'src/index.css'), 'utf8');
  expect(css.length).toBeGreaterThan(0);
  const path = css.match(/\.wh-url\s*\{([^}]+)\}/)?.[1];
  const pair = css.match(/\.serving-pair\s*\{([^}]+)\}/)?.[1];
  expect(path).toBeDefined(); expect(pair).toBeDefined();
  expect(path).toMatch(/max-width:\s*180px/);
  expect(pair).toMatch(/flex-direction:\s*column/);
  expect(pair).toMatch(/align-items:\s*flex-start/);
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The list's severity badges are 11px numbers, so their text must reach 4.5:1 (WCAG AA for small text) in both
 * themes, on the panel and on a hovered row (PR #457, code-quality/M-4). The number once took the severity colour on
 * that colour's own tint and measured 3.6 to 4.4:1. This reads the stylesheet's own tokens and computes the ratios,
 * so a token change that drops one below the line fails here. The stylesheet is read with `node:fs`: Vite's `?raw`
 * import returns an empty string for CSS under vitest.
 */

const CSS = readFileSync(join(process.cwd(), 'src', 'index.css'), 'utf8');
const TOKEN = /--([a-z0-9-]+):\s*([^;]+);/g;
const SEVERITY_RULE = /\.sevbadge\.sev-([a-z]+) \{ --sv: var\(--([a-z-]+)\); --sv-soft: var\(--([a-z-]+)\); \}/g;
const RGBA = /^rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)$/;
const SMALL_TEXT = 4.5;

type Rgba = [number, number, number, number];

/** The `--name: value;` pairs of the first block that opens with `selector`. */
function tokensOf(selector: string): Map<string, string> {
  const start = CSS.indexOf(`${selector} {`);
  expect(start, `${selector} is in index.css`).toBeGreaterThanOrEqual(0);
  const block = CSS.slice(start, CSS.indexOf('}', start));
  return new Map([...block.matchAll(TOKEN)].map((m) => [m[1], m[2].trim()]));
}

function colourOf(value: string | undefined): Rgba {
  expect(value, 'the token is defined').toBeDefined();
  const text = value as string;
  if (text.startsWith('#')) return [1, 3, 5].map((i) => parseInt(text.slice(i, i + 2), 16)).concat(1) as Rgba;
  const m = RGBA.exec(text);
  expect(m, `${text} is a hex or rgba colour`).not.toBeNull();
  return [Number(m![1]), Number(m![2]), Number(m![3]), Number(m![4])];
}

/** `top` painted over the opaque `under`. */
function over(top: Rgba, under: Rgba): Rgba {
  const [r, g, b, a] = top;
  return [r * a + under[0] * (1 - a), g * a + under[1] * (1 - a), b * a + under[2] * (1 - a), 1];
}

function luminance([r, g, b]: Rgba): number {
  const channel = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function contrast(a: Rgba, b: Rgba): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}

const severityTints = [...CSS.matchAll(SEVERITY_RULE)].map((m) => ({ sev: m[1], soft: m[3] }));

describe('the severity badges keep readable numbers', () => {
  it('draws the number in the body text colour, and keeps the severity colour for the tint and the border', () => {
    expect(CSS).toContain('text-align: center; color: var(--text); background: var(--sv-soft);');
    expect(CSS).toContain('border: 1px solid color-mix(in srgb, var(--sv) 45%, transparent);');
    expect(severityTints.map((t) => t.sev)).toEqual(['critical', 'warning', 'suggestion', 'nit']);
  });

  for (const theme of ['light', 'dark']) {
    for (const base of ['panel', 'hover']) {
      it(`reaches ${SMALL_TEXT}:1 for every badge in the ${theme} theme on the ${base}`, () => {
        const tokens = tokensOf(`:root[data-theme="${theme}"]`);
        const ground = colourOf(tokens.get(base));
        for (const { sev, soft } of severityTints) {
          const ratio = contrast(colourOf(tokens.get('text')), over(colourOf(tokens.get(soft)), ground));
          expect(ratio, `${sev} on ${base}`).toBeGreaterThanOrEqual(SMALL_TEXT);
        }
        expect(contrast(colourOf(tokens.get('text-2')), ground), `+N on ${base}`).toBeGreaterThanOrEqual(SMALL_TEXT);
      });
    }
  }
});

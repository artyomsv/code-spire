import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Every class a component uses must exist in the stylesheet.
 *
 * <p>This exists because four screens shipped completely unstyled and a fully green suite said
 * nothing. They used `tiles`, `tile-value`, `plain-list`, `pref-card`, `row-actions`, `form-row`,
 * `table` and `error` — a whole invented vocabulary this project had never heard of — so the markup
 * fell back to browser defaults: stat values ran together as `0Findings`, tables had no borders and
 * the form was raw inputs on one line.
 *
 * <p><b>Why nothing caught it.</b> The UI tests assert text and behaviour — "does this say 14 of 16",
 * "does approving call the API". That is normally the right instinct, because style-coupled tests are
 * brittle. But it left nothing verifying the styles existed at all, which is the same blind spot as
 * the ADR-025 `refused` incident: a rendering fault invisible to every passing test.
 *
 * <p>Deliberately narrow. It does not assert that anything LOOKS right — only that a class a
 * component asks for is one the stylesheet defines. That is cheap, has no false positives for the
 * failure it targets, and would have failed loudly on the first commit.
 *
 * <p>The sources are read with `node:fs` rather than Vite's `import.meta.glob(…, '?raw')`, which
 * returns an EMPTY STRING for a stylesheet under vitest — the CSS pipeline is stubbed out in tests,
 * so the whole check would have passed while reading nothing.
 */

const SRC = join(process.cwd(), 'src');

/** Prefixes the app composes from data (`sev-${severity}`), so the literal never appears in source. */
const DYNAMIC_PREFIXES = ['sev-', 'llm-', 'prov-', 'pr-', 'chip-', 'conn-', 'rail-'];

function componentFiles(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) found.push(...componentFiles(path));
    else if (entry.name.endsWith('.tsx') && !entry.name.endsWith('.test.tsx')) found.push(path);
  }
  return found;
}

function definedClasses(): Set<string> {
  const css = readFileSync(join(SRC, 'index.css'), 'utf8');
  const found = new Set<string>();
  for (const match of css.matchAll(/\.([a-zA-Z][a-zA-Z0-9_-]*)/g)) found.add(match[1]);
  return found;
}

/** Every literal `className="…"` in the component tree, mapped to the files that use it. */
function usedClasses(files: string[]): Map<string, Set<string>> {
  const byClass = new Map<string, Set<string>>();
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const match of source.matchAll(/className="([^"{}]+)"/g)) {
      for (const name of match[1].split(/\s+/).filter(Boolean)) {
        byClass.set(name, (byClass.get(name) ?? new Set()).add(file.slice(SRC.length + 1)));
      }
    }
  }
  return byClass;
}

describe('stylesheet contract', () => {
  it('reads the sources it is meant to check', () => {
    // Without this the whole check passes vacuously the moment a read returns nothing -- the shape of
    // the ContractSchemaSnapshot hole, where iterating an empty list read as zero failures. It is not
    // hypothetical here: the first attempt read the stylesheet through Vite and got an empty string.
    expect(definedClasses().size).toBeGreaterThan(100);
    expect(componentFiles(SRC).length).toBeGreaterThan(20);
  });

  it('defines every class the components ask for', () => {
    const defined = definedClasses();
    const orphans: string[] = [];

    for (const [name, files] of usedClasses(componentFiles(SRC))) {
      if (defined.has(name)) continue;
      if (DYNAMIC_PREFIXES.some((prefix) => name.startsWith(prefix))) continue;
      orphans.push(`${name} (used in ${[...files].join(', ')})`);
    }

    expect(
      orphans,
      'These class names are used by a component and defined nowhere in index.css, so they render ' +
        'with browser defaults. Either add the rule or use an existing class.',
    ).toEqual([]);
  });
});

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

/** An interpolation, standing in for the value it will produce. One level of nesting is enough
 *  for the shapes this app writes (`${a ? 'x' : 'y'}`). */
const INTERPOLATION = /\$\{(?:[^{}]|\{[^{}]*\})*\}/g;

/**
 * Every class a component asks for, mapped to the files that use it — from a literal
 * `className="…"` and from the STATIC words of a `className={\`…\`}` template.
 *
 * <p>The template half was added because three classes this project shipped were invisible here:
 * `conn-badge` and `enabled-dot` are written in template literals beside a composed `conn-${state}`,
 * so a scan that read only quoted attributes never saw them, and both could have been deleted from
 * the stylesheet with the whole suite green — the exact failure this file exists to catch.
 *
 * <p>A word touching an interpolation is dropped: `conn-${state}` is a PREFIX and not a class name,
 * and reporting it would be a false alarm on every render-time class in the app. That is also why
 * there is no longer a list of prefixes to excuse: a composed name never reaches the check now, so
 * excusing whole families of names by their first syllable only hid the whole ones. It hid a real
 * one — `prov-error`, asked for twice by the General screen and defined nowhere.
 */
function usedClasses(files: string[]): Map<string, Set<string>> {
  const byClass = new Map<string, Set<string>>();
  const add = (name: string, file: string) =>
    byClass.set(name, (byClass.get(name) ?? new Set()).add(file.slice(SRC.length + 1)));
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const match of source.matchAll(/className="([^"{}]+)"/g)) {
      for (const name of match[1].split(/\s+/).filter(Boolean)) add(name, file);
    }
    for (const match of source.matchAll(/className=\{`([^`]*)`\}/g)) {
      // Mark where the values go, then keep only the words that touch none of them.
      for (const name of match[1].replace(INTERPOLATION, '\u0000').split(/\s+/).filter(Boolean)) {
        if (!name.includes('\u0000')) add(name, file);
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
      orphans.push(`${name} (used in ${[...files].join(', ')})`);
    }

    expect(
      orphans,
      'These class names are used by a component and defined nowhere in index.css, so they render ' +
        'with browser defaults. Either add the rule or use an existing class.',
    ).toEqual([]);
  });
});

import { expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { withoutBlockComments } from './test/sourceText';

const src = join(process.cwd(), 'src');
function read(file: string) {
  const text = withoutBlockComments(readFileSync(file, 'utf8'));
  expect(text.length, `Readable source: ${file}`).toBeGreaterThan(0);
  return text;
}
function imports(text: string, file: string) {
  const found = new Map<string, string>();
  for (const match of text.matchAll(/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]/g)) {
    if (!match[2].startsWith('.')) continue;
    const base = resolve(dirname(file), match[2]);
    const target = [base + '.tsx', join(base, 'index.tsx')].find(existsSync);
    if (target) for (const name of match[1].matchAll(/\b[A-Z]\w*\b/g)) found.set(name[0], target);
  }
  return found;
}

it('uses accountOptionLabel for account options on routed screens and their rendered children', () => {
  const app = join(src, 'App.tsx'), text = read(app), dependencies = imports(text, app);
  const routes = [...text.matchAll(/<Route\b[^>]*?path="([^"\s]+)"\s+element=\{\s*(?:configure\(\s*)?<([A-Z]\w*)\b/g)];
  expect(routes.length, 'Derive screens from actual App routes').toBeGreaterThan(0);
  const sources = new Map<string, string>();
  function visit(file: string) {
    if (sources.has(file)) return;
    const body = read(file); sources.set(file, body);
    const children = imports(body, file);
    for (const match of body.matchAll(/<([A-Z]\w*)\b/g)) {
      const child = children.get(match[1]); if (child) visit(child);
    }
  }
  for (const [, route, component] of routes) {
    // A redirect renders no screen of its own; the screen it points at is checked under its own route.
    if (component === 'RedirectKeepingQuery' || component === 'Navigate') continue;
    const target = dependencies.get(component);
    expect(target, `Readable route ${route}`).toBeDefined(); visit(target!);
  }
  let options = 0;
  const violations: string[] = [];
  for (const [file, body] of sources) {
    // ProviderView is a configured credential account. Observed SCM people (for example the
    // operator identity-link picker) are not credential accounts and have no account role.
    if (!/\bProviderView\b/.test(body)) continue;
    // Both native selects and the shared Select live in a labelled field. Inspect each account
    // field, not a file-wide import: one correct picker cannot mask a second bare-name picker.
    for (const field of body.matchAll(/<label\b[\s\S]*?<\/label>/g)) {
      const heading = field[0].split(/<(?:select|Select)\b/)[0];
      if (!/\baccount\b/i.test(heading)) continue;
      for (const option of field[0].matchAll(/<option\b[\s\S]*?<\/option>/g)) {
        if (/\bvalue=["']["']/.test(option[0])) continue; // The static placeholder is not an account.
        options++;
        const label = option[0].slice(option[0].indexOf('>') + 1);
        if (!/\baccountOptionLabel\(\w+\)/.test(label)) violations.push(relative(src, file) + ': native account option');
      }
      for (const control of field[0].matchAll(/<Select\b[\s\S]*?\/>/g)) {
        for (const label of control[0].matchAll(/\blabel:\s*([^,}\n]+)/g)) {
          if (/^['"]/.test(label[1])) continue; // A literal placeholder.
          options++;
          if (!/^accountOptionLabel\(\w+\)/.test(label[1])) violations.push(relative(src, file) + ': Select account option');
        }
      }
    }
  }
  expect(options, 'Discover real account options before declaring success').toBeGreaterThan(0);
  // Verified by mutation: SettingsContextProviders label: accountOptionLabel(a) -> label: a.name.
  expect(violations, 'Every account option uses the shared name · type · role label').toEqual([]);
});

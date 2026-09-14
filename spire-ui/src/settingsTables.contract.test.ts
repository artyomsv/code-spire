import { expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve, relative } from 'node:path';

const src = join(process.cwd(), 'src');
function read(file: string) {
  const text = readFileSync(file, 'utf8');
  expect(text.length, `Source was read: ${file}`).toBeGreaterThan(0);
  return text.replace(/\/\*[\s\S]*?\*\//g, '');
}
function imports(text: string, file: string) {
  const paths = new Map<string, string>();
  for (const match of text.matchAll(/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]/g)) {
    if (!match[2].startsWith('.')) continue;
    const base = resolve(dirname(file), match[2]);
    const target = [base + '.tsx', join(base, 'index.tsx')].find(existsSync);
    if (!target) continue;
    for (const name of match[1].matchAll(/\b[A-Z]\w*\b/g)) paths.set(name[0], target);
  }
  return paths;
}
function tableSources(file: string, visited = new Set<string>()): Map<string, string[]> {
  const found = new Map<string, string[]>();
  if (visited.has(file)) return found;
  visited.add(file);
  const text = read(file);
  const tables = [...text.matchAll(/<table\b[^>]*>/g)].map(match => match[0]);
  if (tables.length) found.set(file, tables);
  const dependencies = imports(text, file);
  // Follow rendered components, not every import: webhookPath is a helper, not another screen.
  for (const match of text.matchAll(/<([A-Z]\w*)\b/g)) {
    const target = dependencies.get(match[1]);
    if (target) for (const [path, values] of tableSources(target, visited)) found.set(path, values);
  }
  return found;
}

it('uses the shared table vocabulary on every settings route and its rendered children', () => {
  const app = join(src, 'App.tsx');
  const text = read(app), dependencies = imports(text, app);
  const routes = [...text.matchAll(/<Route\b[^>]*?path="(\/settings\/[^"\s]+)"\s+element=\{(?:configure\()?<([A-Z]\w*)\b/g)];
  expect(routes.length, 'The guard must derive settings routes from App.tsx').toBeGreaterThan(0);
  const tables = new Map<string, string[]>();
  for (const [, route, component] of routes) {
    if (component === 'RedirectKeepingQuery') continue; // A redirect renders no settings screen.
    const target = dependencies.get(component);
    expect(target, `${route} has a readable screen import`).toBeDefined();
    for (const [file, values] of tableSources(target!)) tables.set(file, values);
  }
  expect(tables.size, 'At least one actual table must be checked').toBeGreaterThan(0);
  const unstyled = [...tables].flatMap(([file, tags]) => tags
    .filter(tag => !/className="[^"]*\bprov-table\b[^"]*"/.test(tag))
    .map(() => relative(src, file)));
  // Verified by mutation: remove prov-table from RepositoryRegistryPage, leaving its table intact.
  expect(unstyled, 'Every settings table must use prov-table, including extracted child components').toEqual([]);
});

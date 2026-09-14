import { expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve, relative } from 'node:path';
import { withoutBlockComments } from './test/sourceText';

const src = join(process.cwd(), 'src');
function read(file: string) {
  const text = readFileSync(file, 'utf8');
  expect(text.length, `Source was read: ${file}`).toBeGreaterThan(0);
  return withoutBlockComments(text);
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

/** Every file reachable by rendering a settings route, so a guard cannot be dodged by extraction. */
function screenFiles(): Map<string, string> {
  const app = join(src, 'App.tsx');
  const text = read(app), dependencies = imports(text, app);
  const routes = [...text.matchAll(/<Route\b[^>]*?path="(\/settings\/[^"\s]+)"\s+element=\{\s*(?:configure\(\s*)?<([A-Z]\w*)\b/g)];
  expect(routes.length, 'The guard must derive settings routes from App.tsx').toBeGreaterThan(0);
  const files = new Map<string, string>();
  const walk = (file: string) => {
    if (files.has(file)) return;
    const body = read(file);
    files.set(file, body);
    const deps = imports(body, file);
    for (const match of body.matchAll(/<([A-Z]\w*)\b/g)) {
      const target = deps.get(match[1]);
      if (target) walk(target);
    }
  };
  for (const [, , component] of routes) {
    if (component === 'RedirectKeepingQuery') continue;
    const target = dependencies.get(component);
    if (target) walk(target);
  }
  expect(files.size, 'At least one settings screen must be inspected').toBeGreaterThan(0);
  return files;
}

/**
 * A screen owns a panel; a field component is the thing you put inside one. `AutoTextarea` and
 * `ActorPicker` render a control on purpose and belong within a `SettingField`, so the rule that
 * screens must use one does not apply to them.
 */
const isScreen = (body: string) => /className="(card|content)\b/.test(body);

/**
 * Screens written before these conventions. The list may shrink and must never grow — a new entry
 * means a new screen repeated a mistake these rules exist to stop. Recorded 2026-09-14.
 */
const LEGACY_SCREENS = [
  'ProviderFormModal.tsx', 'SettingsOperators.tsx', 'ScmConnections.tsx', 'SettingsWebhookRepos.tsx',
  'SettingsLlmProviders.tsx', 'SettingsLlmModelForm.tsx', 'SettingsContextProviders.tsx',
];
const legacy = (file: string) => LEGACY_SCREENS.some(name => file.endsWith(name));

it('carries every settings control on a SettingField, not a hand-rolled label', () => {
  const offenders: string[] = [];
  for (const [file, body] of screenFiles()) {
    if (!isScreen(body) || legacy(file)) continue;
    // A checkbox states its own text beside it; everything else needs the label and its explanation.
    const controls = [...body.matchAll(/<(input|select|textarea)\b[^>]*>/g)]
      .filter(match => !/type="checkbox"/.test(match[0]));
    if (controls.length && !/\bSettingField\b/.test(body)) offenders.push(relative(src, file));
  }
  // Verified by mutation: drop the SettingField import and its uses from WorkSources, leaving the
  // controls intact — this fails. A screen that renders only checkboxes is not required to import it.
  expect(offenders, 'A settings screen with form controls must use SettingField').toEqual([]);
});

it('changes things in a dialog rather than a form inline on the screen', () => {
  const offenders: string[] = [];
  for (const [file, body] of screenFiles()) {
    if (legacy(file)) continue;
    if (/<form\b/.test(body)) offenders.push(relative(src, file));
  }
  // FormDialog owns create and edit, so a settings screen renders no <form> of its own. Verified by
  // mutation: wrap RepositoryForm's fields back in a bare <form> — this fails.
  expect(offenders, 'A settings screen must not render its own <form>; use FormDialog').toEqual([]);
});

it('keeps the legacy list honest: every entry is still reachable and still in debt', () => {
  const files = screenFiles();
  for (const name of LEGACY_SCREENS) {
    const entry = [...files].find(([file]) => file.endsWith(name));
    expect(entry, `${name} is still a reachable settings screen; drop it from the list if it is gone`).toBeDefined();
    const body = entry![1];
    const owes = (isScreen(body) && !/\bSettingField\b/.test(body)) || /<form\b/.test(body);
    // A fixed screen must leave the list, or the list quietly becomes permission to stay broken.
    expect(owes, `${name} now meets the conventions; remove it from LEGACY_SCREENS`).toBe(true);
  }
});

it('uses the shared table vocabulary on every settings route and its rendered children', () => {
  const app = join(src, 'App.tsx');
  const text = read(app), dependencies = imports(text, app);
  const routes = [...text.matchAll(/<Route\b[^>]*?path="(\/settings\/[^"\s]+)"\s+element=\{\s*(?:configure\(\s*)?<([A-Z]\w*)\b/g)];
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
  // WorkPolicies is independently killed too: quoted /runs/* must not hide the following route.
  expect(unstyled, 'Every settings table must use prov-table, including extracted child components').toEqual([]);
});

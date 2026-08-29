// Diff the two arms per pull request and print what the operator has to judge.
//
// ADR-026 §9 ships rung 2 only if at least one new finding is judged correct AND false positives do
// not increase. This script does not judge; it isolates the findings that appeared ONLY with code
// context, so a human can.
const fs = require('fs');
const dir = process.argv[2] || '.';

const load = (arm, pr) => {
  const p = `${dir}/f-${arm}-pr${pr}.json`;
  return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, 'utf8')) : null;
};

// Same defect re-found in a second run rarely comes back with byte-identical wording, so exact
// matching would call every finding "new" and make the comparison meaningless. Anchor plus overlap.
const words = (s) => new Set((s || '').toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').split(/\s+/).filter(Boolean));
const overlap = (a, b) => {
  const x = words(a), y = words(b);
  if (!x.size || !y.size) return 0;
  let hit = 0;
  for (const w of x) if (y.has(w)) hit++;
  return hit / Math.max(x.size, y.size);
};
const same = (f, g) =>
  f.path === g.path && Math.abs((f.line || 0) - (g.line || 0)) <= 10 && overlap(f.message, g.message) >= 0.35;

const prs = [...new Set(fs.readdirSync(dir)
  .map((f) => f.match(/^f-(?:off|on)-pr(\d+)\.json$/)).filter(Boolean)
  .map((m) => Number(m[1])))].sort((a, b) => a - b);

const excluded = [];
const usable = [];
for (const pr of prs) {
  const off = load('off', pr), on = load('on', pr);
  if (!off || !on) { excluded.push([pr, `missing ${!off ? 'control' : 'treatment'} arm`]); continue; }
  if (!off.valid || !on.valid) {
    excluded.push([pr, [...off.invalidReasons.map((r) => 'control: ' + r),
                        ...on.invalidReasons.map((r) => 'treatment: ' + r)].join('; ')]);
    continue;
  }
  usable.push([pr, off, on]);
}

let onlyWith = [], onlyWithout = [], both = 0, costOff = 0, costOn = 0;

for (const [pr, off, on] of usable) {
  costOff += off.costCents; costOn += on.costCents;
  const used = new Set();
  for (const f of off.findings) {
    const j = on.findings.findIndex((g, i) => !used.has(i) && same(f, g));
    if (j >= 0) { used.add(j); both++; } else onlyWithout.push({ pr, ...f });
  }
  onlyWith.push(...on.findings.filter((_, i) => !used.has(i)).map((f) => ({ pr, ...f })));

  console.log(`\n=== PR #${pr} — control ${off.findings.length} finding(s), treatment ` +
    `${on.findings.length} · code snippets: ${off.code.contributed} vs ${on.code.contributed} ` +
    `(${on.code.extracted} identifiers extracted, ${on.code.resolved} resolved) ===`);
}

console.log('\n\n########  WHAT THE GATE TURNS ON  ########');
console.log(`\nPull requests measured: ${usable.length}${excluded.length ? ` (${excluded.length} excluded)` : ''}`);
if (excluded.length) {
  // Never silently: an excluded pull request that goes unmentioned reads as a measured null.
  console.log('\nEXCLUDED — not evidence either way:');
  for (const [pr, why] of excluded) console.log(`  #${pr}: ${why}`);
}
console.log(`\n  found by BOTH arms      : ${both}`);
console.log(`  ONLY with code context  : ${onlyWith.length}   <- judge these`);
console.log(`  ONLY without            : ${onlyWithout.length}   <- run-to-run variance / displacement`);
console.log(`  cost: control ${costOff.toFixed(0)}c, treatment ${costOn.toFixed(0)}c\n`);

const show = (title, list) => {
  if (!list.length) return;
  console.log(`\n---------- ${title} ----------`);
  for (const f of list) {
    console.log(`\n[PR #${f.pr}] ${f.severity.toUpperCase()} — ${f.path}:${f.line}`);
    console.log('  ' + (f.message || '').replace(/\s+/g, ' ').slice(0, 700));
  }
};
show('FINDINGS THAT APPEARED ONLY WITH CODE CONTEXT', onlyWith);
show('FINDINGS THAT APPEARED ONLY WITHOUT (control)', onlyWithout);

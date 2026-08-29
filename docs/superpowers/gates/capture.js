// Pull one review's decrypted detail from the orchestrator API and write a normalized record.
//
// Reads through the API on purpose: findings_json is Tink-encrypted at rest, so the DB column is
// unreadable by design. The detail payload names the COUNT `findings` and the ARRAY `findingsList`.
const fs = require('fs');
const [, , pr, arm, outDir] = process.argv;
const repo = process.env.REPO || 'code-spire';
const url = `http://localhost:39280/api/reviews/artyomsv/${repo}/${pr}`;

// How much CODE context the run actually received, read from the worker's own log line rather than
// assumed from the provider toggle. This is half the positive control: the control arm must receive
// none and the treatment arm must receive some, or the arms were not actually different.
function codeContribution(dir, arm, pr) {
  const p = `${dir}/ctx-${arm}-pr${pr}.log`;
  if (!fs.existsSync(p)) return null;
  const line = fs.readFileSync(p, 'utf8').split('\n').filter((l) => l.includes('for CODE')).pop();
  if (!line) return { extracted: 0, resolved: 0, contributed: 0, sawLine: false };
  // One literal pattern rather than three built from a variable. The variable form is a ReDoS
  // shape even when every caller passes a constant, and the line format is fixed anyway:
  //   Context resolution for CODE: extracted=N resolved=N contributed=N droppedForBudget=N
  const m = line.match(/extracted=(\d+) resolved=(\d+) contributed=(\d+)/);
  if (!m) return { extracted: 0, resolved: 0, contributed: 0, sawLine: false };
  return { extracted: Number(m[1]), resolved: Number(m[2]), contributed: Number(m[3]), sawLine: true };
}

fetch(url).then((r) => r.json()).then((d) => {
  const findings = (d.findingsList || []).map((f) => {
    const i = (f.loc || '').lastIndexOf(':');
    return {
      path: i > 0 ? f.loc.slice(0, i) : (f.loc || ''),
      line: i > 0 ? Number(f.loc.slice(i + 1)) || 0 : 0,
      severity: f.sev,
      message: f.msg,
    };
  });
  const tok = (t) => (d.chargeLines || []).filter((c) => c.tokenType === t)
    .reduce((a, c) => a + c.tokens, 0);
  const code = codeContribution(outDir, arm, pr);

  // THE POSITIVE CONTROL. The gate stops rung 2 on a null result, so it must be able to tell "code
  // context did not help" from "this run produced nothing" — the outcome the first attempt actually
  // hit, and mistook for a measurement. A run that came back unparseable is not evidence either way.
  const invalid = [];
  if (d.status !== 'completed') invalid.push(`status=${d.status}`);
  if (d.degraded) invalid.push('degraded (model returned nothing usable)');
  // The other half of the control, and it is arm-specific: the control arm must have received NO
  // code context and the treatment arm must have received some, or the two arms were not actually
  // different and any difference in findings is noise wearing the variable's name.
  const contributed = code ? code.contributed : 0;
  if (arm === 'off' && contributed !== 0) {
    invalid.push(`control arm received ${contributed} code snippet(s) — the toggle did not take`);
  }
  if (arm === 'on' && contributed === 0) {
    invalid.push('treatment arm received no code context — nothing was under test');
  }

  const out = {
    pr: Number(pr), repo, arm, status: d.status, sha: d.sha,
    degraded: !!d.degraded,
    valid: invalid.length === 0,
    invalidReasons: invalid,
    code,
    findings,
    inputTokens: tok('INPUT'), outputTokens: tok('OUTPUT'),
    costCents: (d.chargeLines || []).reduce((a, c) => a + c.costMillicents, 0) / 1000,
    note: d.note,
  };
  fs.writeFileSync(`${outDir}/f-${arm}-pr${pr}.json`, JSON.stringify(out, null, 2));
  console.log(`PR ${pr} [${arm}] ${d.status}${d.degraded ? ' DEGRADED' : ''} ` +
    `findings=${findings.length} code(contributed)=${code ? code.contributed : '?'} ` +
    `in=${out.inputTokens} out=${out.outputTokens} cost=${out.costCents.toFixed(2)}c` +
    (out.valid ? '' : `  *** INVALID: ${invalid.join('; ')} ***`));
  for (const f of findings) console.log(`    [${f.severity}] ${f.path}:${f.line}`);
}).catch((e) => { console.error('capture failed', e.message); process.exit(1); });

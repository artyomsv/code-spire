import { useEffect, useRef, useState } from 'react';
import { checkProvider, verifyRepo, type WebhookRepoView } from '../api';
import type { ServingLookup } from '../hooks/useServingAccounts';
import { servingChip } from './servingAccounts';

type Role = 'reviewer' | 'factory';

interface Props {
  role: Role;
  lookup: ServingLookup | undefined;
  repo: WebhookRepoView;
}

/**
 * One role's account for one repository row, as a chip, with a Verify that probes with THAT
 * account's token. A repository row GETs the repository; an organization row has nothing to GET and
 * runs the account's own connectivity check instead.
 *
 * <p>For the factory the verify is a read: it proves the token can see the repository and nothing
 * about pushing. The label says so, because "verified" beside a push identity would be read as
 * "can push", and a false yes there costs an operator a failed run to discover.
 */
export default function ServingCell({ role, lookup, repo }: Props) {
  const account = lookup?.data?.[role];
  const chip = servingChip(account, lookup?.error ?? null);
  const [verify, setVerify] = useState<{ state: 'idle' | 'checking' | 'ok' | 'fail'; detail?: string }>({
    state: 'idle',
  });
  const canVerify = account !== undefined && account.state !== 'missing' && account.id !== null;
  const verifyReq = useRef(0);

  // A verify result describes one account. The row is never remounted — `<tr key={w.id}>` is stable
  // — so when the lookup resolves to a different account, drop the result it no longer describes
  // rather than leave "reachable" sitting beside a chip it was never about. Bumping the request
  // number here also invalidates a probe still in flight, whose answer would otherwise land after
  // the reset and describe the previous account.
  useEffect(() => {
    verifyReq.current += 1;
    setVerify({ state: 'idle' });
  }, [account?.id, account?.state]);

  async function onVerify() {
    if (account === undefined || account.id === null) return;
    const reqId = ++verifyReq.current;
    setVerify({ state: 'checking' });
    try {
      const result = repo.scope === 'repo' ? await verifyRepo(account.id, repo.target) : await checkProvider(account.id);
      if (reqId !== verifyReq.current) return; // account changed while in flight — drop the stale result
      setVerify(result.ok ? { state: 'ok' } : { state: 'fail', detail: result.detail ?? 'Not reachable' });
    } catch (err) {
      if (reqId !== verifyReq.current) return;
      setVerify({ state: 'fail', detail: err instanceof Error ? err.message : String(err) });
    }
  }

  const okText = role === 'factory' ? 'reachable (read). Push rights are not checked.' : 'reachable';
  const verb = role === 'factory' ? 'push' : 'review';

  return (
    <div className="serving-cell">
      <span className={`pill ${chip.pill}`} title={chip.title}>
        <span className="glyph"></span>
        {chip.label}
      </span>
      {canVerify && (
        <button
          type="button"
          className="btn-ghost serving-verify"
          onClick={() => void onVerify()}
          disabled={verify.state === 'checking'}
          aria-label={`Verify ${verb} account for ${repo.target}`}
        >
          {verify.state === 'checking' ? 'Verifying…' : 'Verify'}
        </button>
      )}
      {verify.state === 'ok' && <div className="wh-verify ok">{okText}</div>}
      {verify.state === 'fail' && <div className="wh-verify fail">{verify.detail}</div>}
    </div>
  );
}

import { useEffect, useRef, useState } from 'react';
import { cancelHarnessSignIn, fetchHarnessSignIn, fetchHarnessSignInProgress, startHarnessSignIn, type HarnessSignInView } from '../api';
import HarnessSignInCode from './HarnessSignInCode';
import SettingField from './SettingField';

/** How often the screen asks. The operator is walking to another device; this is not a race. */
const POLL_MS = 2000;

/** The rules that can stop a sign-in, each as a sentence rather than a code. */
const REASONS: Record<string, string> = {
  harness_unconfigured: 'This deployment has no agent image for that harness, so there is nothing to sign in with.',
  sign_in_already_running: 'A sign-in for this harness is already open. Finish or cancel that one first.',
  harness_credential_label_taken: 'That name is already used by another credential. Pick a different one.',
  sign_in_expired: 'Nobody approved the code before it ran out. Start again to get a fresh one.',
  sign_in_cancelled: 'The sign-in was cancelled.',
  sign_in_unit_failed: 'The sign-in tool did not start, or printed something this version cannot read. Nothing was stored.',
  sign_in_wrong_mode: 'That signed in as an API key, not a subscription. Add it as an API key instead.',
  sign_in_not_started: 'No run worker showed a code within six minutes. Check that the run worker is running and has the agent image, then start again.',
};

const sentence = (reason: string | null) =>
  (reason && REASONS[reason]) || 'The sign-in did not finish, and nothing was stored.';

/** "13m 30s" until the code runs out, then null. */
function remaining(expiresAt: string | null): string | null {
  if (!expiresAt) return null;
  const seconds = Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000);
  if (seconds <= 0) return null;
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`;
}

interface Props {
  harness: string;
  /** A member was created. The pool list reloads and says so. */
  done: (label: string) => void;
}

/**
 * Signing a harness in with a subscription, from a browser (M3.5 part F).
 *
 * <p><b>Nothing is typed into a terminal.</b> An earlier design asked the operator to run the vendor
 * CLI on the host and paste the resulting file into a form. That cannot ship: in a real deployment the
 * services run in containers on a machine the operator may never log in to. So the factory runs that
 * CLI itself, in a trusted container, and shows what it printed.
 *
 * <p><b>The code on screen is not a secret.</b> It authorises nothing on its own — only the account
 * holder can approve it, and only the unit that started the flow can collect the result. It is still
 * short-lived and single-use, which is why the countdown is beside it.
 */
export default function HarnessSubscriptionSignIn({ harness, done }: Props) {
  const [label, setLabel] = useState('');
  const [signIn, setSignIn] = useState<HarnessSignInView | null>(null);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false);
  // Only re-renders the countdown between polls; the value itself is never read.
  const [, setTick] = useState(0);
  const active = useRef(true);
  /**
   * Which sign-in the screen is on. Every start, cancel and unmount moves it on, and a poll that
   * answers under an older number is dropped.
   *
   * <p>Clearing the interval stops future ticks; it does nothing about a request already in flight. So
   * a GET issued before a cancel could answer after it and put the cancelled code back on screen,
   * polling again — or replace a sign-in the operator had just started with the one they abandoned.
   */
  const generation = useRef(0);
  useEffect(() => { active.current = true; return () => { active.current = false; generation.current += 1; }; }, []);

  // A reopened screen finds the sign-in already in flight rather than starting a second one for the
  // same seat, which would put two codes in front of one person.
  useEffect(() => {
    fetchHarnessSignInProgress(harness)
      .then(open => { if (active.current && open) setSignIn(open); })
      .catch(() => { /* nothing in flight is the ordinary case, and not worth an error banner */ });
  }, [harness]);

  const watching = signIn !== null && (signIn.state === 'PENDING' || signIn.state === 'PROMPTED');

  useEffect(() => {
    if (!watching || !signIn) return;
    const mine = generation.current;
    let outstanding = false;
    const timer = setInterval(() => {
      // One request at a time. Overlapping polls can answer out of order, and the older answer wins
      // simply by arriving last.
      if (outstanding) return;
      outstanding = true;
      fetchHarnessSignIn(signIn.id)
        .then(next => {
          if (!active.current || generation.current !== mine) return;
          setSignIn(next);
          if (next.state === 'COMPLETE') done(next.label);
        })
        .catch(() => { /* one failed poll is not a failed sign-in; the next one answers */ })
        .finally(() => { outstanding = false; });
      setTick(value => value + 1);
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [watching, signIn, done]);

  async function start() {
    setBusy(true); setError('');
    generation.current += 1;
    try { setSignIn(await startHarnessSignIn(label.trim(), harness)); }
    catch (failure) { setError(sentence(failure instanceof Error ? failure.message : null)); }
    finally { if (active.current) setBusy(false); }
  }

  async function stop() {
    if (!signIn) return;
    setBusy(true); setError('');
    generation.current += 1;
    try { await cancelHarnessSignIn(signIn.id); setSignIn(null); }
    catch (failure) { setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (active.current) setBusy(false); }
  }

  if (signIn?.state === 'FAILED') {
    return <>
      <p className="prov-error" role="alert">{sentence(signIn.reason)}</p>
      <div className="prov-actions">
        <button className="btn" type="button" onClick={() => { setSignIn(null); setError(''); }}>Start again</button>
      </div>
    </>;
  }

  if (watching && signIn) {
    return <>
      {signIn.state === 'PENDING' && <p className="prov-note" role="status">Starting the sign-in. This takes a few seconds.</p>}
      {signIn.state === 'PROMPTED' && signIn.verificationUri && signIn.userCode &&
        <HarnessSignInCode verificationUri={signIn.verificationUri} userCode={signIn.userCode}
          remaining={remaining(signIn.expiresAt)} />}
      {error && <p className="prov-error" role="alert">{error}</p>}
      <div className="prov-actions">
        <button className="btn-ghost sm" type="button" disabled={busy} onClick={() => void stop()}>Cancel sign-in</button>
      </div>
    </>;
  }

  return <>
    <SettingField label="Name" scope="credential pool" hint="Your own name for this sign-in, so a refusal later names something you can find.">
      <input value={label} onChange={event => setLabel(event.target.value)} />
    </SettingField>
    <p className="prov-note">
      Use a seat that only the factory uses. An agent runs a model on an untrusted ticket at full shell
      access, so a sign-in it can read should not be the one you use yourself.
    </p>
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="prov-actions">
      <button className="btn" type="button" disabled={busy || !label.trim()} onClick={() => void start()}>
        {busy ? 'Starting…' : 'Start sign-in'}
      </button>
    </div>
  </>;
}

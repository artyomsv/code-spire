import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import type { HarnessSignInView } from '../api';
import HarnessSubscriptionSignIn from './HarnessSubscriptionSignIn';

afterEach(cleanup);
beforeEach(() => { vi.spyOn(api, 'fetchHarnessSignInProgress').mockResolvedValue(null); });

function signIn(over: Partial<HarnessSignInView> = {}): HarnessSignInView {
  return {
    id: 'TEST-sign-in', label: 'TEST-factory-seat', harness: 'codex', state: 'PENDING',
    verificationUri: null, userCode: null, expiresAt: null, reason: null, credentialId: null, ...over,
  };
}

const prompted = () => signIn({
  state: 'PROMPTED', verificationUri: 'https://auth.example.test/device', userCode: 'ABCD-12345',
  expiresAt: new Date(Date.now() + 14 * 60 * 1000).toISOString(),
});

// The whole point of part F: an operator never opens a terminal. They type a name, press a button, and
// the screen tells them where to go and what to type there.
it('shows the link and the code the operator must use', async () => {
  vi.spyOn(api, 'startHarnessSignIn').mockResolvedValue(prompted());
  render(<HarnessSubscriptionSignIn harness="codex" done={() => { }} />);

  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'TEST-factory-seat' } });
  fireEvent.click(screen.getByRole('button', { name: 'Start sign-in' }));

  const link = await screen.findByRole('link', { name: 'https://auth.example.test/device' });
  expect(link).toHaveAttribute('href', 'https://auth.example.test/device');
  // A new tab, and no referrer or opener handed to whatever is on the other side.
  expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
  expect(screen.getByLabelText('One-time code')).toHaveTextContent('ABCD-12345');
  expect(screen.getByText(/^The code expires in \d+m \d{2}s\./)).toBeInTheDocument();
  await waitFor(() => expect(api.startHarnessSignIn).toHaveBeenCalledWith('TEST-factory-seat', 'codex'));
});

it('cannot start without a name, so a refusal later names something findable', async () => {
  render(<HarnessSubscriptionSignIn harness="codex" done={() => { }} />);
  await act(async () => { });

  expect(screen.getByRole('button', { name: 'Start sign-in' })).toBeDisabled();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '   ' } });
  expect(screen.getByRole('button', { name: 'Start sign-in' })).toBeDisabled();
});

// A rule that stopped it is a sentence, never a status code or a vendor message.
it('names the rule that stopped a sign-in', async () => {
  vi.spyOn(api, 'startHarnessSignIn').mockRejectedValue(new Error('sign_in_already_running'));
  render(<HarnessSubscriptionSignIn harness="codex" done={() => { }} />);

  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'TEST-seat' } });
  fireEvent.click(screen.getByRole('button', { name: 'Start sign-in' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(/already open/);
});

it('says plainly when a sign-in came back as an API key', async () => {
  vi.spyOn(api, 'fetchHarnessSignInProgress').mockResolvedValue(
    signIn({ state: 'FAILED', reason: 'sign_in_wrong_mode' }));
  render(<HarnessSubscriptionSignIn harness="codex" done={() => { }} />);

  expect(await screen.findByRole('alert')).toHaveTextContent(/API key, not a subscription/);
  expect(screen.getByRole('button', { name: 'Start again' })).toBeInTheDocument();
});

// A reopened screen must find the sign-in already running. Starting a second puts two codes in front
// of one person, and only one of them can ever work.
it('picks up a sign-in that is already in flight instead of starting another', async () => {
  vi.spyOn(api, 'fetchHarnessSignInProgress').mockResolvedValue(prompted());
  const start = vi.spyOn(api, 'startHarnessSignIn');
  render(<HarnessSubscriptionSignIn harness="codex" done={() => { }} />);

  expect(await screen.findByLabelText('One-time code')).toHaveTextContent('ABCD-12345');
  expect(start).not.toHaveBeenCalled();
});

it('tells the pool when a sign-in finished, by the name the operator gave it', async () => {
  vi.useFakeTimers();
  try {
    vi.spyOn(api, 'fetchHarnessSignInProgress').mockResolvedValue(prompted());
    vi.spyOn(api, 'fetchHarnessSignIn').mockResolvedValue(
      signIn({ state: 'COMPLETE', credentialId: 'TEST-credential' }));
    const done = vi.fn();
    render(<HarnessSubscriptionSignIn harness="codex" done={done} />);
    await act(async () => { });

    await act(async () => { vi.advanceTimersByTime(2000); });
    await act(async () => { });

    expect(done).toHaveBeenCalledWith('TEST-factory-seat');
  } finally { vi.useRealTimers(); }
});

it('stops a sign-in the operator gave up on', async () => {
  vi.spyOn(api, 'fetchHarnessSignInProgress').mockResolvedValue(prompted());
  const cancel = vi.spyOn(api, 'cancelHarnessSignIn').mockResolvedValue(undefined);
  render(<HarnessSubscriptionSignIn harness="codex" done={() => { }} />);

  fireEvent.click(await screen.findByRole('button', { name: 'Cancel sign-in' }));

  await waitFor(() => expect(cancel).toHaveBeenCalledWith('TEST-sign-in'));
  expect(await screen.findByRole('button', { name: 'Start sign-in' })).toBeInTheDocument();
});

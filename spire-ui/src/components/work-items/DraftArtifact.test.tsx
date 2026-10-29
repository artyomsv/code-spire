import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { FakeSocket } from '../../test/fakeSocket';
import { queryWrapper } from '../../test/queryWrapper';
import DraftArtifact from './DraftArtifact';
import { useWorkItemChanges } from './useWorkItemChanges';
import * as draftApi from './workDraftApi';
import type { DraftComment, WorkDraftView } from './workDraftApi';

beforeEach(() => {
  FakeSocket.sockets = [];
  vi.stubGlobal('WebSocket', FakeSocket);
});

const TICKET = 'https://tracker.example.test/TEST-owner/TEST-repo/issues/42';
const view = (text: string, comment: DraftComment | null = null): WorkDraftView => ({
  specification: { text, sha256: 'a'.repeat(64), comment },
  plan: { text: 'TEST-plan', sha256: 'b'.repeat(64), comment: null, steps: [] },
  revisions: { specification: 0, plan: 0 },
  ticketLink: TICKET,
});
const written = (sequence: number) => ({ sequence, type: 'DRAFT_ARTIFACT_WRITTEN', reason: 'TEST-written', occurredAt: '1970-01-01T00:00:00Z', generation: 1 });
const item = (...sequences: number[]) => ({ id: 'TEST-item', generation: 1, events: sequences.map(written) });

function Feed() {
  useWorkItemChanges();
  return null;
}

// The specification step and the plan step show parts of one draft. They share one read of it.
it('reads one draft version once for both parts', async () => {
  const read = vi.spyOn(draftApi, 'workDraft').mockResolvedValue(view('TEST-spec'));
  render(<><DraftArtifact item={item(1)} part="spec" /><DraftArtifact item={item(1)} part="plan" /></>, { wrapper: queryWrapper() });
  expect(await screen.findByLabelText('Specification text')).toHaveTextContent('TEST-spec');
  expect(read).toHaveBeenCalledTimes(1);
});

// Review of PR 287, M-2: a revised specification must replace the first text on screen.
it('reads the draft again when the generation writes a newer artifact', async () => {
  vi.spyOn(draftApi, 'workDraft').mockResolvedValueOnce(view('TEST-first')).mockResolvedValue(view('TEST-revised'));
  const { rerender } = render(<DraftArtifact item={item(1)} part="spec" />, { wrapper: queryWrapper() });
  expect(await screen.findByLabelText('Specification text')).toHaveTextContent('TEST-first');
  rerender(<DraftArtifact item={item(1, 2)} part="spec" />);
  await waitFor(() => expect(screen.getByLabelText('Specification text')).toHaveTextContent('TEST-revised'));
});

// A read made for an older write never stands in for the newer one while that is still out.
it('shows nothing for a newer write until its own read answers', async () => {
  let answer: (value: WorkDraftView) => void = () => {};
  vi.spyOn(draftApi, 'workDraft').mockResolvedValueOnce(view('TEST-first'))
    .mockImplementationOnce(() => new Promise(resolve => { answer = resolve; }));
  const { rerender } = render(<DraftArtifact item={item(1)} part="spec" />, { wrapper: queryWrapper() });
  expect(await screen.findByLabelText('Specification text')).toHaveTextContent('TEST-first');
  rerender(<DraftArtifact item={item(1, 2)} part="spec" />);
  expect(screen.queryByLabelText('Specification text')).toBeNull();
  answer(view('TEST-revised'));
  expect(await screen.findByLabelText('Specification text')).toHaveTextContent('TEST-revised');
});

it('asks the server nothing before the generation writes a draft', () => {
  const read = vi.spyOn(draftApi, 'workDraft');
  render(<DraftArtifact item={item()} part="spec" />, { wrapper: queryWrapper() });
  expect(read).not.toHaveBeenCalled();
});

// The ticket comment is marked sent without a new item event (work_tracker_outbox only), so the item's notice
// re-reads the draft even though its version did not change.
it('reads the draft again when the feed names its item', async () => {
  const read = vi.spyOn(draftApi, 'workDraft').mockResolvedValueOnce(view('TEST-spec', { state: 'pending', ref: null }))
    .mockResolvedValue(view('TEST-spec', { state: 'sent', ref: 'https://tracker.example.test/TEST-comment' }));
  render(<><Feed /><DraftArtifact item={item(1)} part="spec" /></>, { wrapper: queryWrapper() });
  expect(await screen.findByText('Being posted to the ticket')).toBeInTheDocument();
  FakeSocket.push({ changed: ['TEST-item'] });
  expect(await screen.findByText('Posted to the ticket')).toBeInTheDocument();
  expect(read).toHaveBeenCalledTimes(2);
});

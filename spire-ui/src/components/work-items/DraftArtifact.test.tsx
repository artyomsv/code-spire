import { render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { queryWrapper } from '../../test/queryWrapper';
import DraftArtifact from './DraftArtifact';
import * as draftApi from './workDraftApi';
import type { WorkDraftView } from './workDraftApi';

const TICKET = 'https://tracker.example.test/TEST-owner/TEST-repo/issues/42';
const view = (text: string): WorkDraftView => ({
  specification: { text, sha256: 'a'.repeat(64), comment: null },
  plan: { text: 'TEST-plan', sha256: 'b'.repeat(64), comment: null, steps: [] },
  revisions: { specification: 0, plan: 0 },
  ticketLink: TICKET,
});
const written = (sequence: number) => ({ sequence, type: 'DRAFT_ARTIFACT_WRITTEN', reason: 'TEST-written', occurredAt: '1970-01-01T00:00:00Z', generation: 1 });
const item = (...sequences: number[]) => ({ id: 'TEST-item', generation: 1, events: sequences.map(written) });

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

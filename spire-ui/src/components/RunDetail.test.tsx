import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import * as auth from '../auth';
import { RunSocket, runView } from '../test/liveRuns';
import RunDetail from './RunDetail';
import Runs from './Runs';

beforeEach(() => {
  RunSocket.sockets = [];
  vi.stubGlobal('WebSocket', RunSocket);
  vi.spyOn(api, 'getRun').mockResolvedValue(runView());
  vi.spyOn(api, 'getRuns').mockResolvedValue([runView()]);
  vi.spyOn(api, 'getRunTranscript').mockResolvedValue([]);
  vi.spyOn(auth, 'isLeavingForAuth').mockReturnValue(false);
  vi.spyOn(auth, 'fetchMe').mockResolvedValue(null);
});

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); });

function show(run = runView()) {
  vi.mocked(api.getRun).mockResolvedValue(run);
  return render(<MemoryRouter initialEntries={[`/runs/${encodeURIComponent(run.runId)}`]}>
    <Routes><Route path="/runs/*" element={<RunDetail />} /></Routes>
  </MemoryRouter>);
}

async function loaded() {
  await screen.findByRole('region', { name: 'Runtime' });
}

function pushRun(value: unknown) {
  const socket = [...RunSocket.sockets].reverse().find((socket) => socket.url.endsWith('/api/ws/runs'));
  act(() => socket?.onmessage?.({ data: JSON.stringify(value) }));
}

it('opens the encoded list link for an id containing slashes and colons', async () => {
  render(<MemoryRouter initialEntries={['/runs']}><Routes>
    <Route path="/runs" element={<Runs />} /><Route path="/runs/*" element={<RunDetail />} />
  </Routes></MemoryRouter>);
  const link = await screen.findByRole('link', { name: 'task:1' });
  expect(link).toHaveAttribute('href', `/runs/${encodeURIComponent(runView().runId)}`);
  fireEvent.click(link);
  await loaded();
  expect(api.getRun).toHaveBeenCalledExactlyOnceWith(runView().runId);
  expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(runView().runId);
});

it('does not decode an already-decoded literal percent sequence a second time', async () => {
  const run = runView({ runId: 'run::github:TEST-WS/app:literal-%41:1' });
  show(run);
  await loaded();
  expect(api.getRun).toHaveBeenCalledExactlyOnceWith(run.runId);
});

it('reports an unknown id as no such run', async () => {
  show();
  vi.mocked(api.getRun).mockResolvedValue(null);
  pushRun([]);
  expect(await screen.findByText('No such run')).toBeInTheDocument();
});

it('shows two unrecorded phases for a queued run', async () => {
  show();
  await loaded();
  const strip = screen.getByRole('list', { name: 'Run phases' });
  expect(within(strip).getAllByRole('listitem')).toHaveLength(3);
  expect(within(strip).getAllByText('not recorded')).toHaveLength(2);
});

it('does not invent an agent start for an older finished run', async () => {
  show(runView({ status: 'succeeded', endedAt: '2026-09-11T10:03:00Z' }));
  await loaded();
  const phases = within(screen.getByRole('list', { name: 'Run phases' })).getAllByRole('listitem');
  expect(phases[1]).toHaveTextContent('not recorded');
  expect(phases[1].querySelector('time')).toBeNull();
});

it('renders unknown total cost as a dash and keeps a partial subtotal separate', async () => {
  show(runView({ spend: { priced: 7000, unpricedLines: 1, tokensByType: { INPUT: 400, OUTPUT: 20 } } }));
  await loaded();
  const spend = screen.getByRole('region', { name: 'Spend' });
  expect(within(spend).getByLabelText('Total cost')).toHaveTextContent(/^—$/);
  expect(within(spend).getByText(/\$0.070 priced · 1 unpriced charge lines/)).toBeInTheDocument();
  expect(within(spend).queryByText('$0.000')).toBeNull();
  expect(within(spend).getByText('400')).toBeInTheDocument();
  expect(within(spend).getByText('20')).toBeInTheDocument();
});

it('renders a real zero as free and exposes every recorded token type', async () => {
  show(runView({ cost: { millicents: 0 }, spend: { priced: 0, unpricedLines: 0,
    tokensByType: { INPUT: 10, CACHED_INPUT: 20, CACHE_WRITE: 30, OUTPUT: 40, REASONING: 50, TOTAL: 60 } } }));
  await loaded();
  const spend = screen.getByRole('region', { name: 'Spend' });
  expect(within(spend).getByLabelText('Total cost')).toHaveTextContent('$0.000');
  for (const [label, count] of [['Input', 10], ['Cached input', 20], ['Cache write', 30], ['Output', 40], ['Reasoning', 50], ['Unsplit total', 60]]) {
    expect(within(spend).getByText(label).nextElementSibling).toHaveTextContent(String(count));
  }
});

it('warns on an unknown status and does not start an elapsed clock', async () => {
  vi.useFakeTimers();
  show(runView({ status: 'unexpected' as api.RunStatus }));
  await act(async () => {});
  expect(screen.getByText('Unknown (unexpected)')).toHaveClass('refused');
  const definition = screen.getByRole('region', { name: 'Run definition' });
  expect(within(definition).getByText('Elapsed').nextElementSibling).toHaveTextContent(/^—$/);
  expect(vi.getTimerCount()).toBe(0);
});

it('lists every blocked path and shows failure details only for a failed run', async () => {
  show(runView({ status: 'push_gate_refused', blocked: [
    { path: 'secrets/a.txt', kind: 'TEST-secret' }, { path: 'config/b.txt', kind: 'TEST-policy' },
  ], failureCause: 'TEST-stale-cause', failureDetail: 'TEST-stale-detail' }));
  await loaded();
  const blocked = screen.getByRole('region', { name: 'Blocked paths' });
  expect(within(blocked).getAllByRole('listitem')).toHaveLength(2);
  expect(within(blocked).getByText('secrets/a.txt')).toBeInTheDocument();
  expect(within(blocked).getByText('config/b.txt')).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: 'Failure' })).toBeNull();
});

it('shows runtime, definition, delivery and the task summary with a working copy button', async () => {
  const clipboard = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal('navigator', Object.create(navigator, {
    clipboard: { value: { writeText: clipboard } },
  }));
  show(runView({ status: 'failed', failureCause: 'TEST-cause', failureDetail: 'TEST-detail',
    reviewId: 'review::TEST-WS/app#42', kind: 'FIX', unitId: 'TEST-unit',
    prUrl: 'https://example.invalid/pr/42', taskSummary: 'TEST copy me' }));
  await loaded();
  expect(screen.getByRole('region', { name: 'Failure' })).toHaveTextContent('TEST-cause');
  expect(screen.getByRole('region', { name: 'Failure' })).toHaveTextContent('TEST-detail');
  expect(screen.getByRole('region', { name: 'Runtime' })).toHaveTextContent('TEST-base-sha');
  expect(screen.getByRole('region', { name: 'Runtime' })).toHaveTextContent('TEST-unit');
  expect(screen.getByRole('link', { name: '#42' })).toHaveAttribute('href', 'https://example.invalid/pr/42');
  expect(screen.getByRole('link', { name: 'review::TEST-WS/app#42' })).toHaveAttribute('href', '/r/TEST-WS/app/42');
  fireEvent.click(within(screen.getByRole('region', { name: 'Task' })).getByRole('button', { name: 'Copy' }));
  await screen.findByRole('button', { name: 'Copied' });
  expect(clipboard).toHaveBeenCalledExactlyOnceWith('TEST copy me');
});

it('refetches exactly once for this runs push and never for another run', async () => {
  show();
  await loaded();
  expect(api.getRuns).not.toHaveBeenCalled();
  pushRun(runView({ runId: 'other-run' }));
  expect(api.getRun).toHaveBeenCalledTimes(1);
  vi.mocked(api.getRun).mockResolvedValue(runView({ status: 'running' }));
  pushRun(runView({ status: 'running' }));
  await screen.findByText('Running');
  expect(api.getRun).toHaveBeenCalledTimes(2);
  expect(RunSocket.sockets.filter((socket) => socket.url.endsWith('/api/ws/runs'))).toHaveLength(1);
});

it('refreshes a historic run on a reconnect snapshot even when absent from the newest page', async () => {
  show();
  await loaded();
  pushRun([]);
  await waitFor(() => expect(api.getRun).toHaveBeenCalledTimes(2));
});

it('ignores an older detail response arriving after a newer push response', async () => {
  let resolveOld!: (run: api.RunView) => void;
  show();
  await loaded();
  vi.mocked(api.getRun).mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }))
    .mockResolvedValueOnce(runView({ status: 'succeeded' }));
  pushRun(runView({ status: 'running' }));
  pushRun(runView({ status: 'succeeded' }));
  await screen.findByText('Succeeded');
  await act(async () => resolveOld(runView({ status: 'running' })));
  expect(screen.getByText('Succeeded')).toBeInTheDocument();
  expect(screen.queryByText('Running')).toBeNull();
});

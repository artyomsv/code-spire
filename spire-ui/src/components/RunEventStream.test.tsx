import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import * as auth from '../auth';
import { RunSocket, runEvent, runRow } from '../test/liveRuns';
import RunEventStream from './RunEventStream';

beforeEach(() => {
  RunSocket.sockets = [];
  vi.stubGlobal('WebSocket', RunSocket);
  vi.spyOn(api, 'getRunTranscript').mockResolvedValue([]);
  vi.spyOn(auth, 'isLeavingForAuth').mockReturnValue(false);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function show() { render(<RunEventStream runId={runRow().runId} />); }
function rows() { return within(screen.getByRole('region', { name: 'Run events' })).queryAllByRole('listitem'); }

it('renders snapshot and append text safely, retaining the server clipping marker', () => {
  show();
  RunSocket.push([runEvent(1, { text: '<script>TEST</script> … [clipped]' })]);
  RunSocket.push([runEvent(2)]);
  expect(rows()).toHaveLength(2);
  expect(rows()[0]).toHaveTextContent('<script>TEST</script> … [clipped]');
  expect(rows()[0].querySelector('script')).toBeNull();
});

it('keeps exactly the documented known kinds under each of the four filters', () => {
  show();
  RunSocket.push(['THINKING', 'TOOL_USE', 'TOOL_RESULT', 'OUTPUT', 'STATE_CHANGE', 'TRUNCATED']
    .map((kind, i) => runEvent(i + 1, { kind, error: i === 2 || i === 4 })));
  expect(rows()).toHaveLength(6);
  fireEvent.click(screen.getByRole('button', { name: 'Agent' }));
  expect(rows().map((row) => row.querySelector('pre')?.textContent)).toEqual(['TEST-event-1', 'TEST-event-2', 'TEST-event-3', 'TEST-event-4']);
  fireEvent.click(screen.getByRole('button', { name: 'System' }));
  expect(rows().map((row) => row.querySelector('pre')?.textContent)).toEqual(['TEST-event-5', 'TEST-event-6']);
  fireEvent.click(screen.getByRole('button', { name: 'Errors' }));
  expect(rows().map((row) => row.querySelector('pre')?.textContent)).toEqual(['TEST-event-3', 'TEST-event-5']);
  fireEvent.click(screen.getByRole('button', { name: 'All' }));
  expect(rows()).toHaveLength(6);
});

it('renders an unknown kind in All, System and Errors, never Agent', () => {
  show();
  RunSocket.push([runEvent(1, { kind: 'TEST-FUTURE-KIND', error: true })]);
  expect(rows()[0]).toHaveTextContent('TEST-FUTURE-KIND');
  fireEvent.click(screen.getByRole('button', { name: 'System' }));
  expect(rows()[0]).toHaveTextContent('TEST-FUTURE-KIND');
  fireEvent.click(screen.getByRole('button', { name: 'Errors' }));
  expect(rows()[0]).toHaveTextContent('TEST-FUTURE-KIND');
  fireEvent.click(screen.getByRole('button', { name: 'Agent' }));
  expect(rows()).toHaveLength(0);
  expect(screen.getByText('No matching events')).toBeInTheDocument();
});

it('follows arrivals until scroll-up, resumes at the bottom, and obeys the tail toggle', () => {
  show();
  const viewport = screen.getByRole('region', { name: 'Run events' });
  Object.defineProperties(viewport, { scrollHeight: { configurable: true, value: 1000 }, clientHeight: { value: 200 } });
  const tail = screen.getByRole('button', { name: 'Tail' });
  RunSocket.push([runEvent(1)]);
  expect(tail).toHaveAttribute('aria-pressed', 'true');
  expect(viewport.scrollTop).toBe(1000);
  fireEvent.scroll(viewport, { target: { scrollTop: 400 } });
  expect(tail).toHaveAttribute('aria-pressed', 'false');
  RunSocket.push([runEvent(2)]);
  expect(viewport.scrollTop).toBe(400);
  fireEvent.scroll(viewport, { target: { scrollTop: 800 } });
  expect(tail).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(tail);
  expect(tail).toHaveAttribute('aria-pressed', 'false');
  viewport.scrollTop = 500;
  RunSocket.push([runEvent(3)]);
  expect(viewport.scrollTop).toBe(500);
  fireEvent.click(tail);
  expect(viewport.scrollTop).toBe(1000);
});

it('discloses dropped history after 2000 events and links the bounded REST transcript', () => {
  show();
  RunSocket.push(Array.from({ length: 2001 }, (_, i) => runEvent(i + 1)));
  expect(screen.getByRole('region', { name: 'Run events' }).querySelectorAll('li')).toHaveLength(2000);
  expect(screen.queryByText('TEST-event-1')).toBeNull();
  expect(screen.getByText(/Earlier events were dropped/)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'REST transcript' })).toHaveAttribute('href', `/api/runs/${encodeURIComponent(runRow().runId)}/transcript?limit=2000&before=2`);
}, 15000);

it('shows a policy-close message rather than an empty transcript', () => {
  show();
  act(() => RunSocket.latest.onclose?.({ code: 1008, reason: 'no such run' }));
  expect(screen.getByRole('alert')).toHaveTextContent('no such run');
  expect(screen.queryByText('No events yet')).toBeNull();
});

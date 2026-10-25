import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import PlanView from './PlanView';
import type { WrittenPlan } from './workDraftApi';

const DIGEST = 'c'.repeat(64);
const plan = (title: string): WrittenPlan => ({
  text: '{"schemaVersion":2}', sha256: DIGEST, comment: null,
  steps: [
    { id: 'TEST-s1', title, instruction: 'TEST-instruction-1', doneWhen: 'TEST-done-1' },
    { id: 'TEST-s2', title: 'TEST-step-2', instruction: 'TEST-instruction-2', doneWhen: 'TEST-done-2' },
  ],
});

afterEach(cleanup);

it('lists numbered titles and done-when lines', () => {
  render(<PlanView plan={plan('TEST-step-1')} />);
  const list = screen.getByRole('list', { name: 'Plan steps' });
  expect(list.tagName).toBe('OL');
  const steps = within(list).getAllByRole('listitem');
  expect(steps).toHaveLength(2);
  expect(steps[0]).toHaveTextContent('TEST-step-1');
  expect(steps[0]).toHaveTextContent('Done when: TEST-done-1');
  expect(steps[1]).toHaveTextContent('Done when: TEST-done-2');
  expect(screen.getByText(DIGEST.slice(0, 12), { exact: false })).toBeInTheDocument();
});

// A step title is model output from a ticket anyone could write: it is shown as text, never as markup.
it('renders titles as text', () => {
  const { container } = render(<PlanView plan={plan('<b>TEST-bold</b>')} />);
  expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('<b>TEST-bold</b>');
  expect(container.querySelector('b')).toBeNull();
});

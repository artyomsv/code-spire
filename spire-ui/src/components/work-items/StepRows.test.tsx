import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import StepRows from './StepRows';
import type { StepRow } from './workPreparationApi';

afterEach(cleanup);

const row = (overrides: Partial<StepRow> = {}): StepRow => ({
  index: 0, title: 'TEST-step-1', state: 'passed', head: '1'.repeat(40), summaryState: 'recorded',
  summary: { decisions: ['TEST-decision'], alternativesRejected: ['TEST-alternative'], deviations: ['TEST-deviation'] }, ...overrides,
});

it('renders summaries as text', () => {
  render(<StepRows rows={[row({ summary: { decisions: ['<b>TEST-bold</b>'], alternativesRejected: [], deviations: [] } })]} />);
  fireEvent.click(screen.getByRole('button', { name: /summary/i }));
  expect(screen.getByText('<b>TEST-bold</b>')).toBeInTheDocument();
  expect(document.querySelector('b')).toBeNull();
});

it("shows each step's state and an expandable summary", () => {
  render(<StepRows rows={[
    row(),
    row({ index: 1, title: 'TEST-step-2', state: 'empty', head: '1'.repeat(40) }),
    row({ index: 2, title: 'TEST-step-3', state: 'building', head: null, summaryState: null, summary: null }),
    row({ index: 3, title: 'TEST-step-4', state: 'waiting', head: null, summaryState: null, summary: null }),
  ]} />);
  const steps = screen.getAllByRole('listitem');
  expect(steps).toHaveLength(4);
  expect(steps[0]).toHaveTextContent('1. TEST-step-1');
  expect(steps[0]).toHaveTextContent('passed');
  expect(steps[0]).toHaveTextContent('1111111');
  expect(steps[1]).toHaveTextContent('empty');
  expect(steps[2]).toHaveTextContent('building');
  expect(steps[3]).toHaveTextContent('waiting');
  expect(screen.queryByText('TEST-decision')).toBeNull();
  fireEvent.click(within(steps[0]).getByRole('button', { name: /summary/i }));
  expect(within(steps[0]).getByText('TEST-decision')).toBeInTheDocument();
  expect(within(steps[0]).getByText('TEST-alternative')).toBeInTheDocument();
  expect(within(steps[0]).getByText('TEST-deviation')).toBeInTheDocument();
});

it('says plainly when a step recorded no decisions', () => {
  render(<StepRows rows={[row({ summaryState: 'summary_missing', summary: null })]} />);
  expect(screen.getByText('No decisions recorded')).toBeInTheDocument();
});

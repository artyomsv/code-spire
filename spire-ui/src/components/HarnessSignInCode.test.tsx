import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import HarnessSignInCode from './HarnessSignInCode';

// Placeholder link and code: this is about the buttons, not the vendor's real values.
const LINK = 'https://auth.example.test/device';
const CODE = 'TEST-12345';

let order: string[];
/** Settles the pending clipboard write, so a test decides when — and whether — the browser confirms. */
let settle: (ok: boolean) => void;

function clipboardThatWaits() {
  Object.assign(navigator, {
    clipboard: {
      writeText: vi.fn((text: string) => {
        order.push(`copy:${text}`);
        return new Promise<void>((resolve, reject) => { settle = ok => (ok ? resolve() : reject(new Error('TEST denied'))); });
      }),
    },
  });
}

beforeEach(() => {
  order = [];
  clipboardThatWaits();
  vi.spyOn(window, 'open').mockImplementation((url?: string | URL) => { order.push(`open:${String(url)}`); return null; });
});
afterEach(cleanup);

const view = (remaining: string | null = '13m 30s') =>
  render(<HarnessSignInCode verificationUri={LINK} userCode={CODE} remaining={remaining} />);

it('says the code was copied only once the browser confirms it', async () => {
  view();

  fireEvent.click(screen.getByRole('button', { name: 'Copy the code' }));
  expect(order).toEqual([`copy:${CODE}`]);
  expect(screen.getByRole('status')).not.toHaveTextContent('copied');

  await act(async () => settle(true));
  expect(screen.getByRole('status')).toHaveTextContent('Code copied.');
});

// Saying "copied" when it was not would send the operator to paste whatever was on the clipboard before.
it('says so when the browser refuses the copy', async () => {
  view();

  fireEvent.click(screen.getByRole('button', { name: 'Copy the code' }));
  await act(async () => settle(false));

  expect(screen.getByRole('status')).toHaveTextContent('The code could not be copied. Type it from here.');
});

it('says so when there is no clipboard to copy to', async () => {
  Object.assign(navigator, { clipboard: undefined });
  view();

  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Copy the code' })); });

  expect(screen.getByRole('status')).toHaveTextContent('could not be copied');
});

// The copy is STARTED before the page opens (a browser refuses it once the new tab has focus), and the
// page opens in the same click (a window opened after waiting is blocked as a pop-up).
it('starts the copy, opens the page in the same click, and reports the copy when it lands', async () => {
  view();

  fireEvent.click(screen.getByRole('button', { name: 'Copy the code and open the sign-in page' }));
  expect(order).toEqual([`copy:${CODE}`, `open:${LINK}`]);
  expect(window.open).toHaveBeenCalledWith(LINK, '_blank', 'noopener,noreferrer');

  await act(async () => settle(true));
  expect(screen.getByRole('status')).toHaveTextContent('Paste it on the page that just opened');
});

it('says the page opened without the code when that copy fails', async () => {
  view();

  fireEvent.click(screen.getByRole('button', { name: 'Copy the code and open the sign-in page' }));
  await act(async () => settle(false));

  expect(screen.getByRole('status')).toHaveTextContent('The page opened, but the code could not be copied');
});

// The worker accepts only the vendor's own host; this is the second lock, where a click would follow it.
it('offers no link and no open button for an address that is not https', () => {
  render(<HarnessSignInCode verificationUri="http://auth.example.test/device" userCode={CODE} remaining="13m 30s" />);

  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Copy the code and open the sign-in page' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Copy the code' })).toBeInTheDocument();
});

it('says when the code has run out', () => {
  view(null);

  expect(screen.getByRole('status')).toHaveTextContent('The code has expired.');
});

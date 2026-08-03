import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import RegisterPrDialog, { parsePrNumber } from './RegisterPrDialog';
import * as api from '../api';

const noop = () => {};

const renderDialog = () => render(<RegisterPrDialog onClose={noop} />);

const resolved: api.ResolvedUrl = {
  workspace: 'acme',
  slug: 'widgets',
  pr: 7,
  providerRegistered: true,
  providerType: 'github',
  providerName: 'Acme GitHub',
};

/**
 * The URL field debounces for 300ms before resolving, so the wait is skipped rather than slept
 * through. Real timers are handed back immediately afterwards: `waitFor` polls on `setTimeout`, so
 * leaving them faked past this point makes every later assertion hang until the test times out.
 */
async function pasteUrlAndSettle(url: string) {
  vi.useFakeTimers();
  fireEvent.change(screen.getByLabelText(/pull request url/i), { target: { value: url } });
  await act(async () => {
    vi.advanceTimersByTime(300);
  });
  vi.useRealTimers();
}

const fillManually = (workspace: string, slug: string, pr: string) => {
  fireEvent.change(screen.getByLabelText('Workspace'), { target: { value: workspace } });
  fireEvent.change(screen.getByLabelText('Repository'), { target: { value: slug } });
  fireEvent.change(screen.getByLabelText(/pr #/i), { target: { value: pr } });
};

const clickRegister = () => fireEvent.click(screen.getByRole('button', { name: /^register$/i }));

describe('parsePrNumber', () => {
  it('accepts a positive whole number', () => {
    expect(parsePrNumber(' 24 ')).toBe(24);
  });

  it('rejects anything that is not one', () => {
    expect(parsePrNumber('abc')).toBeNull();
    expect(parsePrNumber('0')).toBeNull();
    expect(parsePrNumber('-3')).toBeNull();
    expect(parsePrNumber('1.5')).toBeNull();
    expect(parsePrNumber('')).toBeNull();
  });
});

describe('RegisterPrDialog — form', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('refuses to register with the fields empty and does not call the API', async () => {
    const register = vi.spyOn(api, 'registerPr').mockResolvedValue({ reviewId: 'r-1' } as never);
    renderDialog();

    clickRegister();

    expect(await screen.findByText(/paste a pull request url, or fill in/i)).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  /** `Number('abc')` is NaN, which JSON-serializes to null and reaches the backend as a missing PR. */
  it('rejects a non-numeric PR number before it can reach the API', async () => {
    const register = vi.spyOn(api, 'registerPr').mockResolvedValue({ reviewId: 'r-1' } as never);
    renderDialog();

    fillManually('acme', 'widgets', 'twenty-four');
    clickRegister();

    expect(await screen.findByText(/pr # must be a positive whole number/i)).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it('fills the fields from a resolved pull request URL', async () => {
    vi.spyOn(api, 'resolvePrUrl').mockResolvedValue(resolved);
    renderDialog();

    await pasteUrlAndSettle('https://github.example.invalid/acme/widgets/pull/7');

    expect(screen.getByLabelText('Workspace')).toHaveValue('acme');
    expect(screen.getByLabelText('Repository')).toHaveValue('widgets');
    expect(screen.getByLabelText(/pr #/i)).toHaveValue('7');
    expect(screen.getByText(/will use github · acme github/i)).toBeInTheDocument();
  });

  /**
   * The cross-provider fix: the same workspace name can be registered on two SCMs (a GitHub org and
   * a Bitbucket workspace both called `acme`), and resolving by name alone picked the older one —
   * cross-wiring the review to the wrong SCM. The URL's own type disambiguates it.
   */
  it('passes the resolved provider type so a name shared across SCMs cannot cross-wire', async () => {
    vi.spyOn(api, 'resolvePrUrl').mockResolvedValue(resolved);
    const register = vi.spyOn(api, 'registerPr').mockResolvedValue({ reviewId: 'r-1' } as never);
    renderDialog();

    await pasteUrlAndSettle('https://github.example.invalid/acme/widgets/pull/7');
    clickRegister();

    await waitFor(() => expect(register).toHaveBeenCalled());
    expect(register.mock.calls[0][0]).toMatchObject({
      workspace: 'acme',
      slug: 'widgets',
      pr: 7,
      providerType: 'github',
    });
  });

  /**
   * ...but only while the fields still match what was resolved. Editing the repository after
   * pasting means the URL no longer describes what is being registered, so carrying its type
   * forward would assert a platform the operator did not choose.
   */
  it('drops the resolved provider type once the fields no longer match the URL', async () => {
    vi.spyOn(api, 'resolvePrUrl').mockResolvedValue(resolved);
    const register = vi.spyOn(api, 'registerPr').mockResolvedValue({ reviewId: 'r-1' } as never);
    renderDialog();

    await pasteUrlAndSettle('https://github.example.invalid/acme/widgets/pull/7');
    fireEvent.change(screen.getByLabelText('Repository'), { target: { value: 'gadgets' } });
    clickRegister();

    await waitFor(() => expect(register).toHaveBeenCalled());
    expect(register.mock.calls[0][0].providerType).toBeUndefined();
  });

  /**
   * A URL that resolves but names an unregistered workspace must say so — the register call would
   * otherwise be accepted and fail later with no provider to run it.
   */
  it('warns when the resolved workspace has no registered provider', async () => {
    vi.spyOn(api, 'resolvePrUrl').mockResolvedValue({
      ...resolved,
      providerRegistered: false,
      providerType: null,
      providerName: null,
    });
    renderDialog();

    await pasteUrlAndSettle('https://github.example.invalid/acme/widgets/pull/7');

    expect(screen.getByText(/no provider registered for/i)).toBeInTheDocument();
  });

  /** An unparseable URL leaves the fields blank; without this hint the dialog just looks broken. */
  it('explains an unrecognised URL rather than silently leaving the fields blank', async () => {
    vi.spyOn(api, 'resolvePrUrl').mockRejectedValue(new Error('not a PR URL'));
    renderDialog();

    await pasteUrlAndSettle('https://github.example.invalid/acme/widgets');

    expect(screen.getByText(/not a pr\/mr url/i)).toBeInTheDocument();
    expect(screen.getByLabelText('Workspace')).toHaveValue('');
  });

  it('surfaces a register failure instead of reporting success', async () => {
    vi.spyOn(api, 'registerPr').mockRejectedValue(new Error('No provider is registered for acme.'));
    renderDialog();

    fillManually('acme', 'widgets', '7');
    clickRegister();

    expect(await screen.findByText(/no provider is registered for acme/i)).toBeInTheDocument();
    expect(screen.queryByText(/^registered /i)).not.toBeInTheDocument();
  });
});

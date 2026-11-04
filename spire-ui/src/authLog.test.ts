import { afterEach, describe, expect, it, vi } from 'vitest';
import { AUTH_LOG_KEY, AUTH_LOG_MAX, readAuthLog, recordAuthEvent, recordPageLoad } from './authLog';

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
});

const silenceConsole = () => vi.spyOn(console, 'info').mockImplementation(() => undefined);

describe('the sign-in diagnostic log', () => {
  it('keeps each event with its time, across reads', () => {
    silenceConsole();
    recordAuthEvent('login', 'TEST-reason');

    const [entry] = readAuthLog();
    expect(entry.event).toBe('login');
    expect(entry.detail).toBe('TEST-reason');
    expect(Number.isNaN(Date.parse(entry.at))).toBe(false);
  });

  it('prints each event to the console as it happens', () => {
    const info = silenceConsole();
    recordAuthEvent('login', 'TEST-reason');

    expect(info).toHaveBeenCalledWith('[spire auth]', expect.any(String), 'login', 'TEST-reason');
  });

  it('keeps only the newest entries', () => {
    silenceConsole();
    for (let i = 0; i < AUTH_LOG_MAX + 5; i++) recordAuthEvent('login', `TEST-${i}`);

    const log = readAuthLog();
    expect(log).toHaveLength(AUTH_LOG_MAX);
    expect(log[0].detail).toBe('TEST-5');
    expect(log[AUTH_LOG_MAX - 1].detail).toBe(`TEST-${AUTH_LOG_MAX + 4}`);
  });

  it('starts again from a stored value it cannot read', () => {
    silenceConsole();
    sessionStorage.setItem(AUTH_LOG_KEY, 'TEST-not-json');
    recordAuthEvent('login', 'TEST-reason');

    expect(readAuthLog().map((e) => e.detail)).toEqual(['TEST-reason']);
  });

  it('never throws when the browser blocks site data', () => {
    silenceConsole();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('TEST-blocked');
    });

    expect(() => recordAuthEvent('login', 'TEST-reason')).not.toThrow();
  });
});

describe('recording a page load', () => {
  /** The identity provider sends the code and state back in the query: neither may be stored. */
  it('stores where the page came from without its query', () => {
    silenceConsole();
    recordPageLoad('navigate', 'http://localhost:34567/realms/TEST/auth?code=TEST-secret&state=TEST-state');

    const [entry] = readAuthLog();
    expect(entry.event).toBe('page load');
    expect(entry.detail).toBe('navigate from http://localhost:34567/realms/TEST/auth');
    expect(entry.detail).not.toContain('TEST-secret');
  });

  it('says so when the page came from nowhere', () => {
    silenceConsole();
    recordPageLoad('reload', '');

    expect(readAuthLog()[0].detail).toBe('reload from (none)');
  });

  it('prints the whole log so far, so a console opened after a reload still shows it', () => {
    const info = silenceConsole();
    recordAuthEvent('login', 'TEST-earlier');
    info.mockClear();

    recordPageLoad('navigate', '');

    expect(info).toHaveBeenCalledWith('[spire auth] recent events', expect.arrayContaining([
      expect.objectContaining({ detail: 'TEST-earlier' }),
    ]));
  });
});

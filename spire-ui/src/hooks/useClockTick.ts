import { useEffect, useState } from 'react';

/** A relative age ("3m ago") changes on the minute, so a faster tick would only re-render the same text. */
export const CLOCK_TICK_MS = 60_000;

/**
 * Re-renders the caller every `periodMs` while `isActive`, so text computed from `Date.now()` keeps up with the clock.
 *
 * <p>Presentation only: it reads no data and asks no server anything, so it is not the polling the live-data rules
 * forbid. The interval runs only while active and is cleared when that ends or the caller unmounts.
 */
export function useClockTick(isActive: boolean, periodMs: number = CLOCK_TICK_MS): number {
  const [ticks, setTicks] = useState(0);
  useEffect(() => {
    if (!isActive) return undefined;
    const timer = setInterval(() => setTicks((count) => count + 1), periodMs);
    return () => clearInterval(timer);
  }, [isActive, periodMs]);
  return ticks;
}

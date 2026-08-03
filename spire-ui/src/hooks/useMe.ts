import { useEffect, useState } from 'react';
import { fetchMe, type Me } from '../auth';

/**
 * The current operator's session, read once on mount.
 *
 * <p>Not polled. A session that expires mid-visit is discovered by the sockets and by the next API
 * call, both of which already redirect to a login — a timer here would add a third way to learn the
 * same thing, and the least timely of the three.
 *
 * <p>Returns `null` until the answer arrives, and stays `null` if it never does. Callers must treat
 * that as "unknown", not "no permission": hiding controls on an unreachable `/api/me` would make a
 * transient blip look like a permissions change, and the API is the real authority anyway.
 */
export function useMe(): Me | null {
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchMe().then((value) => {
      if (!cancelled) setMe(value);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  return me;
}

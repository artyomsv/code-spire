import { useEffect, useState } from 'react';
import { fetchMe, type Me } from '../auth';

export interface Session {
  /** The signed-in operator, or null while unknown — see `loading` to tell which. */
  me: Me | null;
  /** True until `/api/me` has answered. Nothing about permissions is decided while this is true. */
  loading: boolean;
}

/**
 * The current operator's session, read once on mount.
 *
 * <p>Not polled. A session that expires mid-visit is discovered by the sockets and by the next API
 * call, both of which already redirect to a login — a timer here would add a third way to learn the
 * same thing, and the least timely of the three.
 *
 * <p>`loading` exists because `me === null` cannot say whether the answer has not arrived or is not
 * coming, and the two want different interfaces: "still loading" is a page mid-render, "unreachable"
 * is a page that has to decide without an answer. Both **deny** — but only one of them should say so
 * out loud, which is why a guard needs to tell them apart. Without this flag the choice was between
 * flashing the admin surface at a viewer and accusing an admin of lacking their own role.
 */
export function useMe(): Session {
  const [session, setSession] = useState<Session>({ me: null, loading: true });

  useEffect(() => {
    let cancelled = false;
    void fetchMe().then((value) => {
      if (!cancelled) setSession({ me: value, loading: false });
    });
    return () => {
      cancelled = true;
    };
  }, []);

  return session;
}

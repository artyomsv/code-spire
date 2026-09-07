import { Navigate, useLocation } from 'react-router';

/**
 * A route that moved. `Navigate` alone drops the query, and the query is load-bearing here: the
 * attention panel deep-links to one record with `?edit=<id>`, which the settings screens consume
 * through `useEditDeepLink`. `replace` keeps the dead address out of history so Back does not walk
 * into a second redirect.
 */
export default function RedirectKeepingQuery({ to }: { to: string }) {
  const { search } = useLocation();
  return <Navigate to={{ pathname: to, search }} replace />;
}

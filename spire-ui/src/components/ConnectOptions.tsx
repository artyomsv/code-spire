import { useEffect, useState } from 'react';
// Aliased: `Link` is react-router's navigation component throughout this codebase, and a second
// meaning for that name in a file that also routes would be read wrong at a glance.
import { Link2 as LinkIcon } from 'lucide-react';
import { ConnectablePlatform, connectStartUrl, fetchConnectablePlatforms } from '../api';
import { useMe } from '../hooks/useMe';

/**
 * The operator proving, themselves, which SCM account is theirs (FR-11).
 *
 * <p>A plain link, not a fetch. The server answers with a redirect to the platform's sign-in, and a
 * cross-origin redirect reaches `fetch` as an opaque failure — the same reason the dashboard's own
 * login is a whole-window navigation.
 *
 * <p>A platform with no sign-in application configured is shown as unavailable rather than hidden.
 * Hiding it would leave an operator with no idea why the account they use is not offered, and the
 * thing they need to know is that an admin has one step to take.
 */
export function ConnectOptions() {
  const [platforms, setPlatforms] = useState<ConnectablePlatform[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { me } = useMe();

  useEffect(() => {
    fetchConnectablePlatforms()
      .then(setPlatforms)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  if (error) {
    return (
      <p className="prov-note an-error" role="alert">
        Could not load the sign-in options — {error}
      </p>
    );
  }
  if (platforms === null) {
    return <p className="prov-note">Loading…</p>;
  }

  // A sign-in proves WHOSE account it is, so with authentication off there is nothing to attach the
  // proof to and the server refuses. Said here rather than left to that refusal: the operator would
  // otherwise click a button, visit the platform, authorize, and come back to a decline.
  if (me && !me.authEnabled) {
    return (
      <p className="prov-note">
        Authentication is off in this deployment, so there is no operator identity to attach an SCM
        account to. Start the stack with the identity-provider overlay to use this.
      </p>
    );
  }

  const available = platforms.filter((p) => p.configured && !p.linked);
  const unavailable = platforms.filter((p) => !p.configured && !p.linked);

  return (
    <div className="connect-options">
      {available.map((platform) => (
        <a key={platform.providerType} className="btn" href={connectStartUrl(platform.providerType)}>
          <LinkIcon size={13} /> Connect my {platform.providerType} account
        </a>
      ))}
      {available.length === 0 && unavailable.length > 0 && (
        <p className="prov-note">
          No platform is set up for sign-in yet. An admin registers an application under
          <strong> Settings → Operators</strong>, and then this becomes a single click.
        </p>
      )}
    </div>
  );
}

/**
 * What came back from a sign-in attempt.
 *
 * <p>A fixed vocabulary rather than the platform's own words: an OAuth error response quotes back
 * the parameters it rejected, and on this path one of those is the client secret. Each outcome says
 * what to do next, because "it did not work" sends an operator to an admin who has nothing to go on.
 */
export function connectOutcome(code: string | null): { text: string; ok: boolean } | null {
  switch (code) {
    case null:
      return null;
    case 'connected':
      return { text: 'Your SCM account is linked. Your activity is below.', ok: true };
    case 'declined':
      return { text: 'You declined at the sign-in screen, so nothing was linked.', ok: false };
    case 'expired':
      return { text: 'That sign-in took too long. Start it again.', ok: false };
    case 'mismatch':
      return { text: 'That sign-in belonged to a different session. Start it again from here.', ok: false };
    case 'refused':
      return { text: 'The platform refused the sign-in. An admin should check the application’s client id, secret and redirect address.', ok: false };
    case 'unconfigured':
      return { text: 'No sign-in application is set up for that platform yet.', ok: false };
    case 'noidentity':
      return {
        text: 'This deployment has authentication switched off, so there is no operator identity to link an account to.',
        ok: false,
      };
    case 'noaccount':
      return { text: 'That sign-in returned no user account, so there was nothing to link.', ok: false };
    default:
      // Never assume an unknown code means success -- a reassuring default for a case nobody
      // handled is how a refused review once rendered as five green segments under "done".
      return { text: 'The sign-in did not complete.', ok: false };
  }
}

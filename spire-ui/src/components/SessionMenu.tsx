import { useState } from 'react';
import { LogOut, ShieldCheck, UserRound } from 'lucide-react';
import { goToLogout, type Me } from '../auth';
import Tooltip from './Tooltip';

/**
 * Who is signed in, and how to stop being them.
 *
 * <p>Both halves were missing entirely. {@link goToLogout} was written, tested and reachable from
 * nowhere — the whole three-prefix sign-out existed with no control that called it — and nothing on
 * screen said whose session was in use. An operator could neither tell which account they held nor
 * change it, which makes checking that a viewer really is refused something you cannot do without
 * a private browsing window.
 *
 * <p><b>Dev is a distinct state, not a blank one.</b> With authentication off there is no account
 * and nothing to sign out of, so this says so rather than rendering an empty name or a button that
 * would do nothing — the same rule the unlinked activity screen follows.
 */
export default function SessionMenu({ me }: { me: Me | null }) {
  const [open, setOpen] = useState(false);

  if (!me) {
    return null;
  }

  // `Me.roles` is typed `string[]`, but it arrives as runtime JSON and nothing checks the shape on
  // the way in. A response without it therefore type-checks everywhere and throws HERE, in the
  // topbar, which unmounts the whole shell -- a blank page in place of the dashboard. The same
  // class as a `ReviewDetail` type derived from a sibling that the wire never guaranteed.
  const roles = Array.isArray(me.roles) ? me.roles.filter((role) => role.startsWith('spire-')) : [];
  const isAdmin = roles.includes('spire-admin');
  const label = me.authEnabled ? me.user || 'Signed in' : 'Authentication off';

  return (
    <div className="attention session-menu">
      <Tooltip label={me.authEnabled ? `Signed in as ${label}` : 'Authentication is off'}>
        <button
          className="iconbtn"
          data-testid="session-toggle"
          aria-label={me.authEnabled ? `Session: ${label}` : 'Session: authentication is off'}
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          {isAdmin ? <ShieldCheck size={17} /> : <UserRound size={17} />}
        </button>
      </Tooltip>

      {open && (
        <div className="attention-panel session-panel" role="dialog" aria-label="Session">
          <div className="session-who">
            <span className="session-name">{label}</span>
            {me.authEnabled ? (
              <span className="session-roles">
                {roles.length > 0 ? roles.map(readableRole).join(' · ') : 'no role'}
              </span>
            ) : (
              <span className="session-roles">
                Every action is permitted here. A packaged deployment refuses to start this way.
              </span>
            )}
          </div>

          {me.authEnabled && me.subject && (
            <div className="session-subject">
              Operator id: <code>{me.subject}</code>
            </div>
          )}

          {me.authEnabled ? (
            <button className="btn-ghost session-signout" onClick={() => void goToLogout()}>
              <LogOut size={13} /> Sign out
            </button>
          ) : (
            <p className="session-note">
              There is no account to sign out of. Start the stack with the identity-provider overlay
              to sign in as a real operator.
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/** The role as an operator would say it, not as the token spells it. */
function readableRole(role: string): string {
  switch (role) {
    case 'spire-admin':
      return 'Administrator';
    case 'spire-viewer':
      return 'Viewer';
    default:
      return role;
  }
}

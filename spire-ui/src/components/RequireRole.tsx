import type { ReactNode } from 'react';
import { useMe } from '../hooks/useMe';
import { hasRole, type SpireRole } from '../auth';

interface RequireRoleProps {
  /** Required, and typed to the roles that exist — a guard given nothing would admit everyone,
   *  which reads as protection while providing none. */
  role: SpireRole;
  children: ReactNode;
}

/**
 * Renders its children only for an operator holding `role`.
 *
 * <p>Three states, and the permissive one is never the default:
 * <ul>
 *   <li><b>unknown</b> — `/api/me` has not answered. Decide nothing and say so; do not render the
 *       children on the assumption they will turn out to be allowed.</li>
 *   <li><b>refused</b> — answered, and the role is not held. Say that plainly rather than
 *       redirecting: a bounce to another screen is indistinguishable from a broken link, and this
 *       page is reachable by URL precisely because its nav entry is hidden.</li>
 *   <li><b>allowed</b> — render.</li>
 * </ul>
 *
 * <p>This is the interface's side of the boundary only. Every screen it guards loads by calling an
 * endpoint that carries `@RolesAllowed`, so the refusal is the API's; this exists so a viewer meets
 * an explanation instead of a page of failed requests.
 */
export default function RequireRole({ role, children }: RequireRoleProps) {
  const { me, loading } = useMe();

  if (loading) {
    return (
      <section className="content">
        <div style={{ color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
      </section>
    );
  }

  if (!hasRole(me, role)) {
    return (
      <section className="content">
        <div className="wh-empty">
          <div className="wh-empty-title">Not available to your role</div>
          <p className="wh-empty-text">
            This page manages how reviews are configured, which needs an administrator. Your account
            can read reviews.
          </p>
        </div>
      </section>
    );
  }

  return <>{children}</>;
}

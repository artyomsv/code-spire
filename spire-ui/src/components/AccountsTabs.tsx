/**
 * The two tabs of Settings → Accounts. Machine accounts hold a token and a role; People are the
 * operators the identity provider knows and the SCM accounts they proved are theirs. They share a
 * page and never a table — one mixed list is the shape ADR-038 exists to prevent.
 *
 * <p>Plain anchors with the rail's hash hrefs rather than router links: `SettingsOperators` is
 * mounted without a router in its own tests, and a tab strip must not be the reason a screen needs
 * one.
 */
export type AccountsTab = 'machine' | 'people';

export default function AccountsTabs({ active }: { active: AccountsTab }) {
  return (
    <nav className="tabs" aria-label="Accounts">
      <a
        className={active === 'machine' ? 'tab active' : 'tab'}
        href="#/settings/accounts"
        aria-current={active === 'machine' ? 'page' : undefined}
      >
        Machine accounts
      </a>
      <a
        className={active === 'people' ? 'tab active' : 'tab'}
        href="#/settings/accounts/people"
        aria-current={active === 'people' ? 'page' : undefined}
      >
        People
      </a>
    </nav>
  );
}

import type { ServingLookup } from '../hooks/useServingAccounts';
import { servingChip } from './servingAccounts';

type Role = 'reviewer' | 'factory';

interface Props {
  role: Role;
  lookup: ServingLookup | undefined;
}

/**
 * One role's account for one repository row, as a chip on one line: which account serves this row
 * as reviewer, and which as the factory that pushes. The state behind the colour is the chip's
 * tooltip, so the cell stays the height of a table row.
 *
 * <p>The chip names its own role. Both chips now sit in one Accounts column — two columns of
 * anonymous names did not fit the card — and beside its twin an unlabelled name says nothing about
 * which of the two jobs it does.
 *
 * <p>Nothing is probed from here. Verifying belongs where the thing verified is edited — an
 * account's token by Check on Settings → Accounts, a repository by Verify in the webhook form. A
 * Verify on the chip named neither, so "what exactly did that verify" had no answer on the screen;
 * and beside a push identity a green result reads as "can push", which a read-only probe never
 * established.
 */
export default function ServingCell({ role, lookup }: Props) {
  const chip = servingChip(lookup?.data?.[role], lookup?.error ?? null);
  return (
    <div className="serving-cell">
      <span className={`pill ${chip.pill}`} title={chip.title}>
        <span className="glyph"></span>
        {`${role === 'factory' ? 'Factory' : 'Reviewer'} · ${chip.label}`}
      </span>
    </div>
  );
}

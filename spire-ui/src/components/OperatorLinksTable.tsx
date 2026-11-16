import { useState } from 'react';
import { Trash2 } from 'lucide-react';
import { ObservedAuthor, OperatorIdentityLink, SeenOperator, unlinkOperatorIdentity } from '../api';
import ConfirmDialog from './ConfirmDialog';
import { NameWithId } from './NameWithId';

interface Props {
  links: OperatorIdentityLink[];
  operators: SeenOperator[];
  candidates: ObservedAuthor[];
  onUnlinked: () => void;
}

/**
 * The operator-to-account links, each with an Unlink that waits for a yes: an unlinked operator stops seeing their own
 * numbers for that account until somebody links it again.
 */
export function OperatorLinksTable({ links, operators, candidates, onUnlinked }: Props) {
  const [unlinking, setUnlinking] = useState<OperatorIdentityLink | null>(null);

  // A failed unlink stays in the dialog, so it can be tried again or cancelled.
  const unlink = async (link: OperatorIdentityLink) => {
    await unlinkOperatorIdentity(link.oidcSubject, link.providerType);
    onUnlinked();
  };

  return (
    <>
      <table className="prov-table">
        <thead>
          <tr>
            <th>Operator</th>
            <th>Platform</th>
            <th>SCM account</th>
            <th className="cell-r" />
          </tr>
        </thead>
        <tbody>
          {links.map((link) => (
            <tr key={`${link.oidcSubject}-${link.providerType}`}>
              <td>
                <NameWithId name={operatorName(operators, link.oidcSubject)} id={link.oidcSubject} />
              </td>
              <td>{link.providerType}</td>
              <td>
                <NameWithId name={nameFor(candidates, link)} id={link.authorId} />
              </td>
              <td className="cell-r">
                <button
                  className="iconbtn"
                  aria-label={`Unlink ${link.oidcSubject} on ${link.providerType}`}
                  onClick={() => setUnlinking(link)}
                >
                  <Trash2 size={15} />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {unlinking && (
        <ConfirmDialog
          title="Unlink operator account"
          message={`Unlink ${operatorName(operators, unlinking.oidcSubject)} from ${unlinking.providerType} · ${nameFor(candidates, unlinking)}? They no longer see their own activity for that account until it is linked again.`}
          confirmLabel="Unlink"
          busyLabel="Unlinking…"
          danger
          onConfirm={() => unlink(unlinking)}
          onClose={() => setUnlinking(null)}
        />
      )}
    </>
  );
}

/**
 * Name first, username second, and the opaque subject only when there is nothing else to show.
 *
 * The username is empty when the identity provider's login is an email address (it is never stored),
 * so the name stands alone then.
 */
export function labelFor(operator: SeenOperator): string {
  if (operator.displayName && operator.username && operator.displayName !== operator.username) {
    return `${operator.displayName} · ${operator.username}`;
  }
  return operator.displayName || operator.username || operator.subject;
}

function operatorName(operators: SeenOperator[], subject: string): string {
  const match = operators.find((o) => o.subject === subject);
  return match ? labelFor(match) : subject;
}

/**
 * The login the operator proved by signing in, else the name the reviews recorded, else the id
 * (a link made by hand for an author no review has seen).
 */
function nameFor(candidates: ObservedAuthor[], link: OperatorIdentityLink): string {
  if (link.authorHandle) return link.authorHandle;
  const match = candidates.find(
    (c) => c.providerType === link.providerType && c.authorId === link.authorId,
  );
  return match ? match.displayName : link.authorId;
}

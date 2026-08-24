import { FileText, GitBranch, Globe } from 'lucide-react';
import { GLOBAL_SCOPE, type PromptView } from '../api';
import { provenanceLabel } from './promptKinds';

/** Icon per provenance level -- reinforces the text line rather than replacing it. */
function ProvenanceIcon({ inheritedFrom }: { inheritedFrom: PromptView['inheritedFrom'] }) {
  if (inheritedFrom === 'repo') return <GitBranch size={13} aria-hidden="true" />;
  if (inheritedFrom === 'global') return <Globe size={13} aria-hidden="true" />;
  return <FileText size={13} aria-hidden="true" />;
}

/** Unmissable by design: a repo scope showing global's (or the default's) text looks identical to
 *  one with its own override unless this line says otherwise. */
export default function ProvenanceLine({ scope, inheritedFrom }: { scope: string; inheritedFrom: PromptView['inheritedFrom'] }) {
  return (
    <div className={`prompt-provenance is-${inheritedFrom}`}>
      <ProvenanceIcon inheritedFrom={inheritedFrom} />
      <span>{provenanceLabel(scope, inheritedFrom)}</span>
      {scope !== GLOBAL_SCOPE && <span className="prompt-provenance-scope">{scope}</span>}
    </div>
  );
}

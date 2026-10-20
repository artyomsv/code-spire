import type { RepositoryInput } from './repositoriesApi';

interface Props {
  fields: RepositoryInput;
  onChange: (next: Partial<RepositoryInput>) => void;
}

/** Whether the repository is served at all, and whether the reviewer may change a pull request's merge state. */
export default function RepositorySwitches({ fields, onChange }: Props) {
  return <>
    <label className="field-check"><input type="checkbox" checked={fields.enabled} onChange={e => onChange({ enabled: e.target.checked })} /><span>Enabled</span></label>
    <label className="field-check"><input type="checkbox" checked={fields.reviewerMayApprove} onChange={e => onChange({ reviewerMayApprove: e.target.checked })} /><span>Reviewer may approve</span></label>
    <p className="prov-sub">Off: every review is posted as a comment and the bot never changes a pull request's merge state.</p>
  </>;
}

import { BLOCK_APPROVAL_AT_LABEL, type BlockApprovalAt } from '../../api';
import SettingField from '../SettingField';
import type { RepositoryInput } from './repositoriesApi';

interface Props {
  fields: RepositoryInput;
  onChange: (next: Partial<RepositoryInput>) => void;
}

const LEVELS: BlockApprovalAt[] = ['BLOCKER', 'MAJOR', 'MINOR'];

/**
 * Whether the repository is served at all, whether the reviewer may change a pull request's merge state, and which
 * open findings block approval (V115).
 */
export default function RepositorySwitches({ fields, onChange }: Props) {
  return <>
    <label className="field-check"><input type="checkbox" checked={fields.enabled} onChange={e => onChange({ enabled: e.target.checked })} /><span>Enabled</span></label>
    <label className="field-check"><input type="checkbox" checked={fields.reviewerMayApprove} onChange={e => onChange({ reviewerMayApprove: e.target.checked })} /><span>Reviewer may approve</span></label>
    <p className="prov-sub">Off: every review is posted as a comment and the bot never changes a pull request's merge state.</p>
    <SettingField label="Block approval at" scope="repository"
      hint="The lowest severity of an open finding that stops the review from approving.">
      <select aria-label="Block approval at" value={fields.blockApprovalAt}
        onChange={e => onChange({ blockApprovalAt: e.target.value as BlockApprovalAt })}>
        {LEVELS.map(level => <option key={level} value={level}>{BLOCK_APPROVAL_AT_LABEL[level]}</option>)}
      </select></SettingField>
    <p className="prov-sub">Open findings at this level or higher make the review Request changes and hold a factory land. Info and nits never block.</p>
  </>;
}

import SettingField from '../SettingField';
import type { RepositoryInput, StartReview } from './repositoriesApi';

interface Props {
  fields: RepositoryInput;
  onChange: (next: Partial<RepositoryInput>) => void;
}

export const START_REVIEW_LABEL: Record<StartReview, string> = {
  IMMEDIATELY: 'Immediately',
  AFTER_CHECKS: 'After checks finish',
};

/** The info control's explanation (spire-ui/docs/WIDGETS.md: explanations sit on the info control, not under it). */
export const START_REVIEW_HINT = 'When a review of a new commit starts. After checks finish: a red build gets no review '
  + 'and no charge, shown as "Skipped: checks failed"; checks still pending after 60 minutes start a review '
  + 'that never approves.';

const CHOICES: StartReview[] = ['IMMEDIATELY', 'AFTER_CHECKS'];

/**
 * "Start review" (V121, docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md): review a new commit at once,
 * or only once every check on it finished green. GitHub only, beside "Verdict labels"; the caller hides it elsewhere.
 */
export default function RepositoryStartReview({ fields, onChange }: Props) {
  return (
    <SettingField label="Start review" scope="repository" hint={START_REVIEW_HINT}>
      <select aria-label="Start review" value={fields.startReview ?? 'IMMEDIATELY'}
        onChange={e => onChange({ startReview: e.target.value as StartReview })}>
        {CHOICES.map(choice => <option key={choice} value={choice}>{START_REVIEW_LABEL[choice]}</option>)}
      </select>
    </SettingField>
  );
}

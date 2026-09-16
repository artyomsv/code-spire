import { beforeEach, expect, it, vi } from 'vitest';
import { apiFetch } from '../../auth';
import * as api from './workPreparationApi';
vi.mock('../../auth', () => ({ apiFetch: vi.fn() }));

const artifact: api.Artifact = { sha256: 'a'.repeat(64),
  location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: 'TEST-71' }, issueKey: '71', link: 'https://TEST.example/issues/71' } };
const input = { expectedRevision: 3, specification: artifact, plan: artifact, baseBranch: 'main', baseCommit: 'b'.repeat(40), harness: 'TEST-harness', model: 'TEST-model' };
const refusal = (reason: string, detail?: string) => new Response(JSON.stringify(detail ? { reason, detail } : { reason }), { status: 409 });
beforeEach(() => vi.mocked(apiFetch).mockReset().mockResolvedValue(new Response('{"reason":"artifacts_registered"}', { status: 200 })));

// One reason covers five plan rules. Without the rule the operator is told only that the plan is
// wrong, which is what sent a real registration around the tracker three times.
it.each([
  ['plan_not_json', /not valid JSON/],
  ['plan_schema_version', /"schemaVersion": 1/],
  ['plan_specification_mismatch', /different specification version/],
  ['plan_step_count', /exactly one step/],
  ['plan_step_fields', /id and an instruction/],
])('says what to change when a registration is refused for %s', async (detail, sentence) => {
  vi.mocked(apiFetch).mockResolvedValue(refusal('single_step_plan_required', detail));
  await expect(api.registerPreparation('TEST-item', input)).rejects.toThrow(sentence);
});

it('names which ticket moved when the artifacts changed', async () => {
  vi.mocked(apiFetch).mockResolvedValue(refusal('artifacts_changed', 'plan_changed'));
  await expect(api.registerPreparation('TEST-item', input)).rejects.toThrow('The plan ticket changed after it was checked. Check the references again.');
});

// A reason with no rule still has to read as a sentence, not as the machine word.
it('falls back to the reason when the refusal names no rule', async () => {
  vi.mocked(apiFetch).mockResolvedValue(refusal('preparation_unavailable'));
  await expect(api.registerPreparation('TEST-item', input)).rejects.toThrow('preparation_unavailable');
  vi.mocked(apiFetch).mockResolvedValue(refusal('artifacts_unavailable'));
  await expect(api.registerPreparation('TEST-item', input)).rejects.toThrow('The tracker artifacts could not be read. Check the references and source account.');
});

// A gateway or proxy answers with its own body, which is not JSON at all.
it('keeps the status and body when the answer is not a refusal this API shaped', async () => {
  vi.mocked(apiFetch).mockResolvedValue(new Response('<html>TEST-gateway timeout</html>', { status: 504 }));
  await expect(api.resolveArtifact('TEST-item', '71')).rejects.toThrow('504: <html>TEST-gateway timeout</html>');
});

it('registers the checked versions at the item preparation endpoint', async () => {
  await expect(api.registerPreparation('TEST-item', input)).resolves.toEqual({ reason: 'artifacts_registered' });
  expect(apiFetch).toHaveBeenCalledWith('/api/work-items/TEST-item/preparation', expect.objectContaining({ method: 'POST', body: JSON.stringify(input) }));
});

import { useQuery } from '@tanstack/react-query';
import { fetchLlmDefaultModel, fetchLlmModels, fetchLlmProviders, llmDefaultModelKey, llmProvidersKey } from '../../api';
import type { RepositoryInput } from './repositoriesApi';
import RepositoryReviewProvider from './RepositoryReviewProvider';
import RepositoryReviewModel from './RepositoryReviewModel';
import { preparationKeys } from '../work-items/workQueries';

interface Props {
  fields: RepositoryInput;
  /** The repository reviews on a harness: the provider select shows that choice. */
  harness: boolean;
  /** A provider id, null for the global default, or the harness choice. */
  onChoose: (choice: string | null) => void;
  onModel: (model: string | null) => void;
}

/** The provider read failed: the message, never "none set". */
function failureOf(error: unknown): string | null {
  if (!error) return null;
  return error instanceof Error ? error.message : String(error);
}

/**
 * "Review with" on the API: the provider select, and once a provider is named, its model select. Reads the providers,
 * the models and the default model through the keys Settings → LLM shares, so a save there reaches these selects.
 */
export default function RepositoryApiReview({ fields, harness, onChoose, onModel }: Props) {
  const llmProviders = useQuery({ queryKey: llmProvidersKey, queryFn: () => fetchLlmProviders() });
  const llmModels = useQuery({ queryKey: preparationKeys.llmModels(), queryFn: () => fetchLlmModels() });
  const defaultModel = useQuery({ queryKey: llmDefaultModelKey, queryFn: () => fetchLlmDefaultModel() });
  const named = llmProviders.data?.find(p => p.id === fields.reviewLlmProviderId) ?? null;
  return <>
    <RepositoryReviewProvider value={fields.reviewLlmProviderId} harness={harness} providers={llmProviders.data}
      failure={failureOf(llmProviders.error)} onRetry={() => void llmProviders.refetch()}
      defaultModel={defaultModel.isSuccess ? defaultModel.data.model : undefined} onChange={onChoose} />
    {fields.reviewLlmProviderId !== null && <RepositoryReviewModel providerType={named?.type ?? null}
      value={fields.reviewLlmModel} models={llmModels.data} onChange={onModel} />}
    {fields.reviewLlmProviderId !== null && !fields.reviewLlmModel
      && <p className="factory-note" role="status">To save, choose a review model.</p>}
  </>;
}

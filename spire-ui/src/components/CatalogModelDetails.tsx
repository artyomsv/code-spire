import type { CatalogModelView } from '../api';
import { formatRate } from '../llmPricing';

interface CatalogModelDetailsProps {
  model: CatalogModelView;
}

const CAPABILITIES: { key: 'tools' | 'structuredOutput' | 'imageInput' | 'promptCache'; label: string }[] = [
  { key: 'tools', label: 'Tools' },
  { key: 'structuredOutput', label: 'Structured output' },
  { key: 'imageInput', label: 'Image input' },
  { key: 'promptCache', label: 'Prompt cache' },
];

/** A number of tokens, or a dash when the vendor's page does not state it. */
function tokens(value: number | null): string {
  return value == null ? '—' : value.toLocaleString();
}

function capabilities(model: CatalogModelView): string {
  if (!model.capabilities) return '—';
  const on = CAPABILITIES.filter(({ key }) => model.capabilities?.[key]).map(({ label }) => label);
  return on.length > 0 ? on.join(', ') : 'none stated';
}

function lifecycle(model: CatalogModelView): string {
  const stage = model.lifecycle;
  if (!stage) return '—';
  const parts: string[] = [stage.status.toLowerCase()];
  if (stage.deprecationDate) parts.push(`deprecated ${stage.deprecationDate}`);
  if (stage.retirementDate) parts.push(`retires ${stage.retirementDate}`);
  if (stage.successor) parts.push(`successor ${stage.successor}`);
  return parts.join(' · ');
}

function cacheWrite1h(model: CatalogModelView): string {
  const rate = model.pricing?.cacheWrite1h;
  return rate != null ? `${formatRate(rate)}/1M · recorded, not charged` : 'not listed';
}

/** Where the prices were read, as a link, and when. */
function PricesFrom({ model }: CatalogModelDetailsProps) {
  const pricing = model.pricing;
  return (
    <dd>
      {pricing?.sourceUrl
        ? <a href={pricing.sourceUrl} target="_blank" rel="noreferrer noopener">{pricing.sourceUrl}</a>
        : '—'}
      {pricing?.observedAt && <span className="prov-sub"> · read {pricing.observedAt}</span>}
    </dd>
  );
}

/**
 * What a catalog model carries that the model form has no field for, shown under the read-only form: lifecycle,
 * limits, capabilities, the 1-hour cache write price and where the prices come from.
 */
export default function CatalogModelDetails({ model }: CatalogModelDetailsProps) {
  return (
    <section className="catalog-detail" aria-label="Catalog details">
      <div className="model-prices-title">Catalog details</div>
      <dl>
        {model.aliases.length > 0 && (<><dt>Also reported as</dt><dd className="mono">{model.aliases.join(', ')}</dd></>)}
        <dt>Lifecycle</dt><dd>{lifecycle(model)}</dd>
        <dt>Context window</dt><dd>{tokens(model.contextWindow)}</dd>
        <dt>Max output tokens</dt><dd>{tokens(model.maxOutputTokens)}</dd>
        <dt>Capabilities</dt><dd>{capabilities(model)}</dd>
        <dt>Cache write (1 hour)</dt><dd className="mono">{cacheWrite1h(model)}</dd>
        <dt>Prices from</dt><PricesFrom model={model} />
      </dl>
    </section>
  );
}

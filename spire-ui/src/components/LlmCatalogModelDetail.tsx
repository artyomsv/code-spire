import type { CatalogModelView } from '../api';
import { CATALOG_RATE_FIELDS, catalogPrice } from '../llmCatalog';
import { TOKEN_TYPE_LABEL, formatRate } from '../llmPricing';
import SidePanel from './SidePanel';

interface LlmCatalogModelDetailProps {
  model: CatalogModelView;
  onClose: () => void;
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

/** A catalog model, read-only, in the side panel: it changes only through a catalog update, never here. */
export default function LlmCatalogModelDetail({ model, onClose }: LlmCatalogModelDetailProps) {
  const pricing = model.pricing;
  return (
    <SidePanel title={model.displayName} subtitle={<span className="mono">{model.modelId}</span>} busy={false}
      onClose={onClose} actions={<button type="button" className="btn-ghost" onClick={onClose}>Close</button>}>
      <div className="catalog-detail">
        <dl>
          <dt>Provider</dt><dd>{model.provider}</dd>
          {model.aliases.length > 0 && (<><dt>Also reported as</dt><dd className="mono">{model.aliases.join(', ')}</dd></>)}
          <dt>Lifecycle</dt><dd>{lifecycle(model)}</dd>
          <dt>Context window</dt><dd>{tokens(model.contextWindow)}</dd>
          <dt>Max output</dt><dd>{tokens(model.maxOutputTokens)}</dd>
          <dt>Capabilities</dt><dd>{capabilities(model)}</dd>
          {CATALOG_RATE_FIELDS.map(({ type }) => (
            <PriceRow key={type} label={TOKEN_TYPE_LABEL[type]} value={catalogPrice(model, type)} />
          ))}
          <PriceRow label="Cache write (1 hour)"
            value={pricing?.cacheWrite1h != null ? `${formatRate(pricing.cacheWrite1h)}/1M · recorded, not charged` : 'not listed'} />
          <dt>Prices from</dt>
          <dd>
            {pricing?.sourceUrl
              ? <a href={pricing.sourceUrl} target="_blank" rel="noreferrer noopener">{pricing.sourceUrl}</a>
              : '—'}
            {pricing?.observedAt && <span className="prov-sub"> · read {pricing.observedAt}</span>}
          </dd>
        </dl>
      </div>
    </SidePanel>
  );
}

function PriceRow({ label, value }: { label: string; value: string }) {
  return (<><dt>{label}</dt><dd className="mono">{value}</dd></>);
}

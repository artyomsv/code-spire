import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { llmCatalogKey, refreshLlmCatalog, type CatalogChangeView, type LlmCatalogView } from '../api';
import { formatEventTime } from '../format';
import { invalidateLive } from '../liveCache';
import { shortSha } from '../llmCatalog';
import { preparationKeys } from './work-items/workQueries';

interface SettingsLlmCatalogHeaderProps {
  catalog: LlmCatalogView | undefined;
}

/** "2 re-priced, 1 added", or null when the last file changed nothing a person reads. */
export function changeSummary(change: CatalogChangeView | null): string | null {
  if (!change) return null;
  const parts: string[] = [];
  if (change.rePriced.length > 0) parts.push(`${change.rePriced.length} re-priced`);
  if (change.added.length > 0) parts.push(`${change.added.length} added`);
  if (change.retired.length > 0) parts.push(`${change.retired.length} retired`);
  return parts.length > 0 ? parts.join(', ') : null;
}

/** Where the catalog came from, without the scheme: the line is long enough already. */
function shownUrl(url: string): string {
  return url.replace(/^https:\/\//, '');
}

/** Settings → LLM → Models: which catalog is in use, how the last read went, and "Reload now". */
export default function SettingsLlmCatalogHeader({ catalog }: SettingsLlmCatalogHeaderProps) {
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function reload() {
    setBusy(true);
    setError(null);
    try {
      await refreshLlmCatalog();
      await Promise.all([invalidateLive(client, llmCatalogKey), invalidateLive(client, preparationKeys.llmModels())]);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  if (!catalog) return null;
  const origin = catalog.url ? `from ${shownUrl(catalog.url)}` : 'the copy built into this image (remote loading is off)';
  const loaded = catalog.loadedAt ? `loaded ${formatEventTime(catalog.loadedAt)} ` : '';
  const change = changeSummary(catalog.lastChange);
  return (
    <div className="catalog-head" aria-label="Model catalog">
      <div>
        Catalog: {loaded}{origin} · {catalog.rows.length} models · sha {shortSha(catalog.sha256)}
        {catalog.url && (
          <button className="btn-ghost" onClick={() => void reload()} disabled={busy} aria-label="Reload now">
            <RefreshCw size={13} /> {busy ? 'Reloading…' : 'Reload now'}
          </button>
        )}
      </div>
      {change && <div className="prov-sub">last change: {change}</div>}
      {catalog.lastError && (
        <div className="prov-sub rate-unset" role="status">
          last check failed {formatEventTime(catalog.lastCheckedAt ?? undefined)}: {catalog.lastError} — using the catalog
          {catalog.loadedAt ? ` from ${formatEventTime(catalog.loadedAt)}` : ' in use'}
        </div>
      )}
      {error && <div className="prov-sub rate-unset">{error}</div>}
    </div>
  );
}

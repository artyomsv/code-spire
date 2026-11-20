import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithQuery } from '../test/queryWrapper';
import { catalogRow, catalogView } from '../test/catalogFixtures';
import SettingsLlmCatalogHeader from './SettingsLlmCatalogHeader';
import * as api from '../api';
import * as liveCache from '../liveCache';

describe('SettingsLlmCatalogHeader', () => {
  it('shows source, load time, sha prefix and model count', () => {
    renderWithQuery(<SettingsLlmCatalogHeader catalog={catalogView({
      rows: [catalogRow(), catalogRow({ modelId: 'TEST-cat-b' })],
      lastChange: { oldSha: null, newSha: 'abcdef0', added: ['TEST-cat-b'], retired: [], rePriced: [
        { modelId: 'TEST-cat-a', before: { INPUT: 1 }, after: { INPUT: 2 } }] },
    })} />);

    const head = screen.getByLabelText('Model catalog');
    expect(head).toHaveTextContent('from example.invalid/ai-catalog/v1/catalog.yaml');
    expect(head).toHaveTextContent('2 models');
    expect(head).toHaveTextContent('sha abcdef0');
    expect(head).not.toHaveTextContent('abcdef01');
    expect(head).toHaveTextContent('loaded ');
    expect(head).toHaveTextContent('last change: 1 re-priced, 1 added');
  });

  it('shows the last error beside the catalog in use', () => {
    renderWithQuery(<SettingsLlmCatalogHeader catalog={catalogView({ lastError: 'http_503' })} />);

    expect(screen.getByRole('status')).toHaveTextContent('last check failed');
    expect(screen.getByRole('status')).toHaveTextContent('http_503 — using the catalog from');
  });

  it('says when remote loading is off and offers no reload', () => {
    renderWithQuery(<SettingsLlmCatalogHeader catalog={catalogView({ url: null, source: 'bundled', loadedAt: null })} />);

    expect(screen.getByLabelText('Model catalog')).toHaveTextContent('the copy built into this image');
    expect(screen.queryByRole('button', { name: 'Reload now' })).not.toBeInTheDocument();
  });

  it('reload now posts and re-reads', async () => {
    const refresh = vi.spyOn(api, 'refreshLlmCatalog').mockResolvedValue({ outcome: 'REPLACED' });
    const invalidate = vi.spyOn(liveCache, 'invalidateLive').mockResolvedValue(undefined);
    renderWithQuery(<SettingsLlmCatalogHeader catalog={catalogView()} />);

    fireEvent.click(screen.getByRole('button', { name: 'Reload now' }));

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(2));
    const keys = invalidate.mock.calls.map(([, key]) => JSON.stringify(key));
    expect(keys).toContain(JSON.stringify(api.llmCatalogKey));
  });
});

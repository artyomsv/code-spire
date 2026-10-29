import type { LiveSocketState } from '../../hooks/useLiveSocket';

export function useWorkItemChanges(_options: { enabled?: boolean } = {}): LiveSocketState {
  return { status: 'connecting', closeReason: null };
}

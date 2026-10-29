import { QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import { expect, it } from 'vitest';
import { createQueryClient } from '../queryClient';
import { useEachRead } from './useEachRead';

const KEY = ['TEST-each-read'];

// A page that checks each account after its list is read must not check again because a check wrote into the list.
it('runs once per completed read and for a failed read that keeps data, never for a cache write', async () => {
  const client = createQueryClient();
  const seen: number[] = [];
  let answer = 1, fail = false;
  function Probe() {
    useQuery({ queryKey: KEY, queryFn: async () => { if (fail) throw new Error('TEST-down'); return answer; } });
    useEachRead<number>(KEY, data => { seen.push(data); });
    return null;
  }
  render(<QueryClientProvider client={client}><Probe /></QueryClientProvider>);
  await waitFor(() => expect(seen).toEqual([1]));
  // A different value: an equal one keeps its reference and would prove nothing.
  await act(async () => { client.setQueryData(KEY, 7); });
  expect(seen).toEqual([1]);
  answer = 2;
  await act(async () => { await client.invalidateQueries({ queryKey: KEY }); });
  expect(seen).toEqual([1, 2]);
  // The last good list is still on screen, so its rows are checked rather than left waiting.
  fail = true;
  await act(async () => { await client.invalidateQueries({ queryKey: KEY }); });
  expect(seen).toEqual([1, 2, 2]);
});

import { chargeFor } from './pricer';

export function total(tokens: number): number {
  return chargeFor(tokens);
}

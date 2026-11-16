import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import * as sources from '../../work-items/workSourcesApi';
import { source } from './factoryFixtures';
import { renderFactory, step, stubFactoryApis } from './factoryTestSetup';

beforeEach(stubFactoryApis);

// Removing a person stops their labels starting work, so the button only asks; the dialog's Remove does it.
describe('removing an allowed person', () => {
  async function askToRemove() {
    renderFactory();
    fireEvent.click(within(await step(2)).getByRole('button', { name: 'Remove @TEST-person' }));
    return screen.getByRole('dialog');
  }

  it('asks first, naming the person, the source and what it means', async () => {
    expect(await askToRemove()).toHaveTextContent('Remove @TEST-person from TEST-source name? Labels they add no longer start work.');
    expect(sources.removeWorkActor).not.toHaveBeenCalled();
  });

  it('removes the person once the dialog is confirmed', async () => {
    fireEvent.click(within(await askToRemove()).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(sources.removeWorkActor).toHaveBeenCalledWith(source(), '900123'));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('keeps the person when the dialog is cancelled', async () => {
    fireEvent.click(within(await askToRemove()).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sources.removeWorkActor).not.toHaveBeenCalled();
  });
});

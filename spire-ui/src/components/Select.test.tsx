import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Select, { type SelectOption } from './Select';

const OPTS: SelectOption[] = [
  { value: 'a', label: 'Apple' },
  { value: 'b', label: 'Banana' },
  { value: 'c', label: 'Cherry', disabled: true },
];

function setup(value = 'a') {
  const onChange = vi.fn();
  render(<Select value={value} options={OPTS} onChange={onChange} ariaLabel="Fruit" />);
  return { onChange, trigger: screen.getByRole('combobox', { name: /fruit/i }) };
}

describe('Select', () => {
  it('shows the selected option label and is collapsed', () => {
    const { trigger } = setup('b');
    expect(trigger).toHaveTextContent('Banana');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens on click and lists options', () => {
    const { trigger } = setup();
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('option', { name: 'Banana' })).toBeInTheDocument();
  });

  it('selecting an option calls onChange and closes', () => {
    const { onChange, trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('option', { name: 'Banana' }));
    expect(onChange).toHaveBeenCalledWith('b');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('ArrowDown then Enter selects the next enabled option', () => {
    const { onChange, trigger } = setup('a');
    fireEvent.keyDown(trigger, { key: 'ArrowDown' }); // opens, highlights selected (a)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' }); // -> b
    fireEvent.keyDown(trigger, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('b');
  });

  it('does not select a disabled option', () => {
    const { onChange, trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('option', { name: 'Cherry' }));
    expect(onChange).not.toHaveBeenCalled();
  });

  it('Escape closes without changing', () => {
    const { onChange, trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: 'Escape' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('a disabled Select does not open', () => {
    const onChange = vi.fn();
    render(<Select value="a" options={OPTS} onChange={onChange} ariaLabel="Fruit" disabled />);
    const trigger = screen.getByRole('combobox', { name: /fruit/i });
    fireEvent.click(trigger);
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
  });
});

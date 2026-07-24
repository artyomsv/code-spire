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

  it('keyboard nav skips the disabled option, wrapping past Cherry to Apple', () => {
    const { onChange, trigger } = setup('a');
    fireEvent.keyDown(trigger, { key: 'ArrowDown' }); // opens, highlights selected (a)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' }); // -> b
    fireEvent.keyDown(trigger, { key: 'ArrowDown' }); // c is disabled, skip and wrap -> a
    fireEvent.keyDown(trigger, { key: 'Enter' });
    expect(onChange).not.toHaveBeenCalledWith('c');
    expect(onChange).toHaveBeenCalledWith('a');
  });

  it('type-ahead jumps to the option starting with the typed letter', () => {
    const { onChange, trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: 'B' });
    fireEvent.keyDown(trigger, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('b');
  });

  it('closes on outside pointerdown, leaving no option in the DOM', () => {
    const { trigger } = setup();
    fireEvent.click(trigger);
    expect(screen.getByRole('option', { name: 'Banana' })).toBeInTheDocument();
    fireEvent.pointerDown(document.body);
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
  });

  it('returns focus to the trigger after selecting an option', () => {
    const { trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('option', { name: 'Banana' }));
    expect(document.activeElement).toBe(trigger);
  });
});

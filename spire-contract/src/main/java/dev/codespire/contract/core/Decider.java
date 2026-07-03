package dev.codespire.contract.core;

import java.util.List;

/**
 * The write-model abstraction (fmodel style): pure decision + evolution.
 * State is rebuilt by replaying events through {@link #evolve}; commands are
 * validated against that state in {@link #decide}, which emits new events.
 *
 * @param <C> command type
 * @param <S> state type
 * @param <E> event type
 */
public interface Decider<C, S, E> {

    S initialState();

    /** Pure: what should happen. Returns the events to append (empty = no-op). */
    List<E> decide(C command, S state);

    /** Pure: fold one event into state. */
    S evolve(S state, E event);
}

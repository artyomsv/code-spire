package dev.codespire.contract.core;

/**
 * The read-model abstraction: a pure fold of events into a query-optimized shape.
 *
 * @param <S> view state type
 * @param <E> event type
 */
public interface View<S, E> {

    S initialState();

    S evolve(S state, E event);
}

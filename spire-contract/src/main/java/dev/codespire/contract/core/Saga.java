package dev.codespire.contract.core;

import java.util.List;

/**
 * The automation abstraction: on event E, issue command(s) C. Sagas hold no
 * business logic beyond routing — all decisions live in deciders.
 *
 * @param <E> event type
 * @param <C> command type
 */
public interface Saga<E, C> {

    List<C> react(E event);
}

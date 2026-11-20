package io.github.loopforge4j.core.store;

/**
 * Mutation applied by {@link RunStore#update}. Returning {@code false} discards the change (nothing is
 * written). Implementations must be side-effect free besides the change object: they may be invoked
 * more than once when an optimistic write conflicts.
 */
@FunctionalInterface
public interface RunMutator {

    boolean apply(RunChange change);
}

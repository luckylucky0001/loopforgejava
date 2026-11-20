package io.github.loopforge4j.core.store;

class InMemoryRunStoreTest extends RunStoreContract {

    private final InMemoryRunStore store = new InMemoryRunStore();

    @Override
    protected RunStore store() {
        return store;
    }
}

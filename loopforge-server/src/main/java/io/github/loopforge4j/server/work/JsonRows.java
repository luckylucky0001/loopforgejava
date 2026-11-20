package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.StoreException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** Helpers for the JSON-payload tables of this package. */
final class JsonRows {

    private JsonRows() {}

    static String write(Object value) {
        return Json.write(value);
    }

    static <T> T read(String raw, Class<T> type) {
        return Json.read(raw, type);
    }

    static <T> T store(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            throw new StoreException("store write failed", e);
        }
    }
}

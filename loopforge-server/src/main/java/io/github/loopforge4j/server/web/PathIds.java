package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.engine.WorkflowValidator;
import org.springframework.http.HttpStatus;

/**
 * Ids taken from a request path. Definitions normalise their id (strip it), so an id that is not
 * already in its normal form would look up one definition and then overwrite another: such ids are
 * refused before any lookup.
 */
public final class PathIds {

    private PathIds() {}

    public static String require(String id) {
        if (id == null || !WorkflowValidator.ID.matcher(id).matches()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "id must start with a letter or digit and use only letters, digits, '.', '_', ':' or '-'");
        }
        return id;
    }
}

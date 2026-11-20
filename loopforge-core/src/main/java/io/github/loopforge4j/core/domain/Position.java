package io.github.loopforge4j.core.domain;

/** Canvas position of a node in the SOP designer. */
public record Position(double x, double y) {

    public static final Position ORIGIN = new Position(0, 0);
}

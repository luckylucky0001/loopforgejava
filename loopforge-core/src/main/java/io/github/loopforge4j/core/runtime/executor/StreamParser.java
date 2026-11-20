package io.github.loopforge4j.core.runtime.executor;

/** Incremental parser of one executor stream protocol. Not thread-safe; fed from one reader thread. */
public interface StreamParser {

    void accept(String line);

    AgentResult result(int exitCode);
}

/**
 * Domain model of LoopForge4J.
 *
 * <p>Definitions ({@link io.github.loopforge4j.core.domain.WorkflowDefinition}, {@link
 * io.github.loopforge4j.core.domain.AgentDefinition}, ...) are immutable records whose JSON shape
 * follows the upstream LoopForge catalog so exported SOPs stay portable. Runtime state ({@link
 * io.github.loopforge4j.core.domain.Run}, {@link io.github.loopforge4j.core.domain.NodeRun}) is a
 * mutable aggregate that is only changed inside a store transaction.
 */
package io.github.loopforge4j.core.domain;

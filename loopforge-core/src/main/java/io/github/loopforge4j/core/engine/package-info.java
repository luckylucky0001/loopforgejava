/**
 * SOP engine: schedules ready nodes of a run in parallel, executes agent/script/approval/sub-SOP
 * nodes, routes gate decisions (pass, rework, fail, human) and implements the operator controls
 * (approve, reject, retry, pass, pause, resume, cancel). Port of upstream {@code internal/engine}.
 */
package io.github.loopforge4j.core.engine;

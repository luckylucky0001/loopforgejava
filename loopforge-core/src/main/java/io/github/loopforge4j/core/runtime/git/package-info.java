/**
 * Git support for run workspaces: checkout of bound repositories into first-level sub-directories,
 * work-branch handling and per-process credential injection (port of upstream {@code gitwork} and
 * {@code engine/repository_workspace.go}). All git commands run through the run's sandbox.
 */
package io.github.loopforge4j.core.runtime.git;

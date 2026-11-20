package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.engine.RunEngine;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Executors known to this server and whether their CLI is installed. */
@Tag(name = "Executors")
@RestController
@RequestMapping("/api/v1/executors")
class ExecutorController {

    private final RunEngine engine;

    ExecutorController(RunEngine engine) {
        this.engine = engine;
    }

    record ExecutorView(String id, String name, String kind, String binary, String model, boolean available) {}

    @GetMapping
    List<ExecutorView> list() {
        return engine.executors().definitions().stream()
                .map(e -> new ExecutorView(e.id(), e.name(), e.kind(), e.binary(), e.model(), available(e)))
                .toList();
    }

    static boolean available(ExecutorDefinition executor) {
        if (executor.binary() == null) {
            return true;
        }
        Path binary = Path.of(executor.binary());
        if (binary.isAbsolute()) {
            return Files.isExecutable(binary);
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir).resolve(executor.binary()))) {
                return true;
            }
        }
        return false;
    }
}

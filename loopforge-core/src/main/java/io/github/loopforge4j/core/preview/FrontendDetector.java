package io.github.loopforge4j.core.preview;

import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether a workspace holds a frontend project or an already built site (upstream {@code
 * DetectFrontendProject}). Non-frontend repositories skip the preview instead of inventing a page.
 */
public final class FrontendDetector {

    /** The verdict and what it rests on. */
    public record Result(boolean frontend, String evidence) {}

    static final int MAX_DEPTH = 6;
    static final int MAX_FILES = 8_000;

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules",
            ".git",
            ".svn",
            ".hg",
            "vendor",
            ".loopforge",
            ".agentflow",
            ".next",
            ".nuxt",
            ".output",
            ".cache",
            ".turbo",
            "coverage",
            "tmp",
            "temp");
    private static final Set<String> CONFIG_FILES = Set.of(
            "vite.config.js",
            "vite.config.ts",
            "vite.config.mjs",
            "vite.config.cjs",
            "webpack.config.js",
            "webpack.config.ts",
            "webpack.config.mjs",
            "rspack.config.js",
            "rspack.config.ts",
            "next.config.js",
            "next.config.mjs",
            "next.config.ts",
            "nuxt.config.js",
            "nuxt.config.ts",
            "angular.json",
            "svelte.config.js",
            "astro.config.mjs",
            "astro.config.ts",
            "vue.config.js",
            "remix.config.js");
    private static final Set<String> INDEX_FILES = Set.of("index.html", "index.htm");
    private static final Set<String> DEPENDENCIES = Set.of(
            "react",
            "react-dom",
            "react-scripts",
            "preact",
            "vue",
            "nuxt",
            "next",
            "svelte",
            "@sveltejs/kit",
            "vite",
            "webpack",
            "rspack",
            "@rspack/core",
            "@angular/core",
            "@angular/cli",
            "solid-js",
            "lit",
            "astro",
            "gatsby",
            "@remix-run/react",
            "parcel",
            "umi",
            "@vitejs/plugin-react",
            "@vitejs/plugin-vue");
    private static final List<String> SCRIPT_TOKENS = List.of(
            "vite",
            "webpack",
            "rspack",
            "next build",
            "next start",
            "next dev",
            "nuxt",
            "react-scripts",
            "vue-cli-service",
            "ng build",
            "ng serve",
            "svelte-kit",
            "astro build",
            "astro dev",
            "parcel");

    private FrontendDetector() {}

    public static Result detect(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new Result(false, "工作区不可读");
        }
        String[] found = {null};
        int[] files = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    return SKIP_DIRS.contains(name) || root.relativize(dir).getNameCount() > MAX_DEPTH
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++files[0] > MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    String evidence = evidence(root, file);
                    if (evidence != null) {
                        found[0] = evidence;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return new Result(false, "工作区不可读");
        }
        return found[0] != null
                ? new Result(true, found[0])
                : new Result(false, "未发现 Vite/webpack/Next 配置、前端依赖或 index.html");
    }

    private static String evidence(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (INDEX_FILES.contains(name) || CONFIG_FILES.contains(name)) {
            return rel;
        }
        if (!name.equals("package.json")) {
            return null;
        }
        String why = packageJson(file);
        return why == null ? null : rel + " (" + why + ")";
    }

    @SuppressWarnings("unchecked")
    private static String packageJson(Path file) {
        Map<String, Object> pkg;
        try {
            if (Files.size(file) > (1 << 20)) {
                return null;
            }
            pkg = Json.read(Files.readString(file), Map.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        for (String group : List.of("dependencies", "devDependencies", "peerDependencies")) {
            if (pkg.get(group) instanceof Map<?, ?> deps) {
                for (Object dep : deps.keySet()) {
                    if (DEPENDENCIES.contains(String.valueOf(dep).toLowerCase(Locale.ROOT))) {
                        return String.valueOf(dep);
                    }
                }
            }
        }
        if (pkg.get("scripts") instanceof Map<?, ?> scripts) {
            for (Object command : scripts.values()) {
                String lower = String.valueOf(command).toLowerCase(Locale.ROOT);
                for (String token : SCRIPT_TOKENS) {
                    if (lower.contains(token)) {
                        return "scripts:" + token;
                    }
                }
            }
        }
        return null;
    }
}

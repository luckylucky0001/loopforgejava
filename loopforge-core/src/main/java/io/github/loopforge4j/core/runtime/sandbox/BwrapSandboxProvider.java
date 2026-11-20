package io.github.loopforge4j.core.runtime.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight Linux isolation with bubblewrap (upstream {@code bwrap} process sandbox): every
 * process of a run sees the host file system read-only, can write only its own workspace and a
 * private {@code /tmp}, and runs in new user, PID, IPC and UTS namespaces. Stricter than upstream,
 * the other runs' workspaces and the server's HOME are hidden behind empty mounts. The network is
 * shared unless configured otherwise, so agents still reach their model APIs.
 */
public final class BwrapSandboxProvider implements SandboxProvider {

    public static final String PROFILE = "bwrap";
    private static final Logger log = LoggerFactory.getLogger(BwrapSandboxProvider.class);

    /**
     * @param profile the sandbox profile id SOPs select
     * @param binary the {@code bwrap} launcher
     * @param hiddenPaths host directories replaced by empty ones inside the sandbox, in addition to
     *     the workspace root
     * @param shareNetwork keep the host network (agents need their model APIs); {@code false}
     *     isolates it
     */
    public record Options(String profile, String binary, List<Path> hiddenPaths, boolean shareNetwork) {
        public Options {
            profile = profile == null || profile.isBlank() ? PROFILE : profile;
            binary = binary == null || binary.isBlank() ? "bwrap" : binary;
            hiddenPaths = hiddenPaths == null ? List.of() : List.copyOf(hiddenPaths);
        }
    }

    private final LocalSandboxProvider local;
    private final LocalSandboxOptions localOptions;
    private final Options options;
    private final Path workspaceRoot;

    public BwrapSandboxProvider(LocalSandboxOptions localOptions, Options options) {
        this.local = new LocalSandboxProvider(localOptions);
        this.localOptions = localOptions;
        this.options = options;
        this.workspaceRoot = localOptions.root().toAbsolutePath().normalize();
    }

    /** Whether bubblewrap can create its namespaces on this host (user namespaces may be disabled). */
    public static boolean available(String binary) {
        try {
            Process p = new ProcessBuilder(
                            binary == null ? "bwrap" : binary,
                            "--die-with-parent",
                            "--unshare-user",
                            "--unshare-pid",
                            "--ro-bind",
                            "/",
                            "/",
                            "--proc",
                            "/proc",
                            "--dev",
                            "/dev",
                            "true")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean supports(String profileId) {
        return options.profile().equals(profileId);
    }

    @Override
    public Sandbox acquire(String workspaceId) throws IOException {
        Sandbox base = local.acquire(workspaceId);
        Path workspace = base.workspace().toAbsolutePath().normalize();
        return new LocalSandbox(
                "bwrap-" + workspaceId,
                workspace,
                localOptions,
                "bwrap",
                (argv, directory) -> command(workspace, directory, argv));
    }

    @Override
    public void release(String workspaceId) throws IOException {
        local.release(workspaceId);
    }

    @Override
    public int reclaim(String workspaceId) throws IOException {
        // the recorded process group is bwrap's; its children die with it (--die-with-parent)
        return local.reclaim(workspaceId);
    }

    /** The bubblewrap command line that runs {@code argv} in {@code workspace}. */
    List<String> command(Path workspace, Path directory, List<String> argv) {
        List<String> out = new ArrayList<>(List.of(
                options.binary(),
                "--die-with-parent",
                "--new-session",
                "--unshare-user",
                "--unshare-pid",
                "--unshare-ipc",
                "--unshare-uts"));
        if (!options.shareNetwork()) {
            out.add("--unshare-net");
        }
        out.addAll(List.of("--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp"));
        // evaluated per launch: a path created after startup is hidden too
        for (Path path : hiddenPaths(workspaceRoot, options.hiddenPaths())) {
            out.addAll(List.of("--tmpfs", path.toString()));
        }
        out.addAll(List.of("--bind", workspace.toString(), workspace.toString()));
        out.addAll(List.of("--chdir", (directory == null ? workspace : directory).toString()));
        out.add("--");
        out.addAll(argv);
        return out;
    }

    /**
     * The workspace root (always: the run's own workspace exists inside it) plus the configured paths
     * that exist, shortest first (a parent before its children); a path holding the running JDK stays
     * visible because the gate MCP server needs it.
     */
    static List<Path> hiddenPaths(Path workspaceRoot, List<Path> extra) {
        Path javaHome =
                Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>(extra);
        candidates.add(root);
        List<Path> out = new ArrayList<>();
        for (Path candidate : candidates) {
            Path p = candidate.toAbsolutePath().normalize();
            if (p.getParent() == null || out.contains(p) || !p.equals(root) && !Files.isDirectory(p)) {
                continue;
            }
            if (javaHome.startsWith(p)) {
                log.warn("bwrap sandbox: not hiding {} because the JDK ({}) lives there", p, javaHome);
                continue;
            }
            out.add(p);
        }
        out.sort(java.util.Comparator.comparingInt(Path::getNameCount));
        return List.copyOf(out);
    }
}

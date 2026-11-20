package io.github.loopforge4j.core.runtime.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight Linux isolation with bubblewrap (upstream {@code bwrap} process sandbox): processes
 * see only the system directories (read-only), the JDK and their own run workspace (writable),
 * with a private {@code /tmp}, in new user, PID, IPC and UTS namespaces. Nothing else of the host is
 * mounted: not {@code /run} or {@code /var} (host sockets such as the Docker daemon's or a
 * database's), not the home directories, not other runs' workspaces. Stricter than upstream, which
 * mounts the whole root read-only — a read-only mount does not stop connecting to a socket. The
 * network is shared unless configured otherwise, so agents still reach their model APIs.
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
    public record Options(
            String profile, String binary, List<Path> hiddenPaths, boolean shareNetwork, List<Path> readOnlyPaths) {
        public Options {
            profile = profile == null || profile.isBlank() ? PROFILE : profile;
            binary = binary == null || binary.isBlank() ? "bwrap" : binary;
            hiddenPaths = hiddenPaths == null ? List.of() : List.copyOf(hiddenPaths);
            readOnlyPaths =
                    readOnlyPaths == null || readOnlyPaths.isEmpty() ? SYSTEM_PATHS : List.copyOf(readOnlyPaths);
        }

        public Options(String profile, String binary, List<Path> hiddenPaths, boolean shareNetwork) {
            this(profile, binary, hiddenPaths, shareNetwork, null);
        }
    }

    /** What processes may read by default: programs, libraries and system configuration. */
    public static final List<Path> SYSTEM_PATHS = List.of(
            Path.of("/usr"),
            Path.of("/bin"),
            Path.of("/sbin"),
            Path.of("/lib"),
            Path.of("/lib32"),
            Path.of("/lib64"),
            Path.of("/libx32"),
            Path.of("/etc"),
            Path.of("/opt"));

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
        for (Path path : options.readOnlyPaths()) {
            // --ro-bind-try keeps symlinked directories (/bin -> usr/bin) and skips missing ones
            out.addAll(List.of("--ro-bind-try", path.toString(), path.toString()));
        }
        Path javaHome =
                Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        if (options.readOnlyPaths().stream().noneMatch(javaHome::startsWith)) {
            // the gate MCP server runs on this JDK; only the JDK itself becomes visible, not its parent
            out.addAll(List.of("--ro-bind", javaHome.toString(), javaHome.toString()));
        }
        resolvConf().ifPresent(target -> out.addAll(List.of("--ro-bind-try", target.toString(), target.toString())));
        out.addAll(List.of("--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp"));
        // configured paths inside the mounted trees (a secrets file under /etc, say) are emptied;
        // evaluated per launch, so a path created after startup is covered too
        for (Path path : hiddenPaths(workspaceRoot, options.hiddenPaths())) {
            if (options.readOnlyPaths().stream().anyMatch(path::startsWith)) {
                out.addAll(List.of("--tmpfs", path.toString()));
            }
        }
        out.addAll(List.of("--bind", workspace.toString(), workspace.toString()));
        out.addAll(List.of("--chdir", (directory == null ? workspace : directory).toString()));
        out.add("--");
        out.addAll(argv);
        return out;
    }

    /**
     * {@code /etc/resolv.conf} is often a symlink into {@code /run} (systemd-resolved): its target
     * file alone is mounted so name resolution keeps working without exposing {@code /run}.
     */
    private static java.util.Optional<Path> resolvConf() {
        try {
            Path real = Path.of("/etc/resolv.conf").toRealPath();
            return real.startsWith("/etc") ? java.util.Optional.empty() : java.util.Optional.of(real);
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
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
                // emptying it would hide the JDK; it is not mounted unless it is a system path anyway
                log.debug("bwrap sandbox: {} holds the JDK ({}); only the JDK is mounted from it", p, javaHome);
                continue;
            }
            out.add(p);
        }
        out.sort(java.util.Comparator.comparingInt(Path::getNameCount));
        return List.copyOf(out);
    }
}

package io.github.loopforge4j.core.engine;

import static io.github.loopforge4j.core.domain.NodeType.AGENT;
import static io.github.loopforge4j.core.domain.NodeType.SCRIPT;
import static io.github.loopforge4j.core.domain.NodeType.SOP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.delivery.DeliveryException;
import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.BranchMode;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.ExecutionScope;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.RepositoryProvider;
import io.github.loopforge4j.core.domain.RepositorySelection;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.git.GitCredentialProvider;
import io.github.loopforge4j.core.runtime.git.GitCredentials;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Repository checkout, work branches, repository-scoped steps and PR delivery against real bare repos. */
class GitRepositoryTest {

    @TempDir
    Path root;

    Path remotes;
    FakeDelivery delivery = new FakeDelivery();
    EngineFixture f;

    @BeforeEach
    void setUp() throws Exception {
        remotes = Files.createDirectories(root.resolve("remotes"));
        GitCredentialProvider git = run -> new GitCredentials(Map.of(), null, "Test Bot", "bot@example.invalid");
        f = new EngineFixture(root.resolve("ws"), new InMemoryRunStore(), true, git, delivery);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    @Test
    void scriptCommitsAndPushesTheWorkBranchInsideTheCheckout() throws Exception {
        register("app", seededRemote("app.git"), RepositoryProvider.LOCAL);
        f.workflow(WorkflowDefinition.builder("change")
                .node(WorkflowNode.builder("edit", SCRIPT)
                        .scope(ExecutionScope.REPOSITORIES)
                        .script(
                                "test \"$(git branch --show-current)\" = \"$LOOPFORGE_WORK_BRANCH\"\n"
                                        + "echo change > CHANGE.md && git add CHANGE.md && git commit -qm 'add change'\n"
                                        + "git push -q origin \"$LOOPFORGE_WORK_BRANCH\" && echo pushed $LOOPFORGE_REPOSITORY_NAME")
                        .build())
                .build());
        Run run = f.engine.start("change", "", ExecutionContext.EMPTY, List.of(RepositorySelection.of("app")));
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);

        RepositoryContext rc = done.repositories().getFirst();
        assertThat(rc.workspaceDir()).isEqualTo("app");
        assertThat(rc.workBranch()).startsWith("feature/fix-");
        assertThat(rc.baseSha()).hasSize(40);
        assertThat(done.repositoriesPrepared()).isTrue();
        assertThat(done.requireNode("edit").output()).contains("pushed app");
        assertThat(git(remotes.resolve("app.git"), "log", "--format=%an|%s", rc.workBranch())
                        .strip())
                .startsWith("Test Bot|add change");
        assertThat(git(remotes.resolve("app.git"), "rev-parse", "main").strip()).isEqualTo(rc.baseSha());
        assertThat(f.eventTypes(run.id())).contains("repository.checked_out");
    }

    @Test
    void multipleRepositoriesGetUniqueDirectoriesAndShareTheWorkBranch() throws Exception {
        register("one", seededRemote("one.git"), RepositoryProvider.LOCAL);
        f.catalog.saveRepository(new RepositoryDefinition(
                "two", "one", RepositoryProvider.LOCAL, seededRemote("two.git"), null, "main", null, null, null));
        f.workflow(WorkflowDefinition.builder("multi")
                .node(WorkflowNode.builder("list", SCRIPT)
                        .script("printf '%s\\n' \"$LOOPFORGE_WORK_BRANCHES\"; ls -d one one-2")
                        .build())
                .build());
        Run run = f.engine.start(
                "multi",
                "",
                ExecutionContext.EMPTY,
                List.of(
                        new RepositorySelection("one", null, "feature/shared", BranchMode.AUTO),
                        RepositorySelection.of("two")));
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(done.repositories())
                .extracting(RepositoryContext::workspaceDir)
                .containsExactly("one", "one-2");
        assertThat(done.repositories())
                .extracting(RepositoryContext::workBranch)
                .containsExactly("feature/shared", "feature/shared");
        assertThat(done.requireNode("list").output()).contains("one=feature/shared", "one-2=feature/shared");
    }

    @Test
    void continueModeResumesTheExistingRemoteBranch() throws Exception {
        String url = seededRemote("cont.git");
        pushBranch(url, "feature/existing", "WIP.md");
        register("cont", url, RepositoryProvider.LOCAL);
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("check", SCRIPT)
                        .scope(ExecutionScope.REPOSITORIES)
                        .script("cat WIP.md")
                        .build())
                .build());
        Run run = f.engine.start(
                "wf",
                "",
                ExecutionContext.EMPTY,
                List.of(new RepositorySelection("cont", null, "feature/existing", BranchMode.CONTINUE)));
        assertThat(f.awaitStatus(run.id(), RunStatus.SUCCEEDED)
                        .requireNode("check")
                        .output())
                .contains("WIP.md");

        Run missing = f.engine.start(
                "wf",
                "",
                ExecutionContext.EMPTY,
                List.of(new RepositorySelection("cont", null, "feature/nope", BranchMode.CONTINUE)));
        assertThat(f.awaitStatus(missing.id(), RunStatus.FAILED)
                        .requireNode("check")
                        .error())
                .contains("does not exist on the remote");
    }

    @Test
    void newModeRefusesAnExistingRemoteBranch() throws Exception {
        String url = seededRemote("new.git");
        pushBranch(url, "feature/taken", "X.md");
        register("new", url, RepositoryProvider.LOCAL);
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("n", SCRIPT).script("true").build())
                .build());
        Run run = f.engine.start(
                "wf",
                "",
                ExecutionContext.EMPTY,
                List.of(new RepositorySelection("new", null, "feature/taken", BranchMode.NEW)));
        assertThat(f.awaitStatus(run.id(), RunStatus.FAILED).requireNode("n").error())
                .contains("already exists");
    }

    @Test
    void readOnlyCheckoutStaysOnTheBaseBranch() throws Exception {
        register("ro", seededRemote("ro.git"), RepositoryProvider.LOCAL);
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("n", SCRIPT)
                        .scope(ExecutionScope.REPOSITORIES)
                        .script("git branch --show-current; echo \"work=[$LOOPFORGE_WORK_BRANCH]\"")
                        .build())
                .build());
        Run run = f.engine.start(
                "wf",
                "",
                ExecutionContext.EMPTY,
                List.of(new RepositorySelection("ro", null, null, BranchMode.READ_ONLY)));
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("n").output()).contains("main", "work=[]");
        assertThat(done.repositories().getFirst().writableWorkBranch()).isNull();
    }

    @Test
    void emptyRemoteIsSeededWithTheBaseBranch() throws Exception {
        Path bare = remotes.resolve("empty.git");
        git(remotes, "init", "-q", "--bare", "empty.git");
        register("empty", bare.toUri().toString(), RepositoryProvider.LOCAL);
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("n", SCRIPT)
                        .scope(ExecutionScope.REPOSITORIES)
                        .script("git log --oneline -1")
                        .build())
                .build());
        Run run = f.engine.start("wf", "", ExecutionContext.EMPTY, List.of(RepositorySelection.of("empty")));
        assertThat(f.awaitStatus(run.id(), RunStatus.SUCCEEDED).requireNode("n").output())
                .contains("Initialize repository");
        assertThat(git(bare, "branch", "--list", "main")).contains("main");
    }

    @Test
    void invalidBranchesAreRejectedAtStart() throws Exception {
        register("app", seededRemote("v.git"), RepositoryProvider.LOCAL);
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("n", SCRIPT).script("true").build())
                .build());
        assertThatThrownBy(() -> f.engine.start(
                        "wf",
                        "",
                        ExecutionContext.EMPTY,
                        List.of(new RepositorySelection("app", null, "main", BranchMode.AUTO))))
                .isInstanceOf(RunStateException.class)
                .hasMessageContaining("shared branch");
        assertThatThrownBy(() -> f.engine.start(
                        "wf",
                        "",
                        ExecutionContext.EMPTY,
                        List.of(new RepositorySelection("app", null, "bad..name", BranchMode.AUTO))))
                .isInstanceOf(RunStateException.class);
        assertThatThrownBy(() -> f.engine.start(
                        "wf",
                        "",
                        ExecutionContext.EMPTY,
                        List.of(new RepositorySelection("app", null, null, BranchMode.CONTINUE))))
                .hasMessageContaining("explicit work branch");
        assertThatThrownBy(() ->
                        f.engine.start("wf", "", ExecutionContext.EMPTY, List.of(RepositorySelection.of("ghost"))))
                .isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void repositoryScopedStepWithoutRepositoriesIsAConfigurationError() {
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("n", SCRIPT)
                        .scope(ExecutionScope.REPOSITORIES)
                        .script("true")
                        .maxAttempts(3)
                        .build())
                .build());
        Run failed = f.awaitStatus(f.start("wf").id(), RunStatus.FAILED);
        assertThat(failed.requireNode("n").error()).contains("requires the run to bind at least one repository");
        assertThat(failed.requireNode("n").attempt()).isEqualTo(1);
    }

    @Test
    void childSopWorksInTheParentsCheckout() throws Exception {
        register("app", seededRemote("child.git"), RepositoryProvider.LOCAL);
        f.workflow(WorkflowDefinition.builder("child")
                        .node(WorkflowNode.builder("touch", SCRIPT)
                                .scope(ExecutionScope.REPOSITORIES)
                                .script("echo child > FROM_CHILD.md")
                                .build())
                        .build())
                .workflow(WorkflowDefinition.builder("parent")
                        .node(WorkflowNode.builder("sub", SOP)
                                .sopWorkflow("child")
                                .build())
                        .node(WorkflowNode.builder("read", SCRIPT)
                                .scope(ExecutionScope.REPOSITORIES)
                                .script("cat FROM_CHILD.md")
                                .build())
                        .chain("sub", "read")
                        .build());
        Run run = f.engine.start("parent", "", ExecutionContext.EMPTY, List.of(RepositorySelection.of("app")));
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("read").output()).contains("child");
        Run child = f.engine.require(done.requireNode("sub").childRunId());
        assertThat(child.repositories())
                .extracting(RepositoryContext::workBranch)
                .containsExactly(done.repositories().getFirst().workBranch());
    }

    @Test
    void pullRequestGatePassesWhenThePushedTipHasAnOpenPr() throws Exception {
        String url = seededRemote("pr.git");
        f.catalog.saveRepository(new RepositoryDefinition(
                "pr", "pr", RepositoryProvider.GITHUB, url, null, "main", "acme/pr", null, null));
        delivery.supported = true;
        mrWorkflow();
        Run run = f.engine.start(
                "deliver",
                "",
                ExecutionContext.EMPTY,
                List.of(new RepositorySelection("pr", null, "feature/pr-1", BranchMode.AUTO)));
        // the fake host reports an open PR whose head is the remote tip once the script pushed it
        delivery.onQuery = (rc, head) -> List.of(new PullRequest(
                "pr",
                7,
                "https://github.test/acme/pr/pull/7",
                "open",
                head,
                "main",
                tip(url, head),
                null,
                Instant.now()));
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(f.eventTypes(run.id())).contains("gate.mr.passed");
        assertThat(done.deliveries())
                .singleElement()
                .extracting(PullRequest::number)
                .isEqualTo(7);
        assertThat(delivery.queries).contains("pr:feature/pr-1->main");
    }

    @Test
    void pullRequestGateFailsWithoutPrAndAsksAHumanWhenTheHostCannotBeQueried() throws Exception {
        String url = seededRemote("nopr.git");
        f.catalog.saveRepository(new RepositoryDefinition(
                "nopr", "nopr", RepositoryProvider.GITHUB, url, null, "main", "acme/nopr", null, null));
        mrWorkflow();
        delivery.supported = true;
        delivery.onQuery = (rc, head) -> List.of();
        Run failed = f.awaitStatus(
                f.engine
                        .start("deliver", "", ExecutionContext.EMPTY, List.of(RepositorySelection.of("nopr")))
                        .id(),
                RunStatus.FAILED);
        assertThat(failed.requireNode("agent").error()).contains("没有 PR");

        delivery.supported = false;
        Run waiting = f.awaitStatus(
                f.engine
                        .start("deliver", "", ExecutionContext.EMPTY, List.of(RepositorySelection.of("nopr")))
                        .id(),
                RunStatus.WAITING);
        assertThat(waiting.requireNode("agent").status()).isEqualTo(NodeStatus.WAITING_APPROVAL);
        assertThat(waiting.requireNode("agent").error()).contains("请人工确认");
    }

    @Test
    void credentialHelperAnswersWithTheHostToken() throws Exception {
        register("app", seededRemote("cred.git"), RepositoryProvider.LOCAL);
        f.close();
        GitCredentialProvider git = run -> new GitCredentials(
                Map.of("git.example.com", new GitCredentials.HttpsCredential(null, "s3cret-token")), null, null, null);
        f = new EngineFixture(root.resolve("ws2"), new InMemoryRunStore(), false, git, DeliveryProvider.NONE);
        f.catalog.saveRepository(new RepositoryDefinition(
                "app",
                "app",
                RepositoryProvider.LOCAL,
                remotes.resolve("cred.git").toUri().toString(),
                null,
                "main",
                null,
                null,
                null));
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("n", SCRIPT)
                        .script("printf 'protocol=https\\nhost=git.example.com\\n\\n' | git credential fill;"
                                + " printf 'protocol=https\\nhost=other.example.com\\n\\n' | git credential fill || echo other=none;"
                                + " env | grep -c s3cret-token; grep -c s3cret-token .loopforge/bin/* || true")
                        .build())
                .build());
        Run done = f.awaitStatus(
                f.engine
                        .start("wf", "", ExecutionContext.EMPTY, List.of(RepositorySelection.of("app")))
                        .id(),
                RunStatus.SUCCEEDED);
        String output = done.requireNode("n").output();
        assertThat(output).contains("username=x-access-token", "password=s3cret-token", "other=none");
        assertThat(output.lines().toList()).contains("0"); // the token is not in the helper file
        assertThat(f.eventTypes(done.id()).toString()).doesNotContain("s3cret");
    }

    @Test
    void promptDescribesWorkspaceLayoutAndDelivery() throws Exception {
        register("app", seededRemote("prompt.git"), RepositoryProvider.GITHUB);
        AgentDefinition dev = AgentDefinition.builder("dev")
                .executor("mock")
                .prompt("修复 {{repository}}")
                .build();
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", AGENT).agent("dev").build())
                .build();
        Run run = Run.create("run-p", wf, "", ExecutionContext.EMPTY, Instant.now());
        run.repositories(
                f.engine.resolveRepositories("run-p", List.of(RepositorySelection.of("app")), ExecutionContext.EMPTY));
        String prompt = PromptCompiler.compile(run, wf.requireNode("a"), dev, List.of(), List.of(), "", "g.json");
        assertThat(prompt)
                .contains("修复 app (github), 最新远端基线 main, 本次工作分支 feature/fix-")
                .contains("# Workspace layout", "`./app`: app")
                .contains("## Git 仓库交付", "gh pr create", "禁止向 main 直接推送");
    }

    @Test
    void runsOnlyUseSopsAndRepositoriesOfTheirOwnProject() throws Exception {
        f.catalog.saveRepository(new RepositoryDefinition(
                "b-repo",
                "b-repo",
                RepositoryProvider.LOCAL,
                seededRemote("b-remote.git").toString(),
                null,
                "main",
                null,
                "project-b",
                null));
        f.catalog.saveWorkflow(io.github.loopforge4j.core.json.Json.read(
                "{\"id\":\"b-sop\",\"projectId\":\"project-b\",\"status\":\"published\","
                        + "\"nodes\":[{\"id\":\"a\",\"type\":\"script\",\"script\":\"true\"}]}",
                WorkflowDefinition.class));
        f.workflow(WorkflowDefinition.builder("global")
                .node(WorkflowNode.builder("a", SCRIPT).script("true").build())
                .build());
        ExecutionContext inA = new ExecutionContext("project-a", "dev", null, null, null);
        ExecutionContext inB = new ExecutionContext("project-b", "dev", null, null, null);
        assertThatThrownBy(() -> f.engine.start("b-sop", "", inA, List.of())).isInstanceOf(RunNotFoundException.class);
        assertThatThrownBy(() -> f.engine.start("global", "", inA, List.of(RepositorySelection.of("b-repo"))))
                .isInstanceOf(RunNotFoundException.class)
                .hasMessageContaining("b-repo");
        assertThatThrownBy(() -> f.engine.start("b-sop", "", ExecutionContext.EMPTY, List.of()))
                .isInstanceOf(RunNotFoundException.class);
        Run ok = f.engine.start("b-sop", "", inB, List.of(RepositorySelection.of("b-repo")));
        f.awaitStatus(ok.id(), io.github.loopforge4j.core.domain.RunStatus.SUCCEEDED);
    }

    // ------------------------------------------------------------------ helpers

    private void mrWorkflow() {
        f.agent(AgentDefinition.builder("deliverer")
                        .executor("mock")
                        .prompt("交付")
                        .verification(List.of(VerificationGate.mr("pr", "PR 已创建", FailurePolicy.FAIL)))
                        .build())
                .workflow(WorkflowDefinition.builder("deliver")
                        .node(WorkflowNode.builder("push", SCRIPT)
                                .scope(ExecutionScope.REPOSITORIES)
                                .script(
                                        "echo x > X.md && git add X.md && git commit -qm x && git push -q origin \"$LOOPFORGE_WORK_BRANCH\"")
                                .build())
                        .node(WorkflowNode.builder("agent", AGENT)
                                .agent("deliverer")
                                .build())
                        .chain("push", "agent")
                        .build());
    }

    private void register(String id, String url, RepositoryProvider provider) {
        f.catalog.saveRepository(new RepositoryDefinition(id, id, provider, url, null, "main", null, null, null));
    }

    private String seededRemote(String name) throws Exception {
        Path bare = remotes.resolve(name);
        git(remotes, "init", "-q", "--bare", "--initial-branch=main", name);
        Path seed = Files.createDirectories(root.resolve("seed-" + name));
        git(seed, "init", "-q", "--initial-branch=main");
        Files.writeString(seed.resolve("README.md"), "# " + name + "\n");
        git(seed, "add", "README.md");
        git(seed, "-c", "user.name=seed", "-c", "user.email=seed@example.invalid", "commit", "-qm", "init");
        git(seed, "push", "-q", bare.toUri().toString(), "main");
        return bare.toUri().toString();
    }

    private void pushBranch(String url, String branch, String file) throws Exception {
        Path work = Files.createDirectories(root.resolve("branch-" + branch.replace('/', '_')));
        git(work, "clone", "-q", url, ".");
        git(work, "checkout", "-qb", branch);
        Files.writeString(work.resolve(file), file);
        git(work, "add", file);
        git(work, "-c", "user.name=seed", "-c", "user.email=seed@example.invalid", "commit", "-qm", "wip");
        git(work, "push", "-q", "origin", branch);
    }

    private static String tip(String url, String branch) {
        try {
            String out = git(Path.of(java.net.URI.create(url)), "rev-parse", "refs/heads/" + branch);
            return out.strip();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    /** Fake hosting API. */
    static final class FakeDelivery implements DeliveryProvider {
        volatile boolean supported;
        volatile java.util.function.BiFunction<RepositoryContext, String, List<PullRequest>> onQuery =
                (rc, h) -> List.of();
        final List<String> queries = new CopyOnWriteArrayList<>();

        @Override
        public boolean supports(RepositoryContext repository) {
            return supported;
        }

        @Override
        public List<PullRequest> pullRequests(RepositoryContext repository, String head, String base)
                throws DeliveryException {
            queries.add(repository.repositoryId() + ":" + head + "->" + base);
            return onQuery.apply(repository, head);
        }
    }
}

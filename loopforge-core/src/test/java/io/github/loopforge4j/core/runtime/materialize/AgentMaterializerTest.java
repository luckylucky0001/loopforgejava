package io.github.loopforge4j.core.runtime.materialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AgentHook;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.ExecutionScope;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.ScriptLanguage;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class AgentMaterializerTest {

    @TempDir
    Path workspace;

    InMemoryCatalogStore catalog;
    AgentDefinition agent;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalogStore(Clock.systemUTC());
        catalog.saveAsset(AssetKind.RULE, new RuleDefinition("low", "低优先级", null, "先写测试", 1, null));
        catalog.saveAsset(AssetKind.RULE, new RuleDefinition("high", "高优先级", null, "禁止删除数据", 9, null));
        catalog.saveAsset(
                AssetKind.SKILL,
                new SkillDefinition(
                        "review",
                        "评审",
                        "代码评审步骤",
                        "# Review\n\n按清单评审。",
                        Map.of("scripts/check.sh", "echo ok\n", "references/list.md", "- a\n"),
                        null));
        catalog.saveAsset(
                AssetKind.MCP_SERVER,
                McpServerDefinition.stdio(
                        "tracker",
                        "tracker-mcp",
                        List.of("--token", "${secret:TRACKER}"),
                        Map.of("API_KEY", "${secret:TRACKER}")));
        catalog.saveAsset(
                AssetKind.MCP_SERVER,
                new McpServerDefinition(
                        "docs",
                        "Docs",
                        null,
                        "http",
                        null,
                        null,
                        "https://mcp.example.com/mcp",
                        null,
                        Map.of("Authorization", "Bearer ${secret:DOCS}"),
                        null,
                        30,
                        null));
        agent = AgentDefinition.builder("dev")
                .name("开发")
                .rules(List.of("low", "high", "所有提交都要写中文说明"))
                .skills(List.of("review"))
                .mcpServers(List.of("tracker", "docs"))
                .hooks(List.of(
                        new AgentHook(
                                "guard",
                                "命令守卫",
                                "PreToolUse",
                                "Bash",
                                ScriptLanguage.SHELL,
                                "cat > got.json\nexit 3",
                                10,
                                null),
                        new AgentHook(
                                "lint",
                                "仓库检查",
                                "Stop",
                                null,
                                ScriptLanguage.SHELL,
                                "pwd > where.txt",
                                0,
                                ExecutionScope.REPOSITORIES)))
                .build();
    }

    @Test
    void compilesRulesSkillsMcpAndHooks() throws Exception {
        CompiledAgent compiled = materialize("inv-1");
        Path agentDir = workspace.toRealPath().resolve(".loopforge/agents/dev");
        assertThat(compiled.agentDir()).isEqualTo(agentDir);

        String system = Files.readString(agentDir.resolve("agent-system.md"));
        assertThat(system.indexOf("禁止删除数据")).isLessThan(system.indexOf("先写测试"));
        assertThat(system)
                .contains("## Selected Skills", "name: `review`", "## MCP 怎么用", "tracker、docs、loopforge-gate");
        assertThat(system).doesNotContain("所有提交都要写中文说明");
        assertThat(AgentAssets.inlineRules(agent, catalog)).containsExactly("所有提交都要写中文说明");

        String skill = Files.readString(agentDir.resolve("skills/review/SKILL.md"));
        assertThat(skill)
                .startsWith("---\nname: \"review\"\ndescription: \"代码评审步骤\"\n---\n")
                .contains("按清单评审");
        assertThat(Files.isExecutable(agentDir.resolve("skills/review/scripts/check.sh")))
                .isTrue();
        assertThat(agentDir.resolve("skills/review/references/list.md")).exists();
        assertThat(Files.readString(agentDir.resolve(".claude-plugin/plugin.json")))
                .contains("loopforge-dev");

        JsonNode mcp = Json.mapper().readTree(Files.readString(compiled.mcpConfig()));
        assertThat(mcp.path("mcpServers")
                        .path("tracker")
                        .path("env")
                        .path("API_KEY")
                        .asString())
                .isEqualTo("s3cr3t");
        assertThat(mcp.path("mcpServers").path("tracker").path("args").get(1).asString())
                .isEqualTo("s3cr3t");
        assertThat(mcp.path("mcpServers")
                        .path("docs")
                        .path("headers")
                        .path("Authorization")
                        .asString())
                .isEqualTo("Bearer d0cs");
        JsonNode gate = mcp.path("mcpServers").path("loopforge-gate");
        assertThat(gate.path("env").path("LOOPFORGE_GATE_RESULT_PATH").asString())
                .endsWith(".loopforge/gate-results/n/attempt-1.json");
        assertThat(workspace.resolve(".loopforge/bin/loopforge-gate-mcp.jar")).exists();

        JsonNode settings = Json.mapper().readTree(Files.readString(compiled.hookSettings()));
        JsonNode pre = settings.path("hooks").path("PreToolUse").get(0);
        assertThat(pre.path("matcher").asString()).isEqualTo("Bash");
        assertThat(pre.path("hooks").get(0).path("command").asString())
                .startsWith("sh '")
                .endsWith("guard-run.sh'");
        assertThat(pre.path("hooks").get(0).path("timeout").asInt()).isEqualTo(10);
        assertThat(settings.path("hooks").path("Stop").get(0).has("matcher")).isFalse();
        assertThat(compiled.environment()).containsEntry("LOOPFORGE_REPOSITORY_DIRS", "app\nlib");

        // per-invocation files (resolved secrets) disappear; shared role files stay
        compiled.cleanup();
        assertThat(compiled.runtimeDir()).doesNotExist();
        assertThat(agentDir.resolve("agent-system.md")).exists();
    }

    @Test
    void removedBindingsAreRemovedFromTheWorkspace() throws Exception {
        materialize("inv-1");
        agent = AgentDefinition.builder("dev").name("开发").build();
        materialize("inv-2");
        Path agentDir = workspace.resolve(".loopforge/agents/dev");
        assertThat(agentDir.resolve("skills/review")).doesNotExist();
        assertThat(agentDir.resolve(".claude-plugin")).doesNotExist();
        assertThat(agentDir.resolve("hooks/guard.sh")).doesNotExist();
    }

    @Test
    void missingSecretsAndBindingsAreConfigurationErrors() {
        assertThatThrownBy(() -> AgentMaterializer.materialize(new AgentMaterializer.Request(
                        workspace,
                        "inv",
                        "claude-code",
                        agent,
                        AgentAssets.resolve(agent, catalog),
                        null,
                        null,
                        n -> null,
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret 'TRACKER' is not configured");
        AgentDefinition broken =
                AgentDefinition.builder("x").skills(List.of("ghost")).build();
        assertThatThrownBy(() -> AgentAssets.resolve(broken, catalog)).hasMessage("skill 'ghost' not found");
    }

    @Test
    void skillFilesCannotEscapeTheSkillRoot() {
        for (String path : List.of("../x", "/etc/passwd", "a/../../b", "a\\b", "")) {
            assertThatThrownBy(() -> SkillMarkdown.checkPath(path))
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(SkillMarkdown.checkPath("scripts/run.sh")).isEqualTo("scripts/run.sh");
    }

    @Test
    void aPlantedSymlinkIsNotFollowed() throws Exception {
        Path outside = Files.createDirectories(workspace.resolveSibling(workspace.getFileName() + "-outside"));
        Files.createDirectories(workspace.resolve(".loopforge/agents"));
        Files.createSymbolicLink(workspace.resolve(".loopforge/agents/dev"), outside);
        assertThatThrownBy(() -> materialize("inv-1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symlink");
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    void existingFrontmatterKeepsFieldsButNameFollowsTheId() {
        String body = SkillMarkdown.ensureFrontmatter("---\nname: other\nlicense: MIT\n---\nBody\n", "review", "说明");
        // like upstream setFrontmatterField, a missing field is prepended
        assertThat(body).isEqualTo("---\ndescription: \"说明\"\nname: \"review\"\nlicense: MIT\n---\nBody\n");
        assertThat(SkillMarkdown.description(body)).isEqualTo("说明");
    }

    @Test
    void hookWrapperPassesThePayloadAndRecordsObservations() throws Exception {
        CompiledAgent compiled = materialize("inv-1");
        Files.createDirectories(workspace.resolve("app"));
        Files.createDirectories(workspace.resolve("lib"));
        Map<String, String> env = Map.of(
                "LOOPFORGE_HOOK_OUTPUT",
                compiled.hookOutput().toString(),
                "LOOPFORGE_WORKSPACE",
                workspace.toString(),
                "LOOPFORGE_REPOSITORY_DIRS",
                "app\nlib",
                "PATH",
                "/usr/bin:/bin");
        assertThat(run(compiled.hooks().get(0).command(), "{\"tool\":\"Bash\"}", env))
                .isEqualTo(3);
        assertThat(Files.readString(workspace.resolve("got.json"))).isEqualTo("{\"tool\":\"Bash\"}");
        assertThat(run(compiled.hooks().get(1).command(), "{}", env)).isZero();
        assertThat(Files.readString(workspace.resolve("app/where.txt")).strip()).endsWith("/app");
        assertThat(Files.readString(workspace.resolve("lib/where.txt")).strip()).endsWith("/lib");

        List<String> lines = Files.readAllLines(compiled.hookOutput());
        assertThat(lines).hasSize(6);
        assertThat(lines.get(1)).contains("\"hookId\":\"guard\"", "\"phase\":\"finish\"", "\"exitCode\":3");
        assertThat(lines.get(3)).contains("\"hookId\":\"lint\"", "\"repository\":\"app\"", "\"exitCode\":0");
        for (String line : lines) {
            assertThat(Json.mapper().readTree(line).path("hookId").asString()).isNotBlank();
        }

        Map<String, String> noRepos = new java.util.HashMap<>(env);
        noRepos.remove("LOOPFORGE_REPOSITORY_DIRS");
        assertThat(run(compiled.hooks().get(1).command(), "{}", noRepos)).isEqualTo(2);
    }

    @Test
    void workspaceReachedThroughASymlinkedParentWorks() throws Exception {
        Path link = workspace.resolveSibling(workspace.getFileName() + "-link");
        Files.createSymbolicLink(link, workspace);
        AgentMaterializer.writeControlFile(link, link.resolve(".loopforge/home/x/.cursor/mcp.json"), "{}");
        assertThat(workspace.resolve(".loopforge/home/x/.cursor/mcp.json")).hasContent("{}");
    }

    private int run(String command, String stdin, Map<String, String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).directory(workspace.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        Process p = builder.start();
        try (var in = p.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    private CompiledAgent materialize(String invocation) throws IOException {
        return AgentMaterializer.materialize(new AgentMaterializer.Request(
                workspace,
                invocation,
                "claude-code",
                agent,
                AgentAssets.resolve(agent, catalog),
                ".loopforge/gate-results/n/attempt-1.json",
                null,
                Map.of("TRACKER", "s3cr3t", "DOCS", "d0cs")::get,
                List.of("app", "lib")));
    }
}

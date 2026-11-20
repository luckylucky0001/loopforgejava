package io.github.loopforge4j.core.runtime.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RepositoryProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryRemoteTest {

    @TempDir
    Path workspace;

    @Test
    void parsesHttpsSshAndScpRemotes() {
        assertThat(RepositoryRemote.parse("https://oauth2@GitLab.Example.com:8443/group/sub/app.git"))
                .contains(new RepositoryRemote("gitlab.example.com", "group/sub/app"));
        assertThat(RepositoryRemote.parse("ssh://git@git.woa.com/team/app"))
                .contains(new RepositoryRemote("git.woa.com", "team/app"));
        assertThat(RepositoryRemote.parse("git@gitlab.com:acme/app.git"))
                .contains(new RepositoryRemote("gitlab.com", "acme/app"));
        assertThat(RepositoryRemote.parse("/srv/git/app.git")).isEmpty();
        assertThat(RepositoryRemote.parse("https://host/../etc")).isEmpty();
    }

    @Test
    void gitlabRepositoriesGetTheGlabToken() throws Exception {
        RepositoryContext rc = new RepositoryContext(
                "app",
                "app",
                RepositoryProvider.GITLAB,
                "https://gitlab.example.com/group/app.git",
                null,
                "main",
                "feature/x",
                "app",
                false,
                false,
                false,
                null);
        Map<String, String> env = GitEnvironment.build(
                workspace,
                new GitCredentials(
                        Map.of("gitlab.example.com", new GitCredentials.HttpsCredential("oauth2", "glpat-1")),
                        null,
                        null,
                        null),
                List.of(rc));
        assertThat(env).containsEntry("GITLAB_TOKEN", "glpat-1").containsEntry("GITLAB_HOST", "gitlab.example.com");
        assertThat(env).doesNotContainKey("GH_TOKEN");
        assertThat(RepositoryProvider.GITLAB.hostsPullRequests()).isTrue();
    }
}

package io.github.loopforge4j.server.tracker;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tracker clients with the same hosts and credentials as pull request delivery. */
@Configuration(proxyBeanMethods = false)
class TrackerConfiguration {

    @Bean
    GitHubIssues gitHubIssues(LoopForgeProperties properties) {
        URI api = properties.github().apiUrl();
        String host = api.getHost().equals("api.github.com") ? "github.com" : api.getHost();
        return new GitHubIssues(api, () -> token(properties, host));
    }

    @Bean
    GitLabIssues gitLabIssues(LoopForgeProperties properties) {
        Map<String, URI> hosts = new LinkedHashMap<>();
        for (LoopForgeProperties.GitLabHost h : properties.gitlab()) {
            String name = h.host().strip().toLowerCase(Locale.ROOT);
            hosts.put(name, h.apiUrl() != null ? h.apiUrl() : URI.create("https://" + name + "/api/v4"));
        }
        return new GitLabIssues(hosts, host -> token(properties, host));
    }

    @Bean
    TapdClient tapdClient(LoopForgeProperties properties) {
        LoopForgeProperties.Tapd tapd = properties.tapd();
        return new TapdClient(tapd.apiUrl(), tapd.webUrl(), tapd.apiUser(), tapd.apiPassword());
    }

    private static String token(LoopForgeProperties properties, String host) {
        return properties.git().https().stream()
                .filter(c -> c.host().equalsIgnoreCase(host))
                .map(LoopForgeProperties.HttpsCredential::token)
                .findFirst()
                .orElse(null);
    }
}

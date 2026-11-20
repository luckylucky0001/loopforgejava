package io.github.loopforge4j.server.skillhub;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The SkillHub skills market. */
@Tag(name = "Skills market")
@RestController
@RequestMapping("/api/v1/skillhub")
class SkillHubController {

    private final SkillHubService market;

    SkillHubController(SkillHubService market) {
        this.market = market;
    }

    @GetMapping("/status")
    Map<String, Object> status(Principal principal) {
        return market.status(principal);
    }

    @Operation(summary = "Search the market (keyword, category, namespace, sortBy, page, pageSize ≤ 50)")
    @GetMapping("/skills")
    JsonNode list(@RequestParam Map<String, String> query, Principal principal) {
        return market.list(query, principal);
    }

    @GetMapping("/categories")
    JsonNode categories(Principal principal) {
        return market.categories(principal);
    }

    @GetMapping("/skills/{slug}")
    JsonNode detail(@PathVariable String slug, @RequestParam(required = false) String namespace, Principal principal) {
        return market.detail(slug, namespace, principal);
    }

    @GetMapping("/skills/{slug}/versions")
    JsonNode versions(
            @PathVariable String slug, @RequestParam(required = false) String namespace, Principal principal) {
        return market.versions(slug, namespace, principal);
    }

    @GetMapping("/skills/{slug}/files")
    JsonNode files(
            @PathVariable String slug,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String version,
            Principal principal) {
        return market.files(slug, namespace, version, principal);
    }

    @GetMapping("/skills/{slug}/file")
    Map<String, Object> file(
            @PathVariable String slug,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String version,
            @RequestParam String path,
            Principal principal) {
        return market.file(slug, namespace, version, path, principal);
    }

    @GetMapping("/installed")
    List<SkillHubService.Source> installed(Principal principal) {
        return market.installed(principal);
    }

    @Operation(summary = "Install a version (latest by default) as a catalog skill (administrators)")
    @PostMapping("/install")
    ResponseEntity<SkillHubService.Installed> install(@RequestBody SkillHubService.Install body, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(market.install(body, principal));
    }

    @Operation(summary = "Replace an installed skill with another version (administrators)")
    @PostMapping("/update")
    SkillHubService.Installed update(@RequestBody SkillHubService.Update body, Principal principal) {
        return market.update(body, principal);
    }
}

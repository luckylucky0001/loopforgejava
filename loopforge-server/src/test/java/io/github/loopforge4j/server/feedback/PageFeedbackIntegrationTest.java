package io.github.loopforge4j.server.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** The console's page feedback: submit with images, sanitised context, who may read what. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageFeedbackIntegrationTest {

    static final String TOKEN = "page-feedback-token-0123456789-abcdef";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};
    static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0, 0};

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-feedback-it");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:page-feedback;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    RestClient client(String token) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @SuppressWarnings("unchecked")
    <T> T call(RestClient c, String method, String path, Object body, int status) {
        var spec = c.method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> r = spec.retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        return r.getBody() == null || r.getBody().isBlank() ? null : (T) Json.read(r.getBody(), Object.class);
    }

    RestClient person(RestClient admin, String name, String role) {
        call(
                admin,
                "POST",
                "/api/v1/users",
                Map.of("username", name, "password", name + "-password-1", "role", role),
                201);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return client((String) login.get("token"));
    }

    /** A PNG header followed by zeros, {@code size} bytes in all. */
    static byte[] png(int size) {
        byte[] data = new byte[size];
        System.arraycopy(PNG, 0, data, 0, PNG.length);
        return data;
    }

    static ByteArrayResource file(byte[] data, String name) {
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }

    ResponseEntity<String> submit(RestClient c, Object payload, List<ByteArrayResource> images) {
        org.springframework.util.MultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        org.springframework.http.HttpHeaders json = new org.springframework.http.HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        form.add("payload", new org.springframework.http.HttpEntity<>(Json.write(payload), json));
        for (ByteArrayResource image : images) {
            // the declared type is ignored: the server looks at the bytes
            org.springframework.http.HttpHeaders png = new org.springframework.http.HttpHeaders();
            png.setContentType(MediaType.IMAGE_PNG);
            form.add("images", new org.springframework.http.HttpEntity<>(image, png));
        }
        return c.post()
                .uri("/api/v1/page-feedback")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void feedbackIsSanitisedAndReadableOnlyByItsAuthorAndAdministrators() {
        RestClient service = client(TOKEN);
        RestClient hank = person(service, "hank", "member");
        RestClient ivy = person(service, "ivy", "member");
        RestClient ada = person(service, "ada", "admin");
        Map<String, Object> payload = Map.of(
                "title", "保存按钮没反应",
                "description", "点击后没有任何提示",
                "url", "https://user:pw@lf.example/console/?session=s1&lang=zh#/runs/r1?access_token=abc&tab=log",
                "route", "#/runs/r1?token=x&tab=log&api-key=k",
                "view", "运行\n详情",
                "context", Map.of("viewport", Map.of("width", 1280, "height", 800), "dpr", 2, "userAgent", "UA\t1"),
                "element",
                        Map.of(
                                "selector",
                                "button.primary",
                                "tag",
                                "BUTTON",
                                "text",
                                "  保存 \n  按钮 ",
                                "rect",
                                Map.of("x", 1, "y", 2, "width", 30, "height", 10)),
                "submitter", Map.of("username", "someone-else"));

        ResponseEntity<String> created =
                submit(hank, payload, List.of(file(PNG, "shot.png"), file(GIF, "../anim.gif")));
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        Map<String, Object> feedback = Json.read(created.getBody(), Map.class);
        assertThat(feedback)
                .containsEntry("url", "https://lf.example/console/?lang=zh#/runs/r1?tab=log")
                .containsEntry("route", "#/runs/r1?tab=log")
                .containsEntry("view", "运行 详情");
        assertThat((Map<String, Object>) feedback.get("submitter")).containsEntry("username", "hank");
        assertThat((Map<String, Object>) feedback.get("element")).containsEntry("text", "保存 按钮");
        assertThat((Map<String, Object>) feedback.get("context")).containsEntry("userAgent", "UA 1");
        List<Map<String, Object>> images = (List<Map<String, Object>>) feedback.get("images");
        assertThat(images).extracting(i -> i.get("mimeType")).containsExactly("image/png", "image/gif");
        assertThat(images).extracting(i -> i.get("fileName")).containsExactly("shot.png", "anim.gif");

        // refused input
        assertThat(submit(hank, Map.of("title", " "), List.of()).getStatusCode().value())
                .isEqualTo(400);
        assertThat(submit(hank, Map.of("title", "x".repeat(121)), List.of())
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        ResponseEntity<String> notAnImage =
                submit(hank, Map.of("title", "t"), List.of(file("<svg onload=alert(1)>".getBytes(), "x.png")));
        assertThat(notAnImage.getStatusCode().value()).isEqualTo(400);
        assertThat(notAnImage.getBody()).contains("不是支持的图片格式");
        assertThat(submit(hank, Map.of("title", "t"), java.util.Collections.nCopies(6, file(PNG, "a.png")))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        ResponseEntity<String> big =
                submit(hank, Map.of("title", "t"), List.of(file(png(5 * 1024 * 1024 + 1), "big.png")));
        assertThat(big.getStatusCode().value()).as(big.getBody()).isEqualTo(400);
        assertThat(big.getBody()).contains("超过 5MB");
        // the service token has no account to file feedback under
        assertThat(submit(service, Map.of("title", "t"), List.of())
                        .getStatusCode()
                        .value())
                .isEqualTo(403);

        // the author and administrators read it; nobody else
        String id = (String) feedback.get("id");
        assertThat((List<Object>)
                        ((Map<String, Object>) call(hank, "GET", "/api/v1/page-feedback", null, 200)).get("items"))
                .hasSize(1);
        assertThat((Map<String, Object>) call(ivy, "GET", "/api/v1/page-feedback", null, 200))
                .containsEntry("total", 0);
        assertThat((Map<String, Object>) call(ada, "GET", "/api/v1/page-feedback?limit=1", null, 200))
                .containsEntry("total", 1)
                .containsEntry("hasMore", false);
        call(ivy, "GET", "/api/v1/page-feedback/" + id, null, 404);
        call(ada, "GET", "/api/v1/page-feedback/" + id, null, 200);
        String imageUrl = (String) images.getFirst().get("url");
        ResponseEntity<byte[]> image = hank.get().uri(imageUrl).retrieve().toEntity(byte[].class);
        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getBody()).isEqualTo(PNG);
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(image.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(image.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        assertThat(ivy.get()
                        .uri(imageUrl)
                        .retrieve()
                        .toEntity(byte[].class)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        call(hank, "GET", "/api/v1/page-feedback/" + id + "/images/img_nope", null, 404);
    }
}

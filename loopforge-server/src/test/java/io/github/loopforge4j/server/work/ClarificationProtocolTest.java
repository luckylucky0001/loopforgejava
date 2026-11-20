package io.github.loopforge4j.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClarificationProtocolTest {

    @Test
    void takesTheLastFencedResult() throws Exception {
        var r = ClarificationProtocol.parse("""
                先看看 ```json
                {"example": true}
                ```
                结论：
                ```json
                {"ready": true, "summary": "  本次范围：A  ", "questions": [{"question": "ignored"}]}
                ```
                """);
        assertThat(r.ready()).isTrue();
        assertThat(r.summary()).isEqualTo("本次范围：A");
        assertThat(r.questions()).isEmpty();
        assertThat(r.json()).contains("\"ready\":true");
    }

    @Test
    void findsAnUnfencedObjectAtTheEnd() throws Exception {
        var r = ClarificationProtocol.parse("想了想 {不是 JSON} 然后 {\"ready\": false, \"questions\": [\"放在哪里？\"]}");
        assertThat(r.ready()).isFalse();
        assertThat(r.questions()).singleElement().satisfies(q -> {
            assertThat(q.question()).isEqualTo("放在哪里？");
            assertThat(q.freeText()).isTrue();
            assertThat(q.id()).isEqualTo("q1");
        });
    }

    @Test
    void normalizesQuestions() throws Exception {
        var r = ClarificationProtocol.parse("""
                ```json
                {"ready": false, "questions": [
                  {"id": "a", "question": "选哪个？", "options": ["x", "x", {"label": "y"}], "multiSelect": true},
                  {"id": "a", "question": "填写路径", "options": ["忽略"], "freeText": true},
                  {"question": "q3", "options": []}, {"question": "q4"}, {"question": "q5"}, {"question": "q6"}
                ]}
                ```""");
        assertThat(r.questions()).hasSize(ClarificationProtocol.MAX_QUESTIONS);
        var choice = r.questions().get(0);
        assertThat(choice.options()).containsExactly("x", "y");
        assertThat(choice.multiSelect()).isTrue();
        var free = r.questions().get(1);
        assertThat(free.id()).isEqualTo("q2");
        assertThat(free.options()).isEmpty();
        assertThat(free.freeText()).isTrue();
        assertThat(free.multiSelect()).isFalse();
    }

    @Test
    void rejectsResultsTheConsoleCannotUse() {
        assertThatThrownBy(() -> ClarificationProtocol.parse("没有 JSON"))
                .isInstanceOf(ClarificationProtocol.InvalidResult.class)
                .hasMessageContaining("未返回 JSON");
        assertThatThrownBy(() -> ClarificationProtocol.parse("{\"ready\": true, \"summary\": \" \"}"))
                .hasMessageContaining("缺少 summary");
        assertThatThrownBy(() -> ClarificationProtocol.parse("{\"ready\": false, \"summary\": \"缺口：A\"}"))
                .hasMessageContaining("questions");
        assertThatThrownBy(
                        () -> ClarificationProtocol.parse("{\"ready\": false, \"questions\": [{\"question\": \"\"}]}"))
                .hasMessageContaining("问题文本为空");
        assertThatThrownBy(() -> ClarificationProtocol.parse(null)).hasMessageContaining("未返回 JSON");
    }

    @Test
    void theConclusionIsTheSingleLastBlockOfTheDescription() {
        assertThat(ClarificationProtocol.withConclusion(null, "A")).isEqualTo("## LoopForge 澄清结论\n\nA");
        String once = ClarificationProtocol.withConclusion("原始描述\n\n", " A ");
        assertThat(once).isEqualTo("原始描述\n\n## LoopForge 澄清结论\n\nA");
        assertThat(ClarificationProtocol.withConclusion(once, "B")).isEqualTo("原始描述\n\n## LoopForge 澄清结论\n\nB");
    }

    @Test
    void budgetNudgesAfterThreeRounds() {
        assertThat(ClarificationService.budget(2)).isEmpty();
        assertThat(ClarificationService.budget(3)).contains("已经澄清了 3 轮", "ready=true");
    }
}

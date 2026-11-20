package io.github.loopforge4j.server.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsMathTest {

    @Test
    void nearestRankPercentiles() {
        List<Long> values = List.of(5L, 1L, 4L, 2L, 3L, 10L, 9L, 8L, 7L, 6L);
        assertThat(AnalyticsService.percentile(values, 50)).isEqualTo(5);
        assertThat(AnalyticsService.percentile(values, 95)).isEqualTo(10);
        assertThat(AnalyticsService.percentile(List.of(), 95)).isZero();
        assertThat(AnalyticsService.average(List.of(1L, 2L))).isEqualTo(2);
    }

    @Test
    void bucketsFollowTheViewersZone() {
        Instant lateSundayUtc = Instant.parse("2026-11-22T20:00:00Z");
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        // already Monday in Shanghai
        assertThat(AnalyticsService.bucket(lateSundayUtc, "day", shanghai)).isEqualTo("2026-11-23");
        assertThat(AnalyticsService.bucket(lateSundayUtc, "week", shanghai)).isEqualTo("2026-11-23");
        assertThat(AnalyticsService.bucket(lateSundayUtc, "week", ZoneId.of("UTC")))
                .isEqualTo("2026-11-16");
        assertThat(AnalyticsService.bucket(lateSundayUtc, "month", shanghai)).isEqualTo("2026-11");
    }
}

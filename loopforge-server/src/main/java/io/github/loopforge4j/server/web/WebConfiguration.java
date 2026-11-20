package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.RunStatus;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Query parameters use the same lower-case wire values as JSON payloads ({@code ?status=running}). */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, RunStatus.class, RunStatus::fromWire);
    }
}

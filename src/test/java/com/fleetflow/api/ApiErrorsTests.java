package com.fleetflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiErrorsTests {
    @Test
    void unexpectedErrorsUseTheProblemContractAndNeverLogExceptionParameters() {
        var errors = new ApiErrors();
        var request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.setAttribute("phase1CorrelationId", "redaction-test");
        Logger logger = (Logger) LoggerFactory.getLogger(ApiErrors.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
        String sensitive = "access-token refresh-token password secret 12 Private Street GPS 30.1,31.2 polygon incident-text";
        try {
            var response = errors.unexpected(new IllegalStateException(sensitive), request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().correlationId()).isEqualTo("redaction-test");
            assertThat(response.getBody().message()).doesNotContain(sensitive);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain(sensitive);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            assertThat(appender.list.getFirst().getKeyValuePairs().toString()).doesNotContain(sensitive);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}

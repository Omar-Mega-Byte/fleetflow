package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTests {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void keepsValidIdForLogsAndResponseThenClearsIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("correlationId")).isEqualTo("request-123"));

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("request-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void replacesUnsafeId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "unsafe\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .matches("[0-9a-f-]{36}");
    }

    @Test
    void structuredRequestLogDoesNotCopyCredentialsAddressesLocationsGeometryOrFreeText() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod("POST");
            request.setRequestURI("/api/v1/orders/actual-order-id/30.0444,31.2357");
            request.addHeader("Authorization", "Bearer access-secret-value");
            request.addHeader("X-Refresh-Token", "refresh-secret-value");
            request.addHeader("X-Client-Secret", "client-secret-value");
            request.setContent(("{\"password\":\"password-secret-value\","
                    + "\"address\":\"Sensitive Customer Street\","
                    + "\"location\":\"30.0444,31.2357\","
                    + "\"polygon\":\"POLYGON((31 30,32 30,32 31,31 31,31 30))\","
                    + "\"incident\":\"Sensitive incident text\"}").getBytes());
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders/{id}");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            String structuredEvent = event.getFormattedMessage() + event.getMDCPropertyMap()
                    + event.getKeyValuePairs();
            assertThat(structuredEvent).contains("/api/v1/orders/{id}")
                    .doesNotContain("access-secret-value", "refresh-secret-value", "password-secret-value",
                            "client-secret-value", "Sensitive Customer Street", "30.0444,31.2357",
                            "POLYGON((31 30,32 30,32 31,31 31,31 30))", "Sensitive incident text");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

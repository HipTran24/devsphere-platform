package com.project.devsphere.operations.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesValidTraceHeaderAndExposesItDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "trace-ABC_123.x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY)));

        assertThat(seen.get()).isEqualTo("trace-ABC_123.x");
        assertThat(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER)).isEqualTo("trace-ABC_123.x");
        assertThat(request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE)).isEqualTo("trace-ABC_123.x");
        assertThat(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void fallsBackToRequestIdHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestTraceFilter.REQUEST_ID_HEADER, "req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER)).isEqualTo("req-42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "line\nbreak", "semi;colon", "<script>", "\u00e9t\u00e9",
            "a-65-characters-long-trace-id-that-exceeds-the-maximum-length-abcd"})
    void replacesInvalidIdentifiersWithRandomUuid(String invalid) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, invalid);
        request.addHeader(RequestTraceFilter.REQUEST_ID_HEADER, invalid);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String traceId = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        assertThat(traceId).isNotEqualTo(invalid);
        assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
    }

    @Test
    void generatesTraceIdWhenAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/"), response, (req, res) -> { });

        assertThat(RequestTraceFilter.isValidTraceId(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER))).isTrue();
    }

    @Test
    void clearsMdcEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "boom-trace");
        FilterChain failing = (req, res) -> { throw new IllegalStateException("downstream failure"); };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void restoresAnOuterTraceAfterNestedProcessing() throws Exception {
        MDC.put(RequestTraceFilter.TRACE_ID_MDC_KEY, "outer-trace");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "inner-trace");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(RequestTraceFilter.currentTraceId()).isEqualTo("inner-trace"));

        assertThat(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY)).isEqualTo("outer-trace");
    }

    @Test
    void validatesTraceIdAlphabetAndLength() {
        assertThat(RequestTraceFilter.isValidTraceId("a")).isTrue();
        assertThat(RequestTraceFilter.isValidTraceId("x".repeat(64))).isTrue();
        assertThat(RequestTraceFilter.isValidTraceId("x".repeat(65))).isFalse();
        assertThat(RequestTraceFilter.isValidTraceId(null)).isFalse();
        assertThat(RequestTraceFilter.isValidTraceId("abc/def")).isFalse();
    }
}

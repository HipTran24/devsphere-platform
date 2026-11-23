package com.project.devsphere.operations.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlates every request with a trace identifier.
 *
 * <p>A caller-supplied {@code X-Trace-Id} (or {@code X-Request-Id}) is reused only when it matches a
 * strict, log-safe alphabet; otherwise a random UUID is generated. The identifier is exposed to
 * application code through the SLF4J {@link MDC} under {@link #TRACE_ID_MDC_KEY}, echoed in the
 * {@code X-Trace-Id} response header, and always removed from the MDC when the request completes.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        String previous = MDC.get(TRACE_ID_MDC_KEY);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous != null) {
                MDC.put(TRACE_ID_MDC_KEY, previous);
            } else {
                MDC.remove(TRACE_ID_MDC_KEY);
            }
        }
    }

    /** Returns the first valid caller-supplied identifier, or a freshly generated UUID. */
    static String resolveTraceId(HttpServletRequest request) {
        String traceHeader = request.getHeader(TRACE_ID_HEADER);
        if (isValidTraceId(traceHeader)) {
            return traceHeader;
        }
        String requestIdHeader = request.getHeader(REQUEST_ID_HEADER);
        if (isValidTraceId(requestIdHeader)) {
            return requestIdHeader;
        }
        return UUID.randomUUID().toString();
    }

    /** True when the value is 1..64 characters drawn from {@code [a-zA-Z0-9._-]}. */
    public static boolean isValidTraceId(String candidate) {
        return candidate != null && VALID_TRACE_ID.matcher(candidate).matches();
    }

    /** Trace identifier bound to the current thread, if any. */
    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return isValidTraceId(traceId) ? traceId : null;
    }
}

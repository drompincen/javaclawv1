package io.github.drompincen.javaclawv1.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Blocks direct POST to domain entity endpoints when NOT in testMode.
 * In live mode, all entity creation must flow through the intake pipeline.
 * In testMode ({@code -Djavaclaw.llm.provider=test}), all endpoints are open
 * so scenario tests can seed data directly.
 */
public class TestModeGuardInterceptor implements HandlerInterceptor {

    private static final List<Pattern> GUARDED_PATTERNS = List.of(
            Pattern.compile("^/api/projects/[^/]+/threads/?$"),
            Pattern.compile("^/api/projects/[^/]+/tickets/?$"),
            Pattern.compile("^/api/projects/[^/]+/objectives/?$"),
            Pattern.compile("^/api/resources/?$"),
            Pattern.compile("^/api/projects/[^/]+/blindspots/?$"),
            Pattern.compile("^/api/projects/[^/]+/phases/?$"),
            Pattern.compile("^/api/projects/[^/]+/milestones/?$"),
            Pattern.compile("^/api/projects/[^/]+/checklists/?$")
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if ("test".equals(System.getProperty("javaclaw.llm.provider"))) {
            return true;
        }

        String uri = request.getRequestURI();
        for (Pattern pattern : GUARDED_PATTERNS) {
            if (pattern.matcher(uri).matches()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"Direct entity creation blocked outside testMode. Use POST /api/intake/pipeline.\",\"path\":\"" + uri + "\"}");
                return false;
            }
        }

        return true;
    }
}

package com.collabdocs.api.web;

import com.collabdocs.api.auth.JwtService;
import com.collabdocs.api.auth.SessionDenylist;
import com.collabdocs.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(10)
public class ApiGuardFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset",
            "/api/v1/auth/verify-email");
    private final JwtService jwt;
    private final SessionDenylist denylist;
    private final ObjectMapper mapper;

    public ApiGuardFilter(JwtService jwt, SessionDenylist denylist, ObjectMapper mapper) {
        this.jwt = jwt;
        this.denylist = denylist;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/health") || "/metrics".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!"1".equals(request.getHeader("X-CollabDocs-Request"))) {
                throw new ApiException(400, "MISSING_CLIENT_HEADER", "Missing client header.");
            }
            String path = request.getRequestURI();
            if (PUBLIC.contains(path)) {
                chain.doFilter(request, response);
                return;
            }
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
            }
            JwtService.AccessClaims claims = jwt.parse(header.substring(7));
            denylist.check(claims.sessionId());
            request.setAttribute("currentUser", claims);
            chain.doFilter(request, response);
        } catch (ApiException exception) {
            write(response, exception);
        }
    }

    private void write(HttpServletResponse response, ApiException exception) throws IOException {
        response.setStatus(exception.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String correlation = MDC.get("correlationId");
        mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
                "code", exception.code(),
                "message", exception.getMessage(),
                "details", exception.details(),
                "correlationId", correlation == null ? "" : correlation)));
    }

    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

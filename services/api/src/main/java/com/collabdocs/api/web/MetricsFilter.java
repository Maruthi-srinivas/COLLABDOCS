package com.collabdocs.api.web;

import com.collabdocs.common.AppMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(5)
public class MetricsFilter extends OncePerRequestFilter {
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String route = UUID.matcher(request.getRequestURI()).replaceAll("{id}");
            AppMetrics.INSTANCE.observe("http_server_requests_seconds", (System.nanoTime() - started) / 1_000_000_000d,
                    "method", request.getMethod(), "route", route, "status", Integer.toString(response.getStatus()));
        }
    }
}

package com.onsemi.cim.apps.exensio.exensioreload.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final AntPathRequestMatcher publicAuthMatcher = new AntPathRequestMatcher("/api/auth/**");
    private final AntPathRequestMatcher authMeMatcher = new AntPathRequestMatcher("/api/auth/me");

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (publicAuthMatcher.matches(request) && !authMeMatcher.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = null;

        // First, try to get token from Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        // For SSE endpoints, also check query parameter (EventSource can't send custom headers)
        if (token == null && request.getRequestURI().contains("/monitor")) {
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isEmpty()) {
                token = queryToken;
            }
        }

        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.extractUsername(token);
            if (username != null && !username.isBlank()) {
                List<SimpleGrantedAuthority> authorities = jwtUtil.extractRoles(token)
                        .stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(username, token, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        // Invalid/expired Bearer tokens are ignored (request continues unauthenticated → 401 JSON).
        filterChain.doFilter(request, response);
    }
}

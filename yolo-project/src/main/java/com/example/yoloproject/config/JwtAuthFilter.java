package com.example.yoloproject.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/health",
            "/api/dialog/folder",
            "/ws/logs/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        for (String publicPath : PUBLIC_PATHS) {
            if (pathMatcher.match(publicPath, path)) {
                chain.doFilter(request, response);
                return;
            }
        }

        String authHeader = httpRequest.getHeader("Authorization");
        String token = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            String queryToken = httpRequest.getParameter("token");
            if (queryToken != null && !queryToken.isEmpty()) {
                token = queryToken;
            }
        }

        if (token != null && jwtUtil.isTokenValid(token)) {
            httpRequest.setAttribute("userId", jwtUtil.getUserId(token));
            httpRequest.setAttribute("username", jwtUtil.getUsername(token));
            httpRequest.setAttribute("role", jwtUtil.getRole(token));
            chain.doFilter(request, response);
            return;
        }

        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.getWriter().write("{\"message\":\"未登录或登录已过期\",\"status\":\"unauthorized\"}");
    }
}

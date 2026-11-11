
package com.ihbas.backend.security;

import com.ihbas.backend.user.UserAccount;
import com.ihbas.backend.user.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    // Skip public login and CORS preflight requests.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {

        String path = request.getServletPath();

        return HttpMethod.OPTIONS.matches(request.getMethod())
                || "/api/auth/login".equals(path)
                || "/api/health".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        try {

            String token = header.substring(7);

            Claims claims = jwt.parse(token);

            Number uid = claims.get("uid", Number.class);

            if (uid == null) {
                chain.doFilter(request, response);
                return;
            }

            UserAccount user = users.findById(uid.longValue())
                    .orElse(null);

            boolean accountActive = user != null
                    && "ACTIVE".equals(user.accountStatus());

            boolean accountUnlocked = user != null
                    && (
                        user.lockedUntil() == null
                        || !user.lockedUntil().isAfter(LocalDateTime.now())
                    );

            boolean validRole = user != null
                    && List.of("ADMIN", "RECEPTIONIST", "PATIENT")
                    .contains(user.role());

            if (accountActive && accountUnlocked && validRole) {

                var authentication =
                        new UsernamePasswordAuthenticationToken(
                                user.username(),
                                null,
                                List.of(
                                        new SimpleGrantedAuthority(
                                                "ROLE_" + user.role()
                                        )
                                )
                        );

                authentication.setDetails(
                        new WebAuthenticationDetailsSource()
                                .buildDetails(request)
                );

                SecurityContextHolder.getContext()
                        .setAuthentication(authentication);
            }

        } catch (JwtException | IllegalArgumentException ex) {

            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}

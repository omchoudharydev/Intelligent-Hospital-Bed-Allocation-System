package com.ihbas.backend.security;

import com.ihbas.backend.user.UserAccount;
import com.ihbas.backend.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    public JwtAuthenticationFilter(JwtService jwt, UserRepository users) { this.jwt = jwt; this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwt.parse(header.substring(7));
                Number uid = claims.get("uid", Number.class);
                if (uid != null) {
                    UserAccount user = users.findById(uid.longValue()).orElse(null);
                    boolean lockExpiredOrAbsent = user != null && (user.lockedUntil() == null || !user.lockedUntil().isAfter(LocalDateTime.now()));
                    if (user != null && "ACTIVE".equals(user.accountStatus()) && lockExpiredOrAbsent
                            && List.of("ADMIN", "RECEPTIONIST", "PATIENT").contains(user.role())) {
                        var auth = new UsernamePasswordAuthenticationToken(user.username(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}

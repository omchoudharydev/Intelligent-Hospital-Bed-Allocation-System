
package com.ihbas.backend.config;

import com.ihbas.backend.security.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    // ==================================================
    // PASSWORD ENCODER
    // ==================================================

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // ==================================================
    // CORS CONFIGURATION
    // ==================================================

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080,http://localhost:5500,https://intelligent-hospital-bed-allocation-system-production.up.railway.app}")
            String origins) {

        CorsConfiguration config = new CorsConfiguration();

        List<String> allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();

        config.setAllowedOrigins(allowedOrigins);

        config.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Razorpay-Signature",
                "x-razorpay-event-id"
        ));

        config.setExposedHeaders(List.of(
                "Content-Disposition"
        ));

        // JWT is sent through Authorization header.
        config.setAllowCredentials(false);

        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }

    // ==================================================
    // DISABLE AUTOMATIC JWT FILTER REGISTRATION
    // JWT FILTER RUNS ONLY INSIDE SPRING SECURITY CHAIN
    // ==================================================

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter>
    jwtFilterRegistration(JwtAuthenticationFilter filter) {

        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);

        return registration;
    }

    // ==================================================
    // SPRING SECURITY FILTER CHAIN
    // ==================================================

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter) throws Exception {

        return http

                // CSRF disabled because this application uses stateless JWT authentication.
                .csrf(csrf -> csrf.disable())

                // Enable CORS configuration.
                .cors(Customizer.withDefaults())

                // Stateless authentication.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // Authentication and authorization error handling.
                .exceptionHandling(exception -> exception

                        .authenticationEntryPoint((request, response, authException) -> {

                            response.setStatus(
                                    HttpServletResponse.SC_UNAUTHORIZED
                            );

                            response.setCharacterEncoding("UTF-8");
                            response.setContentType("application/json");

                            response.getWriter().write(
                                    "{\"status\":401,\"message\":\"Authentication required. Please sign in again.\"}"
                            );
                        })

                        .accessDeniedHandler((request, response, accessDeniedException) -> {

                            response.setStatus(
                                    HttpServletResponse.SC_FORBIDDEN
                            );

                            response.setCharacterEncoding("UTF-8");
                            response.setContentType("application/json");

                            response.getWriter().write(
                                    "{\"status\":403,\"message\":\"Your account does not have permission to perform this action.\"}"
                            );
                        })
                )

                // Endpoint authorization.
                .authorizeHttpRequests(auth -> auth

                        // Public static resources.
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/favicon.ico",
                                "/error"
                        ).permitAll()

                        // Public API endpoints.
                        .requestMatchers(
                                "/api/health",
                                "/api/auth/login",
                                "/api/auth/password/**",
                                "/api/webhooks/razorpay"
                        ).permitAll()

                        // Allow CORS preflight requests.
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**"
                        ).permitAll()

                        // Bed board access.
                        .requestMatchers("/api/bed-board")
                        .hasAnyAuthority(
                                "ROLE_ADMIN",
                                "ROLE_RECEPTIONIST"
                        )

                        // Admin-only APIs.
                        .requestMatchers(
                                "/api/admin/**",
                                "/api/dashboard/**"
                        ).hasAuthority("ROLE_ADMIN")

                        // Receptionist APIs.
                        .requestMatchers("/api/receptionist/**")
                        .hasAnyAuthority(
                                "ROLE_ADMIN",
                                "ROLE_RECEPTIONIST"
                        )

                        // Patient APIs.
                        .requestMatchers("/api/patient/**")
                        .hasAuthority("ROLE_PATIENT")

                        // All remaining endpoints require authentication.
                        .anyRequest().authenticated()
                )

                // Disable HTTP Basic, form login and default logout.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())

                // Add JWT authentication filter.
                .addFilterBefore(
                        jwtFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                .build();
    }
}

package com.ihbas.backend.auth;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
    private AuthDtos() {}
    public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}
    public record UserView(long userId, String username, String email, String fullName, String role) {}
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}
    public record MessageResponse(String message) {}
}

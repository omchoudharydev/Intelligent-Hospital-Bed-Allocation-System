package com.ihbas.backend.user;

import java.time.LocalDateTime;

public record UserAccount(long userId, String username, String email, String fullName,
                          String passwordHash, String role, String accountStatus,
                          int failedLoginAttempts, LocalDateTime lockedUntil) {}

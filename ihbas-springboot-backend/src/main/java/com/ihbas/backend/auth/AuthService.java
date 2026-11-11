package com.ihbas.backend.auth;

import com.ihbas.backend.security.JwtService;
import com.ihbas.backend.user.UserAccount;
import com.ihbas.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;

@Service
public class AuthService {
    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users; this.encoder = encoder; this.jwt = jwt;
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        String identifier = request.identifier().trim();
        UserAccount user = users.findByIdentifier(identifier)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Patient User ID, username, email or password."));

        if (!"ACTIVE".equals(user.accountStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not active. Contact the administrator.");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean expiredLock = user.lockedUntil() != null && !user.lockedUntil().isAfter(now);
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Account temporarily locked. Try again after " + user.lockedUntil() + ".");
        }
        int currentAttempts = user.failedLoginAttempts();
        if (expiredLock) {
            users.clearExpiredLock(user.userId());
            currentAttempts = 0;
        }

        if (!encoder.matches(request.password(), user.passwordHash())) {
            int attempts = currentAttempts + 1;
            LocalDateTime lockUntil = attempts >= MAX_ATTEMPTS ? now.plusMinutes(LOCK_MINUTES) : null;
            users.recordFailure(user.userId(), attempts, lockUntil);
            if (lockUntil != null) {
                throw new ResponseStatusException(HttpStatus.LOCKED, "Five failed attempts. Account locked for 15 minutes.");
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Patient User ID, username, email or password.");
        }

        users.recordSuccess(user.userId());
        String token = jwt.createToken(user);
        return new AuthDtos.LoginResponse(token, "Bearer", jwt.expiresInSeconds(),
            new AuthDtos.UserView(user.userId(), user.username(), user.email(), user.fullName(), user.role()));
    }
}

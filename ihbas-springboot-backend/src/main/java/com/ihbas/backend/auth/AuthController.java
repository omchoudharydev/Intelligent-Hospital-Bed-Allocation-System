package com.ihbas.backend.auth;

import com.ihbas.backend.user.UserAccount;
import com.ihbas.backend.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final UserRepository users;

    public AuthController(AuthService authService, UserRepository users) {
        this.authService = authService; this.users = users;
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        UserAccount user = users.findByIdentifier(authentication.getName()).orElse(null);
        if (user == null || !"ACTIVE".equals(user.accountStatus())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new AuthDtos.UserView(user.userId(), user.username(), user.email(), user.fullName(), user.role()));
    }

    @PostMapping("/logout")
    public AuthDtos.MessageResponse logout() {
        return new AuthDtos.MessageResponse("Logged out. Discard the access token on the client.");
    }
}

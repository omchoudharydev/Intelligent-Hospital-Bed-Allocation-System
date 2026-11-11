package com.ihbas.backend.security;

import com.ihbas.backend.user.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:60}") long expirationMinutes) {
        byte[] decoded = Decoders.BASE64.decode(secret);
        if (decoded.length < 32) throw new IllegalArgumentException("JWT secret must contain at least 32 decoded bytes.");
        this.key = Keys.hmacShaKeyFor(decoded);
        this.expirationMinutes = expirationMinutes;
    }

    public String createToken(UserAccount user) {
        Instant now = Instant.now();
        return Jwts.builder().subject(user.username())
            .claim("uid", user.userId()).claim("role", user.role())
            .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expirationMinutes * 60)))
            .signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public long expiresInSeconds() { return expirationMinutes * 60; }
}

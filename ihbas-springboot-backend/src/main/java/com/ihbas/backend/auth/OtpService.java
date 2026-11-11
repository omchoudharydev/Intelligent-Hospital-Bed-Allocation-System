package com.ihbas.backend.auth;

import com.ihbas.backend.gateway.Msg91SmsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Statement;
import java.util.Map;

@Service
public class OtpService {
    public static final String REGISTRATION = "PATIENT_REGISTRATION";
    public static final String RESET = "PASSWORD_RESET";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();
    private final boolean exposeCode;
    private final int expiryMinutes;
    private final Msg91SmsService sms;

    public OtpService(JdbcTemplate jdbc, PasswordEncoder encoder,
                      @Value("${app.otp.dev-expose-code:false}") boolean exposeCode,
                      @Value("${app.otp.expiry-minutes:5}") int expiryMinutes,
                      Msg91SmsService sms) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.exposeCode = exposeCode;
        this.expiryMinutes = expiryMinutes;
        this.sms = sms;
    }

    public Map<String, Object> issue(String phone, String purpose, String ip) {
        final String normalizedPhone = sms.normalizeMobile(phone);
        Long recent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM otp_verifications WHERE phone=? AND purpose=? AND created_at >= UTC_TIMESTAMP()-INTERVAL 15 MINUTE",
                Long.class, normalizedPhone, purpose);
        if (recent != null && recent >= 3) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many OTP requests. Try again after 15 minutes.");
        }
        Long justSent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM otp_verifications WHERE phone=? AND purpose=? AND created_at >= UTC_TIMESTAMP()-INTERVAL 60 SECOND",
                Long.class, normalizedPhone, purpose);
        if (justSent != null && justSent > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Wait at least 60 seconds before requesting another OTP.");
        }

        String digits = String.format("%06d", random.nextInt(1_000_000));
        final String otpHash = encoder.encode(digits);
        jdbc.update("UPDATE otp_verifications SET consumed_at=UTC_TIMESTAMP() WHERE phone=? AND purpose=? AND consumed_at IS NULL",
                normalizedPhone, purpose);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO otp_verifications(phone,purpose,otp_hash,expires_at,attempts,max_attempts,delivery_status,sent_at,created_at,request_ip) VALUES(?,?,?,UTC_TIMESTAMP()+INTERVAL ? MINUTE,0,5,'PENDING',NULL,UTC_TIMESTAMP(),?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, normalizedPhone);
            statement.setString(2, purpose);
            statement.setString(3, otpHash);
            statement.setInt(4, expiryMinutes);
            statement.setString(5, ip);
            return statement;
        }, keyHolder);

        if (keyHolder.getKey() == null) {
            throw new IllegalStateException("OTP record could not be created.");
        }
        long otpId = keyHolder.getKey().longValue();

        if (exposeCode && !sms.isEnabled()) {
            jdbc.update("UPDATE otp_verifications SET delivery_status='SENT',sent_at=UTC_TIMESTAMP() WHERE otp_id=?", otpId);
            return Map.of("message", "OTP generated in explicit local demo mode.",
                    "expiresInMinutes", expiryMinutes, "demoOtp", digits);
        }

        try {
            String providerId = sms.sendOtp(normalizedPhone, digits);
            jdbc.update("UPDATE otp_verifications SET delivery_status='SENT',provider_message_id=?,sent_at=UTC_TIMESTAMP() WHERE otp_id=?",
                    providerId, otpId);
        } catch (RuntimeException ex) {
            jdbc.update("UPDATE otp_verifications SET delivery_status='FAILED',consumed_at=UTC_TIMESTAMP() WHERE otp_id=?", otpId);
            throw ex;
        }
        return Map.of("message", "OTP sent to the registered mobile number.", "expiresInMinutes", expiryMinutes);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ResponseStatusException.class)
    public void verify(String phone, String purpose, String code) {
        final String normalizedPhone = sms.normalizeMobile(phone);
        if (code == null || !code.matches("^[0-9]{6}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the six-digit OTP.");
        }
        var rows = jdbc.queryForList(
                "SELECT otp_id,otp_hash,attempts,max_attempts FROM otp_verifications WHERE phone=? AND purpose=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP() AND delivery_status='SENT' ORDER BY created_at DESC LIMIT 1 FOR UPDATE",
                normalizedPhone, purpose);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTP not found, expired, or already used.");
        }

        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("otp_id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        int max = ((Number) row.get("max_attempts")).intValue();
        if (attempts >= max) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Maximum OTP verification attempts reached.");
        }

        int updated = jdbc.update(
                "UPDATE otp_verifications SET attempts=attempts+1 WHERE otp_id=? AND attempts<max_attempts AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP()",
                id);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "OTP expired or maximum verification attempts reached.");
        }
        if (!encoder.matches(code, String.valueOf(row.get("otp_hash")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OTP.");
        }
        jdbc.update("UPDATE otp_verifications SET consumed_at=UTC_TIMESTAMP() WHERE otp_id=? AND consumed_at IS NULL", id);
    }
}

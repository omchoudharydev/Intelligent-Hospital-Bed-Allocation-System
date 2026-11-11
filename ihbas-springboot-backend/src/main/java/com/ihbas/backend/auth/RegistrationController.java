package com.ihbas.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class RegistrationController {
    private final JdbcTemplate jdbc;private final PasswordEncoder encoder;private final OtpService otp;
    public RegistrationController(JdbcTemplate jdbc,PasswordEncoder encoder,OtpService otp){this.jdbc=jdbc;this.encoder=encoder;this.otp=otp;}

    @PostMapping("/password/otp/request")
    public Map<String,Object> requestReset(@RequestBody Map<String,Object>b,HttpServletRequest request){
        String phone=required(b,"phone");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone=? AND account_status='ACTIVE'",Long.class,phone);
        if(count==null||count==0)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"No active account found for this phone.");
        return otp.issue(phone,OtpService.RESET,request.getRemoteAddr());
    }

    @PostMapping("/password/reset")
    @Transactional
    public Map<String,Object> resetPassword(@RequestBody Map<String,Object>b){
        String phone=required(b,"phone"),code=required(b,"otp"),password=required(b,"newPassword");
        if(password.length()<10)throw new IllegalArgumentException("Password must be at least 10 characters.");
        otp.verify(phone,OtpService.RESET,code);
        jdbc.update("UPDATE users SET password_hash=?,password_changed_at=UTC_TIMESTAMP(),failed_login_attempts=0,locked_until=NULL WHERE phone=? AND account_status='ACTIVE'",encoder.encode(password),phone);
        return Map.of("message","Password reset successfully. Log in again.");
    }
    private static String required(Map<String,Object>b,String k){String s=text(b,k);if(s==null||s.isBlank())throw new IllegalArgumentException(k+" is required.");return s.trim();}
    private static String text(Map<String,Object>b,String k){Object o=b.get(k);return o==null?null:String.valueOf(o);}
    private static String value(Map<String,Object>b,String k,String d){String s=text(b,k);return s==null||s.isBlank()?d:s;}
}

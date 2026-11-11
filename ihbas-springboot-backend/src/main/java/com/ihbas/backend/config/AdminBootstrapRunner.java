package com.ihbas.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.bootstrap-admin.enabled", havingValue="true")
public class AdminBootstrapRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private final JdbcTemplate jdbc;private final PasswordEncoder encoder;
    public AdminBootstrapRunner(JdbcTemplate jdbc,PasswordEncoder encoder){this.jdbc=jdbc;this.encoder=encoder;}
    @Override public void run(ApplicationArguments args){
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN'",Long.class);
        if(count!=null&&count>0){log.warn("Admin bootstrap skipped: an ADMIN account already exists.");return;}
        String password=System.getenv("IHBAS_ADMIN_PASSWORD");
        if(password==null||password.length()<12)throw new IllegalStateException("Set IHBAS_ADMIN_PASSWORD (at least 12 characters) before enabling admin bootstrap.");
        String username=env("IHBAS_ADMIN_USERNAME","admin");
        String fullName=env("IHBAS_ADMIN_FULL_NAME","IHBAS Administrator");
        String email=System.getenv("IHBAS_ADMIN_EMAIL");
        jdbc.update("INSERT INTO users(username,email,full_name,password_hash,role,account_status,password_changed_at) VALUES(?,?,?,?,'ADMIN','ACTIVE',UTC_TIMESTAMP())",username,email,fullName,encoder.encode(password));
        log.info("Initial ADMIN account created. Username: {}. Remove bootstrap environment variables and disable app.bootstrap-admin.enabled immediately.",username);
    }
    private static String env(String name,String fallback){String v=System.getenv(name);return v==null||v.isBlank()?fallback:v;}
}

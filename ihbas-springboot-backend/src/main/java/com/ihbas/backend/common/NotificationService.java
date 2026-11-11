package com.ihbas.backend.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    public NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void create(Long userId, String title, String message, String type) {
        if (userId == null) return;
        jdbc.update("INSERT INTO notifications(user_id,title,message,notification_type,is_read,created_at) VALUES(?,?,?,?,FALSE,UTC_TIMESTAMP())",
                userId, title, message, type);
    }
    public void createForRole(String role, String title, String message, String type) {
        jdbc.update("INSERT INTO notifications(user_id,title,message,notification_type,is_read,created_at) " +
                "SELECT user_id,?,?,?,FALSE,UTC_TIMESTAMP() FROM users WHERE role=? AND account_status='ACTIVE'",
                title, message, type, role);
    }
}

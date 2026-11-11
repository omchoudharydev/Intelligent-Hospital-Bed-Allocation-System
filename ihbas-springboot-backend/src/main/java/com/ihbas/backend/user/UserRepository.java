package com.ihbas.backend.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {
    private static final String COLUMNS = "user_id, username, email, full_name, password_hash, role, account_status, failed_login_attempts, locked_until";
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UserAccount> findByIdentifier(String identifier) {
        String sql = "SELECT " + COLUMNS + " FROM users WHERE username = ? OR email = ? LIMIT 1";
        List<UserAccount> rows = jdbc.query(sql, (rs, n) -> {
            Timestamp locked = rs.getTimestamp("locked_until");
            return new UserAccount(rs.getLong("user_id"), rs.getString("username"),
                rs.getString("email"), rs.getString("full_name"), rs.getString("password_hash"),
                rs.getString("role"), rs.getString("account_status"), rs.getInt("failed_login_attempts"),
                locked == null ? null : locked.toLocalDateTime());
        }, identifier, identifier);
        Optional<UserAccount> match = rows.stream().findFirst();
        if (match.isPresent() || !identifier.matches("\\d+")) return match;
        try {
            return findById(Long.parseLong(identifier));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public Optional<UserAccount> findById(long id) {
        String sql = "SELECT " + COLUMNS + " FROM users WHERE user_id = ?";
        List<UserAccount> rows = jdbc.query(sql, (rs, n) -> {
            Timestamp locked = rs.getTimestamp("locked_until");
            return new UserAccount(rs.getLong("user_id"), rs.getString("username"),
                rs.getString("email"), rs.getString("full_name"), rs.getString("password_hash"),
                rs.getString("role"), rs.getString("account_status"), rs.getInt("failed_login_attempts"),
                locked == null ? null : locked.toLocalDateTime());
        }, id);
        return rows.stream().findFirst();
    }

    public void recordFailure(long id, int attempts, java.time.LocalDateTime until) {
        jdbc.update("UPDATE users SET failed_login_attempts=?, locked_until=? WHERE user_id=?",
            attempts, until == null ? null : Timestamp.valueOf(until), id);
    }

    public void clearExpiredLock(long id) {
        jdbc.update("UPDATE users SET failed_login_attempts=0, locked_until=NULL WHERE user_id=?", id);
    }

    public void recordSuccess(long id) {
        jdbc.update("UPDATE users SET failed_login_attempts=0, locked_until=NULL, last_login_at=CURRENT_TIMESTAMP WHERE user_id=?", id);
    }
}

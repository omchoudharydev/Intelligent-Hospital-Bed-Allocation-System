package com.ihbas.backend.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public void record(Long userId, String action, String entity, Object entityId, Map<String, ?> details, HttpServletRequest request) {
        String json = null;
        try { if (details != null) json = mapper.writeValueAsString(details); }
        catch (JsonProcessingException ignored) { json = "{}"; }
        jdbc.update("INSERT INTO audit_log(user_id, action, entity_type, entity_id, details, ip_address, user_agent, event_time) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP())",
                userId, action, entity, entityId == null ? null : String.valueOf(entityId), json,
                request == null ? null : request.getRemoteAddr(), request == null ? null : request.getHeader("User-Agent"));
    }
}

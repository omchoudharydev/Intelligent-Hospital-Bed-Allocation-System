package com.ihbas.backend.health;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("application", "IHBAS Backend");

        try {
            String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
            Integer bedCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM beds", Integer.class);
            Integer patientCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM patients", Integer.class);

            response.put("status", "UP");
            response.put("database", database);
            response.put("databaseStatus", "CONNECTED");
            response.put("beds", bedCount);
            response.put("patients", patientCount);
            return ResponseEntity.ok(response);
        } catch (DataAccessException exception) {
            response.put("status", "DOWN");
            response.put("databaseStatus", "DISCONNECTED");
            response.put("message", "Database connection or required IHBAS tables are unavailable.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }
}

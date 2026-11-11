package com.ihbas.backend.hospital;

import com.ihbas.backend.common.RequestUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class SharedController {
    private final JdbcTemplate jdbc;
    public SharedController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/wards")
    public List<Map<String,Object>> wards(){return jdbc.queryForList("SELECT ward_id,ward_name,ward_type,floor_label,gender_policy,capacity,status FROM wards WHERE status='ACTIVE' ORDER BY ward_name");}

    @GetMapping("/beds/availability")
    public List<Map<String,Object>> availability(@RequestParam(required=false) String wardType,
                                                  @RequestParam(required=false) Boolean oxygen,
                                                  @RequestParam(required=false) Boolean ventilator,
                                                  @RequestParam(required=false) Boolean monitor){
        return jdbc.queryForList("SELECT b.bed_id,b.bed_code,b.category,b.floor_label,b.daily_charge,b.has_oxygen,b.has_ventilator,b.has_monitor,b.isolation_capable,w.ward_name,w.ward_type,w.gender_policy FROM beds b JOIN wards w ON w.ward_id=b.ward_id WHERE b.status='AVAILABLE' AND w.status='ACTIVE' AND (? IS NULL OR w.ward_type=?) AND (? IS NULL OR b.has_oxygen=?) AND (? IS NULL OR b.has_ventilator=?) AND (? IS NULL OR b.has_monitor=?) ORDER BY w.ward_name,b.bed_code",wardType,wardType,oxygen,oxygen,ventilator,ventilator,monitor,monitor);
    }


    @GetMapping("/bed-board")
    public List<Map<String,Object>> bedBoard(@RequestParam(required=false) String status) {
        return jdbc.queryForList("SELECT b.bed_id,b.bed_code,b.ward_id,w.ward_name,w.ward_type,b.category,b.floor_label,b.daily_charge,b.has_oxygen,b.has_ventilator,b.has_monitor,b.attached_bathroom,b.isolation_capable,b.status,aa.patient_name,aa.uhid,aa.admit_time FROM beds b JOIN wards w ON w.ward_id=b.ward_id LEFT JOIN v_active_allocations aa ON aa.bed_id=b.bed_id WHERE (? IS NULL OR b.status=?) ORDER BY w.ward_name,b.bed_code",status,status);
    }

    @GetMapping("/doctors")
    public List<Map<String,Object>> doctors(){return jdbc.queryForList("SELECT d.doctor_id,d.doctor_code,d.full_name,d.phone,d.email,dep.name department FROM doctors d LEFT JOIN departments dep ON dep.department_id=d.department_id WHERE d.active=TRUE ORDER BY d.full_name");}

    @GetMapping("/notifications/unread-count")
    public Map<String,Object> unreadCount(){
        Long uid=jdbc.queryForObject("SELECT user_id FROM users WHERE username=?",Long.class,RequestUser.username());
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=FALSE",Long.class,uid);
        return Map.of("count",count==null?0:count);
    }
}

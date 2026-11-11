package com.ihbas.backend.admin;

import com.ihbas.backend.common.AuditService;
import com.ihbas.backend.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping({"/api/admin", "/api/dashboard"})
public class AdminController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AdminController(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc; this.encoder = encoder; this.audit = audit;
    }

    @GetMapping({"/stats", "/dashboard", "/admin"})
    public Map<String,Object> stats() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("totalBeds", count("SELECT COUNT(*) FROM beds"));
        m.put("availableBeds", count("SELECT COUNT(*) FROM beds WHERE status='AVAILABLE'"));
        m.put("occupiedBeds", count("SELECT COUNT(*) FROM beds WHERE status='OCCUPIED'"));
        m.put("reservedBeds", count("SELECT COUNT(*) FROM beds WHERE status='RESERVED'"));
        m.put("cleaningBeds", count("SELECT COUNT(*) FROM beds WHERE status='CLEANING'"));
        m.put("maintenanceBeds", count("SELECT COUNT(*) FROM beds WHERE status IN ('MAINTENANCE','OUT_OF_SERVICE')"));
        m.put("totalPatients", count("SELECT COUNT(*) FROM patients WHERE record_status='ACTIVE'"));
        m.put("activeAdmissions", count("SELECT COUNT(*) FROM allocations WHERE status='ACTIVE'"));
        m.put("pendingRequests", count("SELECT COUNT(*) FROM admission_requests WHERE status IN ('PENDING_VERIFICATION','VERIFIED','WAITING')"));
        m.put("todayAdmissions", count("SELECT COUNT(*) FROM allocations WHERE DATE(admit_time)=UTC_DATE()"));
        m.put("todayDischarges", count("SELECT COUNT(*) FROM allocations WHERE discharge_time IS NOT NULL AND DATE(discharge_time)=UTC_DATE()"));
        m.put("icuFreeBeds", count("SELECT COUNT(*) FROM beds b JOIN wards w ON w.ward_id=b.ward_id WHERE UPPER(w.ward_type)='ICU' AND b.status='AVAILABLE'"));
        m.put("occupancyRate", number("SELECT IFNULL(ROUND(100*SUM(status='OCCUPIED')/NULLIF(COUNT(*),0),1),0) FROM beds"));
        m.put("wards", jdbc.queryForList("SELECT w.ward_id,w.ward_name,w.ward_type,w.capacity,COUNT(b.bed_id) total_beds,COALESCE(SUM(b.status='AVAILABLE'),0) available_beds,COALESCE(SUM(b.status='OCCUPIED'),0) occupied_beds,COALESCE(SUM(b.status='RESERVED'),0) reserved_beds,COALESCE(SUM(b.status='CLEANING'),0) cleaning_beds FROM wards w LEFT JOIN beds b ON b.ward_id=w.ward_id GROUP BY w.ward_id,w.ward_name,w.ward_type,w.capacity ORDER BY w.ward_name"));
        m.put("bedStatus", jdbc.queryForList("SELECT status,COUNT(*) count FROM beds GROUP BY status ORDER BY status"));
        m.put("admissionTrend", jdbc.queryForList("SELECT DATE(admit_time) day,COUNT(*) admissions FROM allocations WHERE admit_time >= UTC_DATE()-INTERVAL 6 DAY GROUP BY DATE(admit_time) ORDER BY day"));
        m.put("alerts", alerts());
        return m;
    }

    @GetMapping("/wards")
    public List<Map<String,Object>> wards() {
        return jdbc.queryForList("SELECT w.ward_id,w.ward_name,w.ward_type,w.floor_label,w.gender_policy,w.capacity,w.status,COUNT(b.bed_id) total_beds,SUM(b.status='AVAILABLE') available_beds,SUM(b.status='OCCUPIED') occupied_beds FROM wards w LEFT JOIN beds b ON b.ward_id=w.ward_id GROUP BY w.ward_id,w.ward_name,w.ward_type,w.floor_label,w.gender_policy,w.capacity,w.status ORDER BY w.ward_name");
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/wards")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createWard(@RequestBody Map<String,Object> b, HttpServletRequest req) {
        String name = required(b,"wardName"), type = required(b,"wardType");
        jdbc.update("INSERT INTO wards(ward_name,ward_type,floor_label,gender_policy,capacity,status) VALUES(?,?,?,?,?,'ACTIVE')",
                name, type.toUpperCase(Locale.ROOT), text(b,"floorLabel"), value(b,"genderPolicy","ANY"), integer(b,"capacity",0));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit.record(actorId(),"WARD_CREATED","wards",id,Map.of("wardName",name),req);
        return Map.of("wardId",id,"message","Ward created successfully.");
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/wards/{id}")
    public Map<String,Object> updateWard(@PathVariable long id, @RequestBody Map<String,Object> b, HttpServletRequest req) {
        jdbc.update("UPDATE wards SET ward_name=?,ward_type=?,floor_label=?,gender_policy=?,capacity=?,status=? WHERE ward_id=?",
                required(b,"wardName"),required(b,"wardType").toUpperCase(Locale.ROOT),text(b,"floorLabel"),value(b,"genderPolicy","ANY"),integer(b,"capacity",0),value(b,"status","ACTIVE"),id);
        audit.record(actorId(),"WARD_UPDATED","wards",id,Map.of(),req);
        return Map.of("message","Ward updated successfully.");
    }

    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/wards/{id}")
    public Map<String,Object> deactivateWard(@PathVariable long id, HttpServletRequest req) {
        int changed = jdbc.update("UPDATE wards SET status='INACTIVE' WHERE ward_id=? AND NOT EXISTS (SELECT 1 FROM beds WHERE ward_id=? AND status IN ('OCCUPIED','RESERVED'))",id,id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Ward not found or still has occupied/reserved beds.");
        audit.record(actorId(),"WARD_DEACTIVATED","wards",id,Map.of(),req);
        return Map.of("message","Ward deactivated.");
    }

    @GetMapping("/beds")
    public List<Map<String,Object>> beds(@RequestParam(required=false) String status,
                                         @RequestParam(required=false) Long wardId,
                                         @RequestParam(required=false) String q) {
        String sql = "SELECT b.bed_id,b.bed_code,b.ward_id,w.ward_name,w.ward_type,b.category,b.floor_label,b.daily_charge,b.has_oxygen,b.has_ventilator,b.has_monitor,b.attached_bathroom,b.isolation_capable,b.status,b.notes FROM beds b JOIN wards w ON w.ward_id=b.ward_id WHERE (? IS NULL OR b.status=?) AND (? IS NULL OR b.ward_id=?) AND (? IS NULL OR b.bed_code LIKE CONCAT('%',?,'%') OR w.ward_name LIKE CONCAT('%',?,'%')) ORDER BY w.ward_name,b.bed_code";
        return jdbc.queryForList(sql,status,status,wardId,wardId,q,q,q);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/beds")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createBed(@RequestBody Map<String,Object> b, HttpServletRequest req) {
        String bedCode = required(b,"bedCode");
        long wardId = longValue(b,"wardId");
        String category = required(b,"category").toUpperCase(Locale.ROOT);
        BigDecimal dailyCharge = decimal(b,"dailyCharge",BigDecimal.ZERO);
        String initialStatus = value(b,"status","AVAILABLE").toUpperCase(Locale.ROOT);
        ensureActiveWard(wardId);
        if (dailyCharge.signum() < 0) throw new IllegalArgumentException("Daily charge cannot be negative.");
        if (!Set.of("AVAILABLE","MAINTENANCE","OUT_OF_SERVICE").contains(initialStatus))
            throw new IllegalArgumentException("A new bed can start only as AVAILABLE, MAINTENANCE or OUT_OF_SERVICE.");
        jdbc.update("INSERT INTO beds(bed_code,ward_id,category,floor_label,daily_charge,has_oxygen,has_ventilator,has_monitor,attached_bathroom,isolation_capable,status,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                bedCode,wardId,category,text(b,"floorLabel"),dailyCharge,bool(b,"hasOxygen"),bool(b,"hasVentilator"),bool(b,"hasMonitor"),bool(b,"attachedBathroom"),bool(b,"isolationCapable"),initialStatus,text(b,"notes"));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        audit.record(actorId(),"BED_CREATED","beds",id,Map.of("bedCode",bedCode,"wardId",wardId),req);
        return Map.of("bedId",id,"message","Bed added successfully.");
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/beds/{id}")
    public Map<String,Object> updateBed(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
        List<Map<String,Object>> existing = jdbc.queryForList("SELECT status FROM beds WHERE bed_id=? FOR UPDATE",id);
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Bed not found.");
        String current = String.valueOf(existing.get(0).get("status"));
        if (Set.of("OCCUPIED","RESERVED").contains(current))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Occupied or reserved beds cannot be edited.");
        long wardId = longValue(b,"wardId");
        BigDecimal dailyCharge = decimal(b,"dailyCharge",BigDecimal.ZERO);
        if (dailyCharge.signum() < 0) throw new IllegalArgumentException("Daily charge cannot be negative.");
        ensureActiveWard(wardId);
        String bedCode = required(b,"bedCode"), category = required(b,"category").toUpperCase(Locale.ROOT);
        jdbc.update("UPDATE beds SET bed_code=?,ward_id=?,category=?,floor_label=?,daily_charge=?,has_oxygen=?,has_ventilator=?,has_monitor=?,attached_bathroom=?,isolation_capable=?,notes=? WHERE bed_id=?",
                bedCode,wardId,category,text(b,"floorLabel"),dailyCharge,bool(b,"hasOxygen"),bool(b,"hasVentilator"),bool(b,"hasMonitor"),bool(b,"attachedBathroom"),bool(b,"isolationCapable"),text(b,"notes"),id);
        audit.record(actorId(),"BED_UPDATED","beds",id,Map.of("bedCode",bedCode,"wardId",wardId),req);
        return Map.of("message","Bed details updated.");
    }

    @org.springframework.transaction.annotation.Transactional
    @PatchMapping("/beds/{id}/status")
    public Map<String,Object> bedStatus(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
        String next = required(b,"status").toUpperCase(Locale.ROOT);
        String reason = required(b,"reason");
        if (!Set.of("AVAILABLE","MAINTENANCE","OUT_OF_SERVICE").contains(next))
            throw new IllegalArgumentException("Administrator status changes support AVAILABLE, MAINTENANCE or OUT_OF_SERVICE. Use housekeeping to release a CLEANING bed.");
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT status FROM beds WHERE bed_id=? FOR UPDATE",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Bed not found.");
        String previous = String.valueOf(rows.get(0).get("status"));
        if (Set.of("OCCUPIED","RESERVED").contains(previous))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Occupied or reserved beds cannot be manually changed.");
        if ("CLEANING".equals(previous) && "AVAILABLE".equals(next))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A cleaning bed must be released through the housekeeping Mark clean action.");
        if (previous.equals(next)) return Map.of("message","Bed already has this status.");
        Map<String,Set<String>> allowed = Map.of(
                "AVAILABLE",Set.of("MAINTENANCE","OUT_OF_SERVICE"),
                "MAINTENANCE",Set.of("AVAILABLE","OUT_OF_SERVICE"),
                "OUT_OF_SERVICE",Set.of("AVAILABLE","MAINTENANCE"),
                "CLEANING",Set.of("MAINTENANCE","OUT_OF_SERVICE"));
        if (!allowed.getOrDefault(previous,Set.of()).contains(next))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid bed status transition: "+previous+" → "+next+".");
        long actor = actorId();
        int changed = jdbc.update("UPDATE beds SET status=? WHERE bed_id=? AND status=?",next,id,previous);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Bed status changed by another user. Refresh and retry.");
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP())",id,previous,next,reason,actor);
        audit.record(actor,"BED_STATUS_CHANGED","beds",id,Map.of("from",previous,"to",next,"reason",reason),req);
        return Map.of("message","Bed status updated.");
    }

    private void ensureActiveWard(long wardId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM wards WHERE ward_id=? AND status='ACTIVE'",Long.class,wardId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Select an active ward before saving a bed.");
    }

    @GetMapping("/staff")
    public List<Map<String,Object>> staff() {
        return jdbc.queryForList("SELECT user_id,username,email,phone,full_name,role,account_status,last_login_at,created_at FROM users WHERE role IN ('ADMIN','RECEPTIONIST') ORDER BY created_at DESC");
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createStaff(@RequestBody Map<String,Object> b,HttpServletRequest req) {
        String username=required(b,"username"), fullName=required(b,"fullName"), password=required(b,"password");
        String role=value(b,"role","RECEPTIONIST").toUpperCase(Locale.ROOT);
        if (!Set.of("ADMIN","RECEPTIONIST").contains(role)) throw new IllegalArgumentException("Staff role must be ADMIN or RECEPTIONIST.");
        if (password.length()<10) throw new IllegalArgumentException("Password must be at least 10 characters.");
        jdbc.update("INSERT INTO users(username,email,phone,full_name,password_hash,role,account_status,password_changed_at) VALUES(?,?,?,?,?,?, 'ACTIVE',UTC_TIMESTAMP())",
                username,text(b,"email"),text(b,"phone"),fullName,encoder.encode(password),role);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        audit.record(actorId(),"STAFF_CREATED","users",id,Map.of("role",role),req);
        return Map.of("userId",id,"message","Staff account created.");
    }

    @org.springframework.transaction.annotation.Transactional
    @PatchMapping("/staff/{id}/status")
    public Map<String,Object> staffStatus(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
        String status=required(b,"status").toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE","DISABLED","LOCKED").contains(status)) throw new IllegalArgumentException("Invalid account status.");
        if (id==actorId() && !"ACTIVE".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"You cannot deactivate your own account.");
        jdbc.update("UPDATE users SET account_status=?,locked_until=NULL WHERE user_id=? AND role IN ('ADMIN','RECEPTIONIST')",status,id);
        audit.record(actorId(),"STAFF_STATUS_CHANGED","users",id,Map.of("status",status),req);
        return Map.of("message","Staff status updated.");
    }

    @GetMapping("/patients")
    public List<Map<String,Object>> patients(@RequestParam(required=false) String q) {
        String term=q==null?"":q.trim();
        return jdbc.queryForList("SELECT patient_id,uhid,full_name,date_of_birth,TIMESTAMPDIFF(YEAR,date_of_birth,CURDATE()) age,gender,blood_group,phone,email,insurance_type,record_status,created_at FROM patients WHERE (?='' OR uhid LIKE CONCAT('%',?,'%') OR full_name LIKE CONCAT('%',?,'%') OR phone LIKE CONCAT('%',?,'%')) ORDER BY created_at DESC LIMIT 500",term,term,term,term);
    }


    @GetMapping("/allocations")
    public List<Map<String,Object>> allocations(@RequestParam(defaultValue="ACTIVE") String status) {
        return jdbc.queryForList("SELECT a.allocation_id,a.patient_id,a.bed_id,a.request_id,a.admit_time,a.expected_discharge,a.discharge_time,a.discharge_type,a.status,p.uhid,p.full_name patient_name,b.bed_code,w.ward_name,w.ward_type,u.full_name allocated_by_name FROM allocations a JOIN patients p ON p.patient_id=a.patient_id JOIN beds b ON b.bed_id=a.bed_id JOIN wards w ON w.ward_id=b.ward_id JOIN users u ON u.user_id=a.allocated_by WHERE (?='ALL' OR a.status=?) ORDER BY a.admit_time DESC LIMIT 500",status,status);
    }

    @GetMapping("/waiting-list")
    public List<Map<String,Object>> waitingList() {
        return jdbc.queryForList("SELECT wl.wait_id,wl.request_id,wl.priority_score,wl.added_at,wl.status,r.esi_level,r.requested_category,p.uhid,p.full_name patient_name,p.phone FROM waiting_list wl JOIN admission_requests r ON r.request_id=wl.request_id JOIN patients p ON p.patient_id=r.patient_id WHERE wl.status IN ('WAITING','OFFERED') AND r.status IN ('WAITING','VERIFIED') ORDER BY r.esi_level,wl.added_at");
    }

    /** Admin may cancel an unallocated request; physical deletion is deliberately prohibited. */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/admission-requests/{id}/cancel")
    public Map<String,Object> cancelAdmissionRequest(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        String reason=required(body,"reason");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT status FROM admission_requests WHERE request_id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Admission request not found.");
        String status=String.valueOf(rows.get(0).get("status"));
        if(!Set.of("PENDING_VERIFICATION","VERIFIED","WAITING").contains(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending, verified or waiting requests can be cancelled. Allocated requests must follow the discharge workflow.");
        int changed=jdbc.update("UPDATE admission_requests SET status='CANCELLED' WHERE request_id=? AND status=?",id,status);
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Request changed concurrently. Refresh and retry.");
        jdbc.update("UPDATE waiting_list SET status='CANCELLED' WHERE request_id=? AND status IN ('WAITING','OFFERED')",id);
        audit.record(actorId(),"ADMISSION_REQUEST_CANCELLED_BY_ADMIN","admission_requests",id,Map.of("previousStatus",status,"reason",reason.trim()),req);
        return Map.of("message","Admission request cancelled. Audit and clinical history retained.");
    }

    @GetMapping("/audit")
    public List<Map<String,Object>> auditLogs(@RequestParam(required=false) String action,
                                              @RequestParam(required=false) String user,
                                              @RequestParam(defaultValue="100") int limit) {
        int safe=Math.max(1,Math.min(limit,500));
        return jdbc.queryForList("SELECT a.audit_id,a.action,a.entity_type,a.entity_id,a.details,a.ip_address,a.event_time,u.username,u.full_name FROM audit_log a LEFT JOIN users u ON u.user_id=a.user_id WHERE (? IS NULL OR a.action LIKE CONCAT('%',?,'%')) AND (? IS NULL OR u.username LIKE CONCAT('%',?,'%') OR u.full_name LIKE CONCAT('%',?,'%')) ORDER BY a.event_time DESC LIMIT " + safe,action,action,user,user,user);
    }

    @GetMapping("/reports/occupancy")
    public Map<String,Object> occupancyReport() {
        return Map.of("byWard",jdbc.queryForList("SELECT * FROM v_ward_occupancy ORDER BY ward_name"),
                "daily",jdbc.queryForList("SELECT day,SUM(admissions) admissions,SUM(discharges) discharges FROM (SELECT DATE(admit_time) day,COUNT(*) admissions,0 discharges FROM allocations WHERE admit_time>=UTC_DATE()-INTERVAL 29 DAY GROUP BY DATE(admit_time) UNION ALL SELECT DATE(discharge_time) day,0 admissions,COUNT(*) discharges FROM allocations WHERE discharge_time>=UTC_DATE()-INTERVAL 29 DAY GROUP BY DATE(discharge_time)) t GROUP BY day ORDER BY day"),
                "metrics",jdbc.queryForMap("SELECT (SELECT COUNT(*) FROM allocations) total_allocations,(SELECT ROUND(AVG(TIMESTAMPDIFF(HOUR,admit_time,COALESCE(discharge_time,UTC_TIMESTAMP())))/24,2) FROM allocations) average_stay_days,(SELECT ROUND(AVG(TIMESTAMPDIFF(MINUTE,requested_at,verified_at)),1) FROM admission_requests WHERE verified_at IS NOT NULL) average_wait_minutes,(SELECT ROUND(AVG(TIMESTAMPDIFF(MINUTE,c.changed_at,a.changed_at)),1) FROM bed_status_history c JOIN bed_status_history a ON a.bed_id=c.bed_id AND a.old_status='CLEANING' AND a.new_status='AVAILABLE' AND a.changed_at>c.changed_at WHERE c.new_status='CLEANING' AND a.changed_at=(SELECT MIN(a2.changed_at) FROM bed_status_history a2 WHERE a2.bed_id=c.bed_id AND a2.old_status='CLEANING' AND a2.new_status='AVAILABLE' AND a2.changed_at>c.changed_at)) average_cleaning_minutes"));
    }

    @GetMapping("/reports/occupancy.csv")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> occupancyCsv() {
        var rows=jdbc.queryForList("SELECT ward_name,ward_type,capacity,total_beds,available_beds,occupied_beds,unavailable_beds FROM v_ward_occupancy ORDER BY ward_name");
        StringBuilder csv=new StringBuilder("Ward,Ward Type,Capacity,Total Beds,Available,Occupied,Unavailable\r\n");
        for (var r:rows) csv.append(csv(r.get("ward_name"))).append(',').append(csv(r.get("ward_type"))).append(',').append(r.get("capacity")).append(',').append(r.get("total_beds")).append(',').append(r.get("available_beds")).append(',').append(r.get("occupied_beds")).append(',').append(r.get("unavailable_beds")).append("\r\n");
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=ihbas-occupancy.csv").header("Content-Type","text/csv; charset=utf-8").body(csv.toString());
    }

    @GetMapping("/notifications")
    public List<Map<String,Object>> notifications(@RequestParam(defaultValue="50") int limit) {
        return jdbc.queryForList("SELECT notification_id,title,message,notification_type,is_read,created_at FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT ?",actorId(),Math.min(200,Math.max(1,limit)));
    }

    @PatchMapping("/notifications/{id}/read")
    public Map<String,Object> markNotificationRead(@PathVariable long id) {
        jdbc.update("UPDATE notifications SET is_read=TRUE,read_at=UTC_TIMESTAMP() WHERE notification_id=? AND user_id=?",id,actorId());
        return Map.of("message","Notification marked as read.");
    }

    @GetMapping("/settings")
    public Map<String,Object> settings() { return jdbc.queryForMap("SELECT hospital_id,hospital_code,hospital_name,timezone_name,phone,email,address FROM hospital_profile WHERE hospital_id=1"); }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/settings")
    public Map<String,Object> updateSettings(@RequestBody Map<String,Object> b,HttpServletRequest req) {
        jdbc.update("UPDATE hospital_profile SET hospital_name=?,timezone_name=?,phone=?,email=?,address=? WHERE hospital_id=1",
                required(b,"hospitalName"),value(b,"timezoneName","Asia/Kolkata"),text(b,"phone"),text(b,"email"),text(b,"address"));
        audit.record(actorId(),"HOSPITAL_SETTINGS_UPDATED","hospital_profile",1,Map.of(),req);
        return Map.of("message","Hospital settings updated.");
    }

    private List<Map<String,Object>> alerts() {
        List<Map<String,Object>> out=new ArrayList<>();
        out.addAll(jdbc.queryForList("SELECT 'WARD_OVER_90_PERCENT' alert_type,ward_name title,CONCAT(ROUND(100*occupied_beds/NULLIF(total_beds,0),1),'% occupied') message FROM v_ward_occupancy WHERE total_beds>0 AND 100*occupied_beds/total_beds>90"));
        long icu=count("SELECT COUNT(*) FROM beds b JOIN wards w ON w.ward_id=b.ward_id WHERE UPPER(w.ward_type)='ICU' AND b.status='AVAILABLE'");
        if(icu<2) out.add(Map.of("alert_type","LOW_ICU_CAPACITY","title","ICU capacity low","message","Only "+icu+" ICU beds available."));
        out.addAll(jdbc.queryForList("SELECT 'CLEANING_DELAY' alert_type,b.bed_code title,CONCAT('In cleaning for ',TIMESTAMPDIFF(MINUTE,h.changed_at,UTC_TIMESTAMP()),' minutes') message FROM beds b JOIN (SELECT bed_id,MAX(changed_at) changed_at FROM bed_status_history GROUP BY bed_id) h ON h.bed_id=b.bed_id WHERE b.status='CLEANING' AND h.changed_at<UTC_TIMESTAMP()-INTERVAL 60 MINUTE"));
        return out;
    }
    private long actorId(){ return jdbc.queryForObject("SELECT user_id FROM users WHERE username=?",Long.class, RequestUser.username()); }
    private long count(String sql){Long n=jdbc.queryForObject(sql,Long.class);return n==null?0:n;}
    private Object number(String sql){return jdbc.queryForObject(sql,Object.class);}
    private static String csv(Object value){String s=value==null?"":String.valueOf(value);return "\""+s.replace("\"","\"\"")+"\"";}
    private static String required(Map<String,Object>b,String k){String s=text(b,k);if(s==null||s.isBlank())throw new IllegalArgumentException(k+" is required.");return s.trim();}
    private static String text(Map<String,Object>b,String k){Object v=b.get(k);return v==null?null:String.valueOf(v);}
    private static String value(Map<String,Object>b,String k,String d){String s=text(b,k);return s==null||s.isBlank()?d:s;}
    private static int integer(Map<String,Object>b,String k,int d){Object v=b.get(k);return v==null?d:Integer.parseInt(String.valueOf(v));}
    private static long longValue(Map<String,Object>b,String k){Object v=b.get(k);if(v==null)throw new IllegalArgumentException(k+" is required.");return Long.parseLong(String.valueOf(v));}
    private static BigDecimal decimal(Map<String,Object>b,String k,BigDecimal d){Object v=b.get(k);return v==null?d:new BigDecimal(String.valueOf(v));}
    private static boolean bool(Map<String,Object>b,String k){Object v=b.get(k);return v!=null && (Boolean.parseBoolean(String.valueOf(v)) || "1".equals(String.valueOf(v)));}
}

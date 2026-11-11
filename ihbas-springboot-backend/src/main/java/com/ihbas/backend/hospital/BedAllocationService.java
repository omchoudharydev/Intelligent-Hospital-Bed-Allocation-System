package com.ihbas.backend.hospital;

import com.ihbas.backend.common.AuditService;
import com.ihbas.backend.common.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BedAllocationService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    public BedAllocationService(JdbcTemplate jdbc, AuditService audit, NotificationService notifications) {
        this.jdbc=jdbc; this.audit=audit; this.notifications=notifications;
    }

    public List<Map<String,Object>> suggestions(long requestId) {
        Map<String,Object> request = request(requestId, false);
        List<Map<String,Object>> candidates = candidates(request);
        candidates.sort((a,b)->Integer.compare((int)b.get("matchScore"),(int)a.get("matchScore")));
        return candidates;
    }

    @Transactional
    public Map<String,Object> allocate(long requestId, long bedId, long actorId, String reason, HttpServletRequest http) {
        if (reason==null || reason.isBlank()) throw new IllegalArgumentException("Allocation reason is mandatory.");
        Map<String,Object> r=request(requestId,true);
        String status=String.valueOf(r.get("status"));
        if (!Set.of("VERIFIED","WAITING").contains(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Request must be verified before allocation.");
        Map<String,Object> bed=jdbc.queryForMap("SELECT b.bed_id,b.bed_code,b.ward_id,b.category,b.daily_charge,b.status,w.ward_type,w.gender_policy,b.has_oxygen,b.has_ventilator,b.has_monitor,b.isolation_capable FROM beds b JOIN wards w ON w.ward_id=b.ward_id WHERE b.bed_id=? FOR UPDATE",bedId);
        if (!"AVAILABLE".equals(String.valueOf(bed.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Selected bed is no longer available. Refresh suggestions.");
        validateCompatibility(r,bed);
        Long existing=jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE patient_id=? AND status='ACTIVE'",Long.class,((Number)r.get("patient_id")).longValue());
        if(existing!=null && existing>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Patient already has an active bed allocation.");
        LocalDateTime now=LocalDateTime.now();
        Object expected = r.get("expected_stay_days")==null?null:java.sql.Timestamp.valueOf(now.plusDays(((Number)r.get("expected_stay_days")).longValue()));
        jdbc.update("INSERT INTO allocations(patient_id,bed_id,request_id,allocated_by,allocation_reason,admit_time,expected_discharge,status) VALUES(?,?,?,?,?,UTC_TIMESTAMP(),?,'ACTIVE')",
                ((Number)r.get("patient_id")).longValue(),bedId,requestId,actorId,reason.trim(),expected);
        long allocationId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("UPDATE beds SET status='OCCUPIED' WHERE bed_id=? AND status='AVAILABLE'",bedId);
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(?,'AVAILABLE','OCCUPIED',?,?,UTC_TIMESTAMP())",bedId,"Allocation #"+allocationId,actorId);
        jdbc.update("INSERT INTO allocation_bed_history(allocation_id,bed_id,started_at,daily_charge_snapshot) VALUES(?,?,UTC_TIMESTAMP(),?)",allocationId,bedId,bed.get("daily_charge"));
        jdbc.update("UPDATE admission_requests SET status='ALLOCATED',verified_by=COALESCE(verified_by,?),verified_at=COALESCE(verified_at,UTC_TIMESTAMP()) WHERE request_id=?",actorId,requestId);
        jdbc.update("UPDATE waiting_list SET status='ALLOCATED' WHERE request_id=? AND status IN ('WAITING','OFFERED')",requestId);
        Long patientUser=(Long)r.get("user_id");
        notifications.create(patientUser,"Bed allocated","Your bed "+bed.get("bed_code")+" in "+bed.get("ward_type")+" has been allocated.","ALLOCATION");
        audit.record(actorId,"BED_ALLOCATED","allocations",allocationId,Map.of("requestId",requestId,"bedId",bedId,"reason",reason),http);
        return Map.of("allocationId",allocationId,"bedId",bedId,"bedCode",bed.get("bed_code"),"message","Bed allocated successfully.");
    }

    @Transactional
    public Map<String,Object> transfer(long allocationId,long targetBedId,long actorId,String reason,HttpServletRequest http) {
        if(reason==null||reason.isBlank()) throw new IllegalArgumentException("Transfer reason is mandatory.");
        Map<String,Object> a=jdbc.queryForMap("SELECT a.allocation_id,a.patient_id,a.bed_id,a.request_id,a.status,p.gender,p.date_of_birth,p.full_name,p.user_id,r.requested_category,r.requires_oxygen,r.requires_ventilator,r.requires_monitor,r.isolation_required,r.clinical_isolation_verified FROM allocations a JOIN patients p ON p.patient_id=a.patient_id JOIN admission_requests r ON r.request_id=a.request_id WHERE a.allocation_id=? FOR UPDATE",allocationId);
        if(!"ACTIVE".equals(String.valueOf(a.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only active admissions can be transferred.");
        long from=((Number)a.get("bed_id")).longValue();
        if(from==targetBedId) throw new IllegalArgumentException("Source and destination beds must be different.");
        Map<String,Object> to=jdbc.queryForMap("SELECT b.bed_id,b.bed_code,b.ward_id,b.status,b.category,b.daily_charge,b.has_oxygen,b.has_ventilator,b.has_monitor,b.isolation_capable,w.ward_type,w.gender_policy,w.status ward_status FROM beds b JOIN wards w ON w.ward_id=b.ward_id WHERE b.bed_id=? FOR UPDATE",targetBedId);
        if(!"ACTIVE".equals(String.valueOf(to.get("ward_status")))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Destination ward is inactive.");
        if(!"AVAILABLE".equals(String.valueOf(to.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Destination bed is not available.");
        // Reuse the original, clinically verified admission requirements. Do not infer
        // requirements from the destination bed; doing so would make every bed look compatible.
        validateCompatibility(a,to);
        Map<String,Object> old=jdbc.queryForMap("SELECT status FROM beds WHERE bed_id=? FOR UPDATE",from);
        jdbc.update("UPDATE allocation_bed_history SET ended_at=UTC_TIMESTAMP() WHERE allocation_id=? AND ended_at IS NULL",allocationId);
        jdbc.update("INSERT INTO transfers(allocation_id,from_bed_id,to_bed_id,reason,transferred_by,transfer_time) VALUES(?,?,?,?,?,UTC_TIMESTAMP())",allocationId,from,targetBedId,reason.trim(),actorId);
        jdbc.update("UPDATE allocations SET bed_id=? WHERE allocation_id=?",targetBedId,allocationId);
        jdbc.update("UPDATE beds SET status='CLEANING' WHERE bed_id=?",from);
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(? ,?,'CLEANING',?,?,UTC_TIMESTAMP())",from,old.get("status"),"Patient transferred: "+reason,actorId);
        jdbc.update("UPDATE beds SET status='OCCUPIED' WHERE bed_id=?",targetBedId);
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(?,'AVAILABLE','OCCUPIED',?,?,UTC_TIMESTAMP())",targetBedId,"Transfer for allocation #"+allocationId,actorId);
        jdbc.update("INSERT INTO allocation_bed_history(allocation_id,bed_id,started_at,daily_charge_snapshot) VALUES(?,?,UTC_TIMESTAMP(),?)",allocationId,targetBedId,to.get("daily_charge"));
        notifications.create((Long)a.get("user_id"),"Bed transferred","Your bed has been changed to "+to.get("bed_code")+".","TRANSFER");
        audit.record(actorId,"PATIENT_TRANSFERRED","allocations",allocationId,Map.of("fromBedId",from,"toBedId",targetBedId,"reason",reason),http);
        return Map.of("message","Patient transferred. Previous bed moved to Cleaning.","fromBedId",from,"toBedId",targetBedId);
    }

    @Transactional
    public Map<String,Object> discharge(long allocationId,long actorId,String type,String reason,HttpServletRequest http) {
        if(reason==null||reason.isBlank()) throw new IllegalArgumentException("Discharge reason is mandatory.");
        String normalized=type==null?"OTHER":type.toUpperCase(Locale.ROOT);
        if(!Set.of("RECOVERED","REFERRED","LAMA","DECEASED","OTHER").contains(normalized)) throw new IllegalArgumentException("Invalid discharge type.");
        Map<String,Object> a=jdbc.queryForMap("SELECT a.allocation_id,a.patient_id,a.bed_id,a.status,p.user_id,p.full_name FROM allocations a JOIN patients p ON p.patient_id=a.patient_id WHERE a.allocation_id=? FOR UPDATE",allocationId);
        if(!"ACTIVE".equals(String.valueOf(a.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Admission is not active.");
        long bedId=((Number)a.get("bed_id")).longValue();
        Map<String,Object> bed=jdbc.queryForMap("SELECT status FROM beds WHERE bed_id=? FOR UPDATE",bedId);
        jdbc.update("UPDATE allocations SET status='DISCHARGED',discharge_time=UTC_TIMESTAMP(),discharge_type=? WHERE allocation_id=?",normalized,allocationId);
        jdbc.update("UPDATE allocation_bed_history SET ended_at=UTC_TIMESTAMP() WHERE allocation_id=? AND ended_at IS NULL",allocationId);
        jdbc.update("UPDATE beds SET status='CLEANING' WHERE bed_id=?",bedId);
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(?,?,'CLEANING',?,?,UTC_TIMESTAMP())",bedId,bed.get("status"),"Discharge: "+reason,actorId);
        generateBedInvoice(allocationId,((Number)a.get("patient_id")).longValue(),actorId);
        notifications.create((Long)a.get("user_id"),"Discharge recorded","Your discharge has been recorded. Bed is now awaiting housekeeping.","DISCHARGE");
        notifications.createForRole("ADMIN","Patient discharged",a.get("full_name")+" has been discharged.","DISCHARGE");
        audit.record(actorId,"PATIENT_DISCHARGED","allocations",allocationId,Map.of("dischargeType",normalized,"reason",reason),http);
        return Map.of("message","Discharge recorded. Bed moved to Cleaning.","allocationId",allocationId);
    }

    private void generateBedInvoice(long allocationId,long patientId,long actorId) {
        Map<String,Object> a=jdbc.queryForMap("SELECT a.admit_time,COALESCE(a.discharge_time,UTC_TIMESTAMP()) discharge_time,b.daily_charge FROM allocations a JOIN beds b ON b.bed_id=a.bed_id WHERE a.allocation_id=?",allocationId);
        java.sql.Timestamp start=(java.sql.Timestamp)a.get("admit_time");
        java.sql.Timestamp end=(java.sql.Timestamp)a.get("discharge_time");
        long hours=Math.max(1,java.time.Duration.between(start.toLocalDateTime(),end.toLocalDateTime()).toHours());
        BigDecimal days=BigDecimal.valueOf(Math.max(1, (long)Math.ceil(hours/24.0)));
        BigDecimal rate=(BigDecimal)a.get("daily_charge"); BigDecimal total=rate.multiply(days).setScale(2,java.math.RoundingMode.HALF_UP);
        String invoiceNo="INV-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO invoices(invoice_no,patient_id,allocation_id,invoice_date,due_date,currency,subtotal,discount_total,tax_total,total_amount,status,notes,created_by,issued_at) VALUES(?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'INR',0,0,0,0,'DRAFT',?, ?,NULL)",invoiceNo,patientId,allocationId,"Generated on discharge; review charges before issue.",actorId);
        long invoiceId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO invoice_items(invoice_id,description,quantity,unit_price,discount_amount,tax_rate,line_subtotal,tax_amount,line_total,service_start,service_end) VALUES(?,?,?,?,0,0,?,0,?,?,?)",invoiceId,"Bed charges",days,rate,total,total,start,end);
        jdbc.update("UPDATE invoices SET subtotal=?,total_amount=?,status='ISSUED',issued_at=UTC_TIMESTAMP() WHERE invoice_id=?",total,total,invoiceId);
    }

    @Transactional
    public Map<String,Object> markClean(long bedId,long actorId,HttpServletRequest http) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT status FROM beds WHERE bed_id=? FOR UPDATE",bedId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Bed not found.");
        String old=String.valueOf(rows.get(0).get("status"));
        if(!"CLEANING".equals(old)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only beds in Cleaning can be marked clean.");
        Long activeAllocations=jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE bed_id=? AND status='ACTIVE'",Long.class,bedId);
        Long activeHistory=jdbc.queryForObject("SELECT COUNT(*) FROM allocation_bed_history WHERE bed_id=? AND ended_at IS NULL",Long.class,bedId);
        if((activeAllocations!=null&&activeAllocations>0)||(activeHistory!=null&&activeHistory>0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This bed still has an active admission history and cannot be released.");
        int changed=jdbc.update("UPDATE beds SET status='AVAILABLE' WHERE bed_id=? AND status='CLEANING'",bedId);
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Bed status changed by another user. Refresh and retry.");
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(?,'CLEANING','AVAILABLE','Housekeeping completed',?,UTC_TIMESTAMP())",bedId,actorId);
        audit.record(actorId,"BED_CLEANED","beds",bedId,Map.of("newStatus","AVAILABLE"),http);
        notifyWaitingQueue();
        return Map.of("message","Bed cleaning recorded. Bed is now Available.");
    }

    @Transactional
    public Map<String,Object> addToWaitingList(long requestId,long actorId,HttpServletRequest http) {
        Map<String,Object> r=request(requestId,true);
        jdbc.update("UPDATE admission_requests SET status='WAITING' WHERE request_id=? AND status NOT IN ('ALLOCATED','REJECTED','CANCELLED')",requestId);
        jdbc.update("INSERT INTO waiting_list(request_id,priority_score,status,added_at) VALUES(?,?,'WAITING',UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE status='WAITING'",requestId,priority(((Number)r.get("esi_level")).intValue(),r.get("date_of_birth")));
        audit.record(actorId,"ADDED_TO_WAITING_LIST","admission_requests",requestId,Map.of(),http);
        return Map.of("message","Request added to waiting list.");
    }

    @Transactional
    public int expireReservations() {
        List<Long> ids=jdbc.queryForList("SELECT reservation_id FROM reservations WHERE status='ACTIVE' AND expires_at<=UTC_TIMESTAMP() FOR UPDATE",Long.class);
        for(Long id:ids){
            Map<String,Object> r=jdbc.queryForMap("SELECT bed_id FROM reservations WHERE reservation_id=?",id);
            long bedId=((Number)r.get("bed_id")).longValue();
            jdbc.update("UPDATE reservations SET status='EXPIRED' WHERE reservation_id=?",id);
            jdbc.update("UPDATE beds SET status='AVAILABLE' WHERE bed_id=? AND status='RESERVED'",bedId);
            jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_at) VALUES(?,'RESERVED','AVAILABLE','Reservation expired',UTC_TIMESTAMP())",bedId);
        }
        return ids.size();
    }

    private void notifyWaitingQueue() {
        // A housekeeping event triggers an in-app reminder; staff still confirm the suggested allocation.
        notifications.createForRole("RECEPTIONIST","Bed available","A bed became available. Review the priority waiting list.","WAITING_LIST");
    }

    private Map<String,Object> request(long id,boolean lock) {
        String sql="SELECT r.request_id,r.patient_id,r.requested_category,r.esi_level,r.reason,r.doctor_id,r.expected_stay_days,r.requires_oxygen,r.requires_ventilator,r.requires_monitor,r.isolation_required,r.clinical_isolation_verified,r.status,r.requested_by,p.user_id,p.full_name,p.gender,p.date_of_birth,p.phone FROM admission_requests r JOIN patients p ON p.patient_id=r.patient_id WHERE r.request_id=?"+(lock?" FOR UPDATE":"");
        List<Map<String,Object>> rows=jdbc.queryForList(sql,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Admission request not found.");
        return rows.get(0);
    }

    private List<Map<String,Object>> candidates(Map<String,Object> r) {
        List<Map<String,Object>> beds=jdbc.queryForList("SELECT b.bed_id,b.bed_code,b.ward_id,b.category,b.floor_label,b.daily_charge,b.has_oxygen,b.has_ventilator,b.has_monitor,b.isolation_capable,b.status,w.ward_name,w.ward_type,w.gender_policy,COALESCE((SELECT MAX(h.changed_at) FROM bed_status_history h WHERE h.bed_id=b.bed_id),b.created_at) idle_since,EXISTS(SELECT 1 FROM doctor_ward_assignments dwa WHERE dwa.doctor_id=? AND dwa.ward_id=b.ward_id AND dwa.assigned_from<=CURDATE() AND (dwa.assigned_until IS NULL OR dwa.assigned_until>=CURDATE())) doctor_match FROM beds b JOIN wards w ON w.ward_id=b.ward_id WHERE b.status='AVAILABLE' AND w.status='ACTIVE'",r.get("doctor_id"));
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object>b:beds){
            try{validateCompatibility(r,b);}catch(RuntimeException ex){continue;}
            String requested=String.valueOf(r.get("requested_category")).toUpperCase(Locale.ROOT);
            String wardType=String.valueOf(b.get("ward_type")).toUpperCase(Locale.ROOT);
            int wardScore=wardType.equals(requested)?40:(alternative(requested).contains(wardType)?30:0);
            int facilities=25;
            if(truth(r.get("requires_oxygen"))&&!truth(b.get("has_oxygen")))facilities-=8;
            if(truth(r.get("requires_ventilator"))&&!truth(b.get("has_ventilator")))facilities-=9;
            if(truth(r.get("requires_monitor"))&&!truth(b.get("has_monitor")))facilities-=8;
            int doctor=truth(b.get("doctor_match"))?10:0;
            long idle=0;Object idleSince=b.get("idle_since");if(idleSince instanceof java.sql.Timestamp t)idle=Math.max(0,java.time.Duration.between(t.toLocalDateTime(),LocalDateTime.now()).toHours());
            int idleScore=(int)Math.min(10,idle/6);
            Map<String,Object> scored=new LinkedHashMap<>(b);scored.put("requested_category",r.get("requested_category"));scored.put("matchScore",wardScore+facilities+15+doctor+idleScore);scored.put("scoreBreakdown",Map.of("wardMatch",wardScore,"facilities",facilities,"patientCompatibility",15,"doctorProximity",doctor,"idleTime",idleScore));
            result.add(scored);
        }
        result.sort((a,b)->Integer.compare((int)b.get("matchScore"),(int)a.get("matchScore")));
        return result;
    }

    private void validateCompatibility(Map<String,Object> r,Map<String,Object>b) {
        String gender=String.valueOf(r.get("gender"));String policy=String.valueOf(b.get("gender_policy"));
        if("MALE_ONLY".equals(policy)&&!"MALE".equals(gender)) throw new IllegalArgumentException("Gender policy mismatch");
        if("FEMALE_ONLY".equals(policy)&&!"FEMALE".equals(gender)) throw new IllegalArgumentException("Gender policy mismatch");
        Object dob=r.get("date_of_birth");
        if(dob instanceof java.sql.Date d && d.toLocalDate().isAfter(java.time.LocalDate.now().minusDays(28))){
            String wt=String.valueOf(b.get("ward_type")).toUpperCase(Locale.ROOT);
            if(!Set.of("NICU","PEDIATRIC","MATERNITY").contains(wt)) throw new IllegalArgumentException("Infant must be placed in NICU/Pediatric/Maternity");
        }
        if(truth(r.get("isolation_required")) && (!truth(b.get("isolation_capable")) || !truth(r.get("clinical_isolation_verified")))) throw new IllegalArgumentException("Isolation requirements not met");
        if(truth(r.get("requires_oxygen"))&&!truth(b.get("has_oxygen"))) throw new IllegalArgumentException("Oxygen facility missing");
        if(truth(r.get("requires_ventilator"))&&!truth(b.get("has_ventilator"))) throw new IllegalArgumentException("Ventilator facility missing");
        if(truth(r.get("requires_monitor"))&&!truth(b.get("has_monitor"))) throw new IllegalArgumentException("Monitor facility missing");
        String requested=String.valueOf(r.get("requested_category")).toUpperCase(Locale.ROOT), actual=String.valueOf(b.get("ward_type")).toUpperCase(Locale.ROOT);
        if(!requested.equals(actual)&&!alternative(requested).contains(actual)) throw new IllegalArgumentException("Ward is not a supported match or alternative");
    }
    private List<String> alternative(String requested){return switch(requested){case "ICU"->List.of("HDU");case "PRIVATE"->List.of("SEMI-PRIVATE","SEMI_PRIVATE");case "SEMI-PRIVATE","SEMI_PRIVATE"->List.of("GENERAL");default->List.of();};}
    private double priority(int esi,Object dob){long age=0;if(dob instanceof java.sql.Date d)age=java.time.Period.between(d.toLocalDate(),java.time.LocalDate.now()).getYears();return 1000-(esi*100)+(age<1?15:(age>=60?10:0));}
    private static boolean truth(Object o){return o instanceof Boolean b?b:o instanceof Number n&&n.intValue()!=0||"true".equalsIgnoreCase(String.valueOf(o))||"1".equals(String.valueOf(o));}
}

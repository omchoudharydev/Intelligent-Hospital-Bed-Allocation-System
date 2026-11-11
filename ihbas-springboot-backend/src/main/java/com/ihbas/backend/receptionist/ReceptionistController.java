package com.ihbas.backend.receptionist;

import com.ihbas.backend.common.AuditService;
import com.ihbas.backend.common.RequestUser;
import com.ihbas.backend.hospital.BedAllocationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/receptionist")
public class ReceptionistController {
    private final JdbcTemplate jdbc;
    private final BedAllocationService allocation;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    public ReceptionistController(JdbcTemplate jdbc, BedAllocationService allocation, AuditService audit, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.allocation = allocation;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/stats")
    public Map<String,Object> stats(){
        return Map.of("pendingRequests",count("SELECT COUNT(*) FROM admission_requests WHERE status IN ('PENDING_VERIFICATION','VERIFIED','WAITING')"),
                "emergencyCases",count("SELECT COUNT(*) FROM admission_requests WHERE status IN ('PENDING_VERIFICATION','VERIFIED','WAITING') AND esi_level<=2"),
                "availableBeds",count("SELECT COUNT(*) FROM beds WHERE status='AVAILABLE'"),
                "dischargesToday",count("SELECT COUNT(*) FROM allocations WHERE discharge_time IS NOT NULL AND DATE(discharge_time)=UTC_DATE()"),
                "activeAdmissions",count("SELECT COUNT(*) FROM allocations WHERE status='ACTIVE'"));
    }

    @GetMapping("/patients")
    public List<Map<String,Object>> searchPatients(@RequestParam(required=false) String q){
        String term=q==null?"":q.trim();
        return jdbc.queryForList("SELECT patient_id,uhid,full_name,date_of_birth,TIMESTAMPDIFF(YEAR,date_of_birth,CURDATE()) age,gender,blood_group,phone,email,insurance_type,record_status FROM patients WHERE record_status='ACTIVE' AND (?='' OR uhid LIKE CONCAT('%',?,'%') OR full_name LIKE CONCAT('%',?,'%') OR phone LIKE CONCAT('%',?,'%')) ORDER BY full_name LIMIT 100",term,term,term,term);
    }

    @PostMapping("/patients")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> registerPatient(@RequestBody Map<String,Object> b, HttpServletRequest req) {
        String name = required(b, "fullName");
        String phone = required(b, "phone");
        String password = required(b, "password");
        validatePatientPassword(password, b);
        String gender = required(b, "gender").toUpperCase(Locale.ROOT);
        if (!Boolean.parseBoolean(String.valueOf(b.getOrDefault("consentGranted", false))))
            throw new IllegalArgumentException("Patient data-processing consent is required.");
        LocalDate dob = LocalDate.parse(required(b, "dateOfBirth"));
        if (dob.isAfter(LocalDate.now())) throw new IllegalArgumentException("Date of birth cannot be in the future.");
        if (!Set.of("MALE", "FEMALE", "OTHER", "UNDISCLOSED").contains(gender))
            throw new IllegalArgumentException("Invalid gender.");
        Long duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM patients WHERE LOWER(full_name)=LOWER(?) AND date_of_birth=? AND phone=? AND record_status='ACTIVE'",
                Long.class, name, dob, phone);
        if (duplicate != null && duplicate > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Possible duplicate patient: same name, date of birth and phone already exists.");

        String email = normalizedOptional(text(b, "email"));
        ensureEmailAvailable(email);
        String uhid = "IHBAS-" + LocalDate.now().getYear() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        long actor = actorId();
        jdbc.update("INSERT INTO patients(user_id,uhid,full_name,date_of_birth,gender,blood_group,phone,email,address,emergency_contact_name,emergency_contact_phone,id_proof_type,allergies,chronic_conditions,insurance_type,record_status,created_by) VALUES(NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)",
                uhid, name, dob, gender, value(b,"bloodGroup","UNKNOWN").toUpperCase(Locale.ROOT), phone, email,
                text(b,"address"), text(b,"emergencyContactName"), text(b,"emergencyContactPhone"),
                value(b,"idProofType","NONE").toUpperCase(Locale.ROOT), text(b,"allergies"), text(b,"chronicConditions"),
                value(b,"insuranceType","CASH").toUpperCase(Locale.ROOT), actor);
        long patientId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String,Object> credentials = createPatientLogin(patientId, uhid, name, phone, email, password, actor, req);
        jdbc.update("INSERT INTO patient_consents(patient_id,consent_type,consent_version,granted,granted_at,recorded_by,notes) VALUES(? ,'DATA_PROCESSING','1.0',TRUE,UTC_TIMESTAMP(),?,'Captured during receptionist registration')", patientId, actor);
        audit.record(actor, "PATIENT_REGISTERED", "patients", patientId, Map.of("uhid", uhid, "userId", credentials.get("userId")), req);
        return credentials;
    }

    /** Create login credentials for an existing patient record that does not yet have a linked account. */
    @PostMapping("/patients/{id}/account")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> createPatientAccount(@PathVariable long id, @RequestBody Map<String,Object> b, HttpServletRequest req) {
        String password = required(b, "password");
        validatePatientPassword(password, b);
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT patient_id,user_id,uhid,full_name,phone,email,record_status FROM patients WHERE patient_id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient record not found.");
        Map<String,Object> patient = rows.get(0);
        if (patient.get("user_id") != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This patient already has a login account.");
        if (!"ACTIVE".equals(String.valueOf(patient.get("record_status"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only active patient records can receive a login account.");
        String email = normalizedOptional(text(b, "email"));
        if (email == null) email = normalizedOptional((String) patient.get("email"));
        ensureEmailAvailable(email);
        long actor = actorId();
        Map<String,Object> credentials = createPatientLogin(id, String.valueOf(patient.get("uhid")),
                String.valueOf(patient.get("full_name")), String.valueOf(patient.get("phone")), email, password, actor, req);
        if (email != null) jdbc.update("UPDATE patients SET email=? WHERE patient_id=?", email, id);
        return credentials;
    }

    @GetMapping("/patients/{id}")
    public Map<String,Object> patient(@PathVariable long id){
        return jdbc.queryForMap("SELECT p.patient_id,p.user_id,u.username login_username,p.uhid,p.full_name,p.date_of_birth,TIMESTAMPDIFF(YEAR,p.date_of_birth,CURDATE()) age,p.gender,p.blood_group,p.phone,p.email,p.address,p.emergency_contact_name,p.emergency_contact_phone,p.allergies,p.chronic_conditions,p.insurance_type,p.record_status,p.created_at FROM patients p LEFT JOIN users u ON u.user_id=p.user_id WHERE p.patient_id=?",id);
    }

    private Map<String,Object> createPatientLogin(long patientId, String uhid, String name, String phone, String email,
                                                   String password, long actor, HttpServletRequest req) {
        String username = "PAT-" + String.format(Locale.ROOT, "%08d", patientId);
        Long usernameExists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Long.class, username);
        if (usernameExists != null && usernameExists > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A login ID already exists for this patient record. Contact the administrator.");
        jdbc.update("INSERT INTO users(username,email,phone,full_name,password_hash,role,account_status,password_changed_at) VALUES(?,?,?,?,?,'PATIENT','ACTIVE',UTC_TIMESTAMP())",
                username, email, phone, name, passwordEncoder.encode(password));
        long userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        int linked = jdbc.update("UPDATE patients SET user_id=? WHERE patient_id=? AND user_id IS NULL", userId, patientId);
        if (linked != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Patient login could not be linked. No patient data was saved.");
        audit.record(actor, "PATIENT_ACCOUNT_CREATED", "users", userId,
                Map.of("patientId", patientId, "uhid", uhid, "username", username), req);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("message", "Patient registered and login account created successfully.");
        result.put("patientId", patientId);
        result.put("userId", userId);
        result.put("loginId", String.valueOf(userId));
        result.put("username", username);
        result.put("uhid", uhid);
        return result;
    }

    private void ensureEmailAvailable(String email) {
        if (email == null) return;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email)=LOWER(?)", Long.class, email);
        if (count != null && count > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already linked to another user account. Use a different email or leave it blank.");
    }

    private static String normalizedOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validatePatientPassword(String password, Map<String,Object> body) {
        if (password.length() < 10) throw new IllegalArgumentException("Patient password must be at least 10 characters.");
        String confirmation = text(body, "confirmPassword");
        if (confirmation == null || !password.equals(confirmation)) throw new IllegalArgumentException("Password and confirm password do not match.");
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createRequest(@RequestBody Map<String,Object>b,HttpServletRequest req){
        long patientId=number(b,"patientId"),actor=actorId();int esi=(int)number(b,"esiLevel");
        if(esi<1||esi>5)throw new IllegalArgumentException("ESI level must be between 1 and 5.");
        String category=required(b,"requestedCategory").toUpperCase(Locale.ROOT);
        String reason=required(b,"reason");
        jdbc.queryForObject("SELECT patient_id FROM patients WHERE patient_id=? AND record_status='ACTIVE'",Long.class,patientId);
        Long doctorId=b.get("doctorId")==null?null:number(b,"doctorId");
        jdbc.update("INSERT INTO admission_requests(patient_id,requested_category,esi_level,reason,doctor_id,expected_stay_days,requires_oxygen,requires_ventilator,requires_monitor,isolation_required,clinical_isolation_verified,status,requested_by,verified_by,verified_at,requested_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'VERIFIED',?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                patientId,category,esi,reason,doctorId,b.get("expectedStayDays")==null?null:number(b,"expectedStayDays"),bool(b,"requiresOxygen"),bool(b,"requiresVentilator"),bool(b,"requiresMonitor"),bool(b,"isolationRequired"),bool(b,"clinicalIsolationVerified"),actor,actor);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        audit.record(actor,"ADMISSION_REQUEST_CREATED","admission_requests",id,Map.of("patientId",patientId,"esiLevel",esi),req);
        return Map.of("requestId",id,"message","Admission request created and verified.");
    }

    @GetMapping("/requests/queue")
    public List<Map<String,Object>> queue(){
        return jdbc.queryForList("SELECT r.request_id,r.patient_id,p.uhid,p.full_name patient_name,p.phone,p.gender,p.date_of_birth,TIMESTAMPDIFF(YEAR,p.date_of_birth,CURDATE()) age,r.requested_category,r.esi_level,e.label esi_label,e.emergency_flag,r.reason,r.expected_stay_days,r.requires_oxygen,r.requires_ventilator,r.requires_monitor,r.isolation_required,r.clinical_isolation_verified,r.status,r.requested_at,d.full_name doctor_name FROM admission_requests r JOIN patients p ON p.patient_id=r.patient_id JOIN esi_levels e ON e.esi_level=r.esi_level LEFT JOIN doctors d ON d.doctor_id=r.doctor_id WHERE r.status IN ('PENDING_VERIFICATION','VERIFIED','WAITING') ORDER BY r.esi_level ASC, r.requested_at ASC, CASE WHEN p.date_of_birth>=UTC_DATE()-INTERVAL 28 DAY THEN 0 WHEN TIMESTAMPDIFF(YEAR,p.date_of_birth,UTC_DATE())>=60 THEN 1 ELSE 2 END ASC,r.requested_at ASC");
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/requests/{id}/verify")
    public Map<String,Object> verify(@PathVariable long id,@RequestBody(required=false) Map<String,Object> body,HttpServletRequest req){
        int esi=body==null||body.get("esiLevel")==null?5:Integer.parseInt(String.valueOf(body.get("esiLevel")));
        if(esi<1||esi>5)throw new IllegalArgumentException("ESI level must be between 1 and 5.");
        boolean isolationVerified=body!=null&&Boolean.parseBoolean(String.valueOf(body.getOrDefault("clinicalIsolationVerified",false)));
        int n=jdbc.update("UPDATE admission_requests SET status='VERIFIED',esi_level=?,clinical_isolation_verified=?,verified_by=?,verified_at=UTC_TIMESTAMP() WHERE request_id=? AND status='PENDING_VERIFICATION'",esi,isolationVerified,actorId(),id);
        if(n==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Request is not pending verification.");
        audit.record(actorId(),"ADMISSION_REQUEST_VERIFIED","admission_requests",id,Map.of("esiLevel",esi,"clinicalIsolationVerified",isolationVerified),req);
        return Map.of("message","Admission request verified.");
    }

    /** Cancel a live admission request without deleting its clinical/audit history. */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/requests/{id}/cancel")
    public Map<String,Object> cancelRequest(@PathVariable long id,@RequestBody(required=false) Map<String,Object> body,HttpServletRequest req){
        String reason=body==null?null:text(body,"reason");
        if(reason==null||reason.isBlank()) throw new IllegalArgumentException("Cancellation reason is required.");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT status FROM admission_requests WHERE request_id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Admission request not found.");
        String status=String.valueOf(rows.get(0).get("status"));
        if(!Set.of("PENDING_VERIFICATION","VERIFIED","WAITING").contains(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending, verified or waiting requests can be cancelled. Allocated requests must follow the discharge workflow.");
        int changed=jdbc.update("UPDATE admission_requests SET status='CANCELLED' WHERE request_id=? AND status=?",id,status);
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Request changed concurrently. Refresh the queue and retry.");
        jdbc.update("UPDATE waiting_list SET status='CANCELLED' WHERE request_id=? AND status IN ('WAITING','OFFERED')",id);
        audit.record(actorId(),"ADMISSION_REQUEST_CANCELLED","admission_requests",id,Map.of("previousStatus",status,"reason",reason.trim()),req);
        return Map.of("message","Admission request cancelled. Its history has been retained.");
    }

    @GetMapping("/requests/{id}/suggestions")
    public List<Map<String,Object>> suggestions(@PathVariable long id){return allocation.suggestions(id);}

    @PostMapping("/requests/{id}/allocate")
    public Map<String,Object> allocate(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        if(b.get("bedId")==null)throw new IllegalArgumentException("bedId is required.");
        return allocation.allocate(id,number(b,"bedId"),actorId(),required(b,"reason"),req);
    }

    @PostMapping("/requests/{id}/waitlist")
    public Map<String,Object> waitlist(@PathVariable long id,HttpServletRequest req){return allocation.addToWaitingList(id,actorId(),req);}

    @GetMapping("/waiting-list")
    public List<Map<String,Object>> waiting(){
        return jdbc.queryForList("SELECT wl.wait_id,wl.request_id,wl.priority_score,wl.added_at,wl.status,r.esi_level,r.requested_category,p.patient_id,p.uhid,p.full_name patient_name,p.phone FROM waiting_list wl JOIN admission_requests r ON r.request_id=wl.request_id JOIN patients p ON p.patient_id=r.patient_id WHERE wl.status IN ('WAITING','OFFERED') AND r.status IN ('WAITING','VERIFIED') ORDER BY r.esi_level,wl.added_at");
    }

    @GetMapping("/admissions/active")
    public List<Map<String,Object>> activeAdmissions(){
        return jdbc.queryForList("SELECT a.allocation_id,a.patient_id,a.bed_id,a.admit_time,a.expected_discharge,p.uhid,p.full_name patient_name,p.phone,b.bed_code,w.ward_name,w.ward_type,b.daily_charge FROM allocations a JOIN patients p ON p.patient_id=a.patient_id JOIN beds b ON b.bed_id=a.bed_id JOIN wards w ON w.ward_id=b.ward_id WHERE a.status='ACTIVE' ORDER BY a.admit_time");
    }

    @PutMapping("/allocations/{id}/transfer")
    public Map<String,Object> transfer(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){return allocation.transfer(id,number(b,"bedId"),actorId(),required(b,"reason"),req);}

    @PutMapping("/allocations/{id}/discharge")
    public Map<String,Object> discharge(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){return allocation.discharge(id,actorId(),value(b,"dischargeType","RECOVERED"),required(b,"reason"),req);}

    @PostMapping("/beds/{id}/clean")
    public Map<String,Object> clean(@PathVariable long id,HttpServletRequest req){return allocation.markClean(id,actorId(),req);}

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> reserve(@RequestBody Map<String,Object>b,HttpServletRequest req){
        long bedId=number(b,"bedId"),patientId=number(b,"patientId"),requestId=number(b,"requestId"),actor=actorId();
        Long relation=jdbc.queryForObject("SELECT COUNT(*) FROM admission_requests WHERE request_id=? AND patient_id=? AND status IN ('VERIFIED','WAITING')",Long.class,requestId,patientId);
        if(relation==null||relation==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Reservation must reference a verified request for the same patient.");
        Map<String,Object> bed=jdbc.queryForMap("SELECT status FROM beds WHERE bed_id=? FOR UPDATE",bedId);
        if(!"AVAILABLE".equals(String.valueOf(bed.get("status"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"Bed is not available.");
        jdbc.update("INSERT INTO reservations(bed_id,patient_id,request_id,reserved_by,reserved_at,expires_at,status) VALUES(?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP()+INTERVAL 2 HOUR,'ACTIVE')",bedId,patientId,requestId,actor);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("UPDATE beds SET status='RESERVED' WHERE bed_id=?",bedId);
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(?,'AVAILABLE','RESERVED',?,?,UTC_TIMESTAMP())",bedId,"Reserved for admission request #"+requestId,actor);
        audit.record(actor,"BED_RESERVED","reservations",id,Map.of("bedId",bedId,"requestId",requestId),req);
        return Map.of("reservationId",id,"expiresInMinutes",120,"message","Bed reserved for two hours.");
    }

    @PostMapping("/reservations/{id}/confirm")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> confirmReservation(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req){
        Map<String,Object> reservation=jdbc.queryForMap("SELECT reservation_id,bed_id,request_id,status,expires_at FROM reservations WHERE reservation_id=? FOR UPDATE",id);
        if(!"ACTIVE".equals(String.valueOf(reservation.get("status"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"Reservation is no longer active.");
        java.sql.Timestamp expiry=(java.sql.Timestamp)reservation.get("expires_at");
        if(expiry.toLocalDateTime().isBefore(java.time.LocalDateTime.now()))throw new ResponseStatusException(HttpStatus.GONE,"Reservation has expired.");
        long bedId=((Number)reservation.get("bed_id")).longValue();
        jdbc.update("UPDATE reservations SET status='CONFIRMED' WHERE reservation_id=?",id);
        jdbc.update("UPDATE beds SET status='AVAILABLE' WHERE bed_id=? AND status='RESERVED'",bedId);
        jdbc.update("INSERT INTO bed_status_history(bed_id,old_status,new_status,reason,changed_by,changed_at) VALUES(?,'RESERVED','AVAILABLE','Reservation confirmed for allocation',?,UTC_TIMESTAMP())",bedId,actorId());
        return allocation.allocate(((Number)reservation.get("request_id")).longValue(),bedId,actorId(),required(b,"reason"),req);
    }

    @GetMapping("/notifications")
    public List<Map<String,Object>> notifications(){return jdbc.queryForList("SELECT notification_id,title,message,notification_type,is_read,created_at FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 100",actorId());}

    @PatchMapping("/notifications/{id}/read")
    public Map<String,Object> markNotificationRead(@PathVariable long id){jdbc.update("UPDATE notifications SET is_read=TRUE,read_at=UTC_TIMESTAMP() WHERE notification_id=? AND user_id=?",id,actorId());return Map.of("message","Notification marked as read.");}

    private long actorId(){return jdbc.queryForObject("SELECT user_id FROM users WHERE username=?",Long.class,RequestUser.username());}
    private long count(String s){Long x=jdbc.queryForObject(s,Long.class);return x==null?0:x;}
    private static String required(Map<String,Object>b,String k){String v=text(b,k);if(v==null||v.isBlank())throw new IllegalArgumentException(k+" is required.");return v.trim();}
    private static String text(Map<String,Object>b,String k){Object v=b.get(k);return v==null?null:String.valueOf(v);}
    private static String value(Map<String,Object>b,String k,String d){String v=text(b,k);return v==null||v.isBlank()?d:v;}
    private static long number(Map<String,Object>b,String k){Object v=b.get(k);if(v==null)throw new IllegalArgumentException(k+" is required.");return Long.parseLong(String.valueOf(v));}
    private static boolean bool(Map<String,Object>b,String k){Object v=b.get(k);return v!=null&&(Boolean.parseBoolean(String.valueOf(v))||"1".equals(String.valueOf(v)));}
}

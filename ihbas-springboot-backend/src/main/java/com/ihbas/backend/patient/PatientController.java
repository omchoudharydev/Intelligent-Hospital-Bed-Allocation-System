package com.ihbas.backend.patient;

import com.ihbas.backend.common.RequestUser;
import com.ihbas.backend.common.AuditService;
import com.ihbas.backend.common.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/patient")
public class PatientController {
    private final JdbcTemplate jdbc;private final AuditService audit;private final NotificationService notifications;
    public PatientController(JdbcTemplate jdbc,AuditService audit,NotificationService notifications){this.jdbc=jdbc;this.audit=audit;this.notifications=notifications;}

    @GetMapping("/profile")
    public Map<String,Object> profile(){
        return jdbc.queryForMap("SELECT patient_id,uhid,full_name,date_of_birth,TIMESTAMPDIFF(YEAR,date_of_birth,CURDATE()) age,gender,blood_group,phone,email,address,emergency_contact_name,emergency_contact_phone,allergies,chronic_conditions,insurance_type,record_status FROM patients WHERE user_id=?",userId());
    }

    @GetMapping("/admission")
    public Map<String,Object> admission(){
        long pid=patientId();
        List<Map<String,Object>> active=jdbc.queryForList("SELECT a.allocation_id,a.admit_time,a.expected_discharge,p.uhid,p.full_name,b.bed_code,b.daily_charge,b.status bed_status,w.ward_name,w.ward_type,w.floor_label FROM allocations a JOIN patients p ON p.patient_id=a.patient_id JOIN beds b ON b.bed_id=a.bed_id JOIN wards w ON w.ward_id=b.ward_id WHERE a.patient_id=? AND a.status='ACTIVE' ORDER BY a.admit_time DESC LIMIT 1",pid);
        List<Map<String,Object>> requests=jdbc.queryForList("SELECT request_id,requested_category,esi_level,reason,status,requested_at FROM admission_requests WHERE patient_id=? ORDER BY requested_at DESC LIMIT 20",pid);
        return Map.of("activeAdmission",active.isEmpty()?Map.of():active.get(0),"requests",requests);
    }

    @PostMapping("/admission-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> requestAdmission(@RequestBody Map<String,Object>b,HttpServletRequest http){
        long pid=patientId(),uid=userId();int esi=5; // Patient-submitted requests are untriaged until clinical staff verify them.
        String cat=required(b,"requestedCategory").toUpperCase(Locale.ROOT);
        String reason=required(b,"reason");
        jdbc.update("INSERT INTO admission_requests(patient_id,requested_category,esi_level,reason,expected_stay_days,requires_oxygen,requires_ventilator,requires_monitor,isolation_required,clinical_isolation_verified,status,requested_by,requested_at) VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING_VERIFICATION',?,UTC_TIMESTAMP())",
                pid,cat,esi,reason,b.get("expectedStayDays")==null?null:number(b,"expectedStayDays"),bool(b,"requiresOxygen"),bool(b,"requiresVentilator"),bool(b,"requiresMonitor"),bool(b,"isolationRequired"),false,uid);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        audit.record(uid,"PATIENT_ADMISSION_REQUESTED","admission_requests",id,Map.of("patientId",pid,"category",cat),http);
        notifications.createForRole("RECEPTIONIST","Patient admission request","A new online admission request is waiting for verification.","ADMISSION_REQUEST");
        return Map.of("requestId",id,"status","PENDING_VERIFICATION","message","Admission request submitted. Receptionist will verify it.");
    }

    @GetMapping("/history")
    public List<Map<String,Object>> history(){
        return jdbc.queryForList("SELECT a.allocation_id,a.admit_time,a.expected_discharge,a.discharge_time,a.discharge_type,a.status,b.bed_code,w.ward_name,w.ward_type FROM allocations a JOIN beds b ON b.bed_id=a.bed_id JOIN wards w ON w.ward_id=b.ward_id WHERE a.patient_id=? ORDER BY a.admit_time DESC",patientId());
    }

    @GetMapping("/bills")
    public List<Map<String,Object>> bills(){
        return jdbc.queryForList("SELECT i.invoice_id,i.invoice_no,i.invoice_date,i.due_date,i.currency,v.total_amount,v.invoice_status,v.amount_paid,v.amount_refunded,v.net_amount_paid,v.balance_due FROM v_invoice_balances v JOIN invoices i ON i.invoice_id=v.invoice_id WHERE v.patient_id=? ORDER BY i.invoice_date DESC",patientId());
    }

    @GetMapping("/bills/{id}")
    public Map<String,Object> bill(@PathVariable long id){
        Map<String,Object> invoice=jdbc.queryForMap("SELECT v.*,i.invoice_date,i.due_date,i.currency FROM v_invoice_balances v JOIN invoices i ON i.invoice_id=v.invoice_id WHERE v.invoice_id=? AND v.patient_id=?",id,patientId());
        List<Map<String,Object>> items=jdbc.queryForList("SELECT description,quantity,unit_price,discount_amount,tax_rate,line_subtotal,tax_amount,line_total,service_start,service_end FROM invoice_items WHERE invoice_id=? ORDER BY invoice_item_id",id);
        List<Map<String,Object>> payments=jdbc.queryForList("SELECT receipt_no,amount,payment_method,status,transaction_ref,received_at FROM payments WHERE invoice_id=? ORDER BY received_at DESC",id);
        return Map.of("invoice",invoice,"items",items,"payments",payments);
    }

    @GetMapping("/bed-availability")
    public List<Map<String,Object>> bedAvailability(){
        return jdbc.queryForList("SELECT ward_name,ward_type,capacity,total_beds,available_beds,occupied_beds,unavailable_beds FROM v_ward_occupancy ORDER BY ward_name");
    }

    @GetMapping("/notifications")
    public List<Map<String,Object>> notifications(){
        return jdbc.queryForList("SELECT notification_id,title,message,notification_type,is_read,created_at FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 100",userId());
    }
    @PatchMapping("/notifications/{id}/read")
    public Map<String,Object> markRead(@PathVariable long id){jdbc.update("UPDATE notifications SET is_read=TRUE,read_at=UTC_TIMESTAMP() WHERE notification_id=? AND user_id=?",id,userId());return Map.of("message","Notification marked as read.");}

    private long userId(){Long id=jdbc.queryForObject("SELECT user_id FROM users WHERE username=?",Long.class,RequestUser.username());return id;}
    private long patientId(){List<Long> ids=jdbc.queryForList("SELECT patient_id FROM patients WHERE user_id=? AND record_status='ACTIVE'",Long.class,userId());if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Patient profile is not linked to this account.");return ids.get(0);}
    private static String required(Map<String,Object>b,String k){String s=b.get(k)==null?null:String.valueOf(b.get(k));if(s==null||s.isBlank())throw new IllegalArgumentException(k+" is required.");return s.trim();}
    private static long number(Map<String,Object>b,String k){return Long.parseLong(required(b,k));}
    private static boolean bool(Map<String,Object>b,String k){Object o=b.get(k);return o!=null&&(Boolean.parseBoolean(String.valueOf(o))||"1".equals(String.valueOf(o)));}
}

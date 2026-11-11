package com.ihbas.backend.billing;

import com.ihbas.backend.common.AuditService;
import com.ihbas.backend.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/receptionist/billing")
public class BillingController {
    private final JdbcTemplate jdbc;private final AuditService audit;
    public BillingController(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}

    @GetMapping("/invoices")
    public List<Map<String,Object>> invoices(@RequestParam(required=false) String q,@RequestParam(required=false) String status){
        String term=q==null?"":q.trim();
        return jdbc.queryForList("SELECT v.invoice_id,v.invoice_no,v.patient_id,p.uhid,p.full_name patient_name,p.phone,i.invoice_date,i.due_date,v.total_amount,v.invoice_status,v.amount_paid,v.balance_due FROM v_invoice_balances v JOIN invoices i ON i.invoice_id=v.invoice_id JOIN patients p ON p.patient_id=v.patient_id WHERE (?='' OR v.invoice_no LIKE CONCAT('%',?,'%') OR p.uhid LIKE CONCAT('%',?,'%') OR p.full_name LIKE CONCAT('%',?,'%')) AND (? IS NULL OR v.invoice_status=?) ORDER BY i.invoice_date DESC LIMIT 300",term,term,term,term,status,status);
    }

    @GetMapping("/invoices/{id}")
    public Map<String,Object> invoice(@PathVariable long id){
        Map<String,Object> head=jdbc.queryForMap("SELECT v.*,i.invoice_date,i.due_date,i.currency,p.uhid,p.full_name patient_name,p.phone,p.address FROM v_invoice_balances v JOIN invoices i ON i.invoice_id=v.invoice_id JOIN patients p ON p.patient_id=v.patient_id WHERE v.invoice_id=?",id);
        return Map.of("invoice",head,"items",jdbc.queryForList("SELECT * FROM invoice_items WHERE invoice_id=? ORDER BY invoice_item_id",id),"payments",jdbc.queryForList("SELECT * FROM payments WHERE invoice_id=? ORDER BY received_at DESC",id));
    }

    @PostMapping("/invoices/{id}/payments")
    @Transactional
    public Map<String,Object> payment(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        BigDecimal amount=new BigDecimal(required(b,"amount"));
        if(amount.signum()<=0)throw new IllegalArgumentException("Payment amount must be greater than zero.");
        jdbc.queryForObject("SELECT invoice_id FROM invoices WHERE invoice_id=? FOR UPDATE",Long.class,id);
        Map<String,Object> inv=jdbc.queryForMap("SELECT invoice_status,balance_due FROM v_invoice_balances WHERE invoice_id=?",id);
        BigDecimal balance=(BigDecimal)inv.get("balance_due");
        if(amount.compareTo(balance)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment exceeds the outstanding balance.");
        String method=value(b,"paymentMethod","CASH").toUpperCase(Locale.ROOT);
        if(!Set.of("CASH","UPI","CARD","BANK_TRANSFER","INSURANCE","OTHER").contains(method))throw new IllegalArgumentException("Invalid payment method.");
        String receipt="RCPT-"+UUID.randomUUID().toString().substring(0,10).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO payments(invoice_id,receipt_no,amount,payment_method,status,transaction_ref,received_by,received_at,notes) VALUES(?,?,?,?,'SUCCESS',?,?,UTC_TIMESTAMP(),?)",id,receipt,amount,method,text(b,"transactionRef"),actorId(),text(b,"notes"));
        BigDecimal newBalance=balance.subtract(amount);
        jdbc.update("UPDATE invoices SET status=? WHERE invoice_id=? AND status<>'VOID'",newBalance.signum()==0?"PAID":"PARTIALLY_PAID",id);
        audit.record(actorId(),"PAYMENT_RECEIVED","payments",receipt,Map.of("invoiceId",id,"amount",amount,"method",method),req);
        return Map.of("receiptNo",receipt,"balanceDue",newBalance,"message","Payment recorded.");
    }

    @GetMapping("/discharge-summary/{allocationId}")
    public Map<String,Object> dischargeSummary(@PathVariable long allocationId){
        return jdbc.queryForMap("SELECT a.allocation_id,a.admit_time,a.discharge_time,a.discharge_type,p.uhid,p.full_name,p.date_of_birth,p.gender,p.blood_group,p.phone,b.bed_code,b.daily_charge,w.ward_name,w.ward_type,iv.invoice_no,iv.total_amount,iv.balance_due FROM allocations a JOIN patients p ON p.patient_id=a.patient_id JOIN beds b ON b.bed_id=a.bed_id JOIN wards w ON w.ward_id=b.ward_id LEFT JOIN v_invoice_balances iv ON iv.allocation_id=a.allocation_id WHERE a.allocation_id=?",allocationId);
    }

    @GetMapping("/invoices.csv")
    public org.springframework.http.ResponseEntity<String> csv(){
        var rows=invoices(null,null);StringBuilder s=new StringBuilder("Invoice,Patient UHID,Patient,Date,Status,Total,Paid,Balance\r\n");
        for(var r:rows)s.append(quote(r.get("invoice_no"))).append(',').append(quote(r.get("uhid"))).append(',').append(quote(r.get("patient_name"))).append(',').append(quote(r.get("invoice_date"))).append(',').append(quote(r.get("invoice_status"))).append(',').append(r.get("total_amount")).append(',').append(r.get("amount_paid")).append(',').append(r.get("balance_due")).append("\r\n");
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=ihbas-invoices.csv").header("Content-Type","text/csv; charset=utf-8").body(s.toString());
    }
    private long actorId(){return jdbc.queryForObject("SELECT user_id FROM users WHERE username=?",Long.class,RequestUser.username());}
    private static String required(Map<String,Object>b,String k){String s=text(b,k);if(s==null||s.isBlank())throw new IllegalArgumentException(k+" is required.");return s.trim();}
    private static String text(Map<String,Object>b,String k){Object o=b.get(k);return o==null?null:String.valueOf(o);}
    private static String value(Map<String,Object>b,String k,String d){String s=text(b,k);return s==null||s.isBlank()?d:s;}
    private static String quote(Object o){return "\""+(o==null?"":String.valueOf(o).replace("\"","\"\""))+"\"";}
}

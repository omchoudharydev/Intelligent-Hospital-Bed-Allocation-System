package com.ihbas.backend.gateway;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/patient/bills")
public class PatientRazorpayPaymentController {
    private final RazorpayGatewayService gateway;
    public PatientRazorpayPaymentController(RazorpayGatewayService gateway) { this.gateway = gateway; }

    @PostMapping("/{invoiceId}/razorpay/orders")
    public Map<String, Object> createOrder(@PathVariable long invoiceId, @RequestBody(required = false) Map<String, Object> body) {
        BigDecimal amount = body == null || body.get("amount") == null ? null : new BigDecimal(String.valueOf(body.get("amount")));
        return gateway.createPatientInvoiceOrder(invoiceId, amount);
    }

    @PostMapping("/razorpay/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> body) {
        return gateway.verifyPatientCheckout(Long.parseLong(required(body, "paymentOrderId")),
                required(body, "razorpayPaymentId"), required(body, "razorpaySignature"));
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(key + " is required.");
        return String.valueOf(value).trim();
    }
}

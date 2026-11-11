package com.ihbas.backend.gateway;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/receptionist/billing/razorpay")
public class RazorpayPaymentController {
    private final RazorpayGatewayService gateway;
    public RazorpayPaymentController(RazorpayGatewayService gateway) { this.gateway = gateway; }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody(required = false) Map<String, Object> body) {
        Long invoiceId = body == null || body.get("invoiceId") == null ? null : Long.valueOf(String.valueOf(body.get("invoiceId")));
        if (invoiceId == null || invoiceId <= 0) throw new IllegalArgumentException("A valid invoiceId is required.");
        BigDecimal amount = null;
        if (body.get("amount") != null && !String.valueOf(body.get("amount")).isBlank()) amount = new BigDecimal(String.valueOf(body.get("amount")));
        return gateway.createInvoiceOrder(invoiceId, amount);
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> body) {
        long localOrderId = Long.parseLong(required(body, "paymentOrderId"));
        return gateway.verifyCheckout(localOrderId, required(body, "razorpayPaymentId"), required(body, "razorpaySignature"));
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(key + " is required.");
        return String.valueOf(value).trim();
    }
}


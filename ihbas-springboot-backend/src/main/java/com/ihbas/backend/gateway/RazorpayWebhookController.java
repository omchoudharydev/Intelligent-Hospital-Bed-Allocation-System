package com.ihbas.backend.gateway;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks/razorpay")
public class RazorpayWebhookController {
    private final RazorpayGatewayService gateway;
    public RazorpayWebhookController(RazorpayGatewayService gateway) { this.gateway = gateway; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> webhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "x-razorpay-event-id", required = false) String eventId,
            @RequestBody byte[] rawBody) {
        gateway.validateWebhookSignature(rawBody, signature);
        gateway.processWebhook(eventId, rawBody);
        return Map.of("received", true);
    }
}

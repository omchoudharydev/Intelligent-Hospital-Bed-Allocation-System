package com.ihbas.backend.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihbas.backend.common.RequestUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class RazorpayGatewayService {

    private static final String CURRENCY_INR = "INR";
    private static final String STATUS_CAPTURED = "CAPTURED";
    private static final String STATUS_REVIEW_REQUIRED = "REVIEW_REQUIRED";

    private final JdbcTemplate jdbc;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final boolean enabled;
    private final boolean testMode;

    public RazorpayGatewayService(
            JdbcTemplate jdbc,
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${app.razorpay.key-id:}") String keyId,
            @Value("${app.razorpay.key-secret:}") String keySecret,
            @Value("${app.razorpay.webhook-secret:}") String webhookSecret,
            @Value("${app.razorpay.enabled:false}") boolean enabled,
            @Value("${app.razorpay.test-mode:true}") boolean testMode,
            @Value("${app.razorpay.api-base-url:https://api.razorpay.com/v1}") String apiBaseUrl) {

        this.jdbc = jdbc;
        this.mapper = mapper;
        this.keyId = clean(keyId);
        this.keySecret = clean(keySecret);
        this.webhookSecret = clean(webhookSecret);
        this.enabled = enabled;
        this.testMode = testMode;

        validateApiBaseUrl(apiBaseUrl);

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        this.client = builder
                .baseUrl(apiBaseUrl.trim().replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .build();
    }

    @Transactional
    public Map<String, Object> createInvoiceOrder(long invoiceId, BigDecimal requestedAmount) {
        return createInvoiceOrderInternal(invoiceId, requestedAmount, false);
    }

    @Transactional
    public Map<String, Object> createPatientInvoiceOrder(long invoiceId, BigDecimal requestedAmount) {
        return createInvoiceOrderInternal(invoiceId, requestedAmount, true);
    }

    private Map<String, Object> createInvoiceOrderInternal(
            long invoiceId,
            BigDecimal requestedAmount,
            boolean patientOnly) {

        if (invoiceId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invoice ID.");
        }
        requireCheckoutConfigured();

        long actorId = currentUserId();
        Map<String, Object> invoice;
        try {
            invoice = jdbc.queryForMap(
                    "SELECT i.invoice_id,i.invoice_no,i.currency,i.status, " +
                    "p.patient_id,p.user_id AS patient_user_id,p.full_name,p.email,p.phone, " +
                    "(i.total_amount " +
                    "- COALESCE((SELECT SUM(pm.amount) FROM payments pm " +
                    "WHERE pm.invoice_id=i.invoice_id AND pm.status='SUCCESS'),0) " +
                    "+ COALESCE((SELECT SUM(rf.amount) FROM payment_refunds rf " +
                    "JOIN payments pm2 ON pm2.payment_id=rf.payment_id " +
                    "WHERE pm2.invoice_id=i.invoice_id AND rf.status='PROCESSED'),0)) AS balance_due " +
                    "FROM invoices i JOIN patients p ON p.patient_id=i.patient_id " +
                    "WHERE i.invoice_id=? FOR UPDATE",
                    invoiceId);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found.");
        }

        if (patientOnly) {
            Object patientUserId = invoice.get("patient_user_id");
            if (!(patientUserId instanceof Number number) || number.longValue() != actorId) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found for this patient account.");
            }
        }

        jdbc.update(
                "UPDATE payment_gateway_orders " +
                "SET status='EXPIRED',failure_reason='Local payment window expired',updated_at=UTC_TIMESTAMP() " +
                "WHERE invoice_id=? AND status IN ('CREATED','AUTHORIZED') " +
                "AND created_at<UTC_TIMESTAMP()-INTERVAL 30 MINUTE",
                invoiceId);

        Integer activeOrderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_gateway_orders " +
                "WHERE invoice_id=? AND status IN ('CREATED','AUTHORIZED') " +
                "AND created_at>=UTC_TIMESTAMP()-INTERVAL 30 MINUTE",
                Integer.class,
                invoiceId);

        if (activeOrderCount != null && activeOrderCount > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An online payment order is already open for this invoice. Complete it or wait 30 minutes before creating another.");
        }

        String invoiceStatus = String.valueOf(invoice.get("status"));
        if ("VOID".equalsIgnoreCase(invoiceStatus) || "DRAFT".equalsIgnoreCase(invoiceStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only issued invoices can be paid.");
        }

        String currency = String.valueOf(invoice.get("currency")).toUpperCase(Locale.ROOT);
        if (!CURRENCY_INR.equals(currency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Razorpay checkout is currently configured for INR invoices only.");
        }

        BigDecimal balance = asMoney(invoice.get("balance_due"));
        BigDecimal amount = requestedAmount == null ? balance : requestedAmount;
        if (amount == null || amount.signum() <= 0 || amount.compareTo(balance) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment amount must be positive and cannot exceed the outstanding balance.");
        }

        final long amountPaise;
        try {
            amountPaise = amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must have at most two decimal places.");
        }
        if (amountPaise < 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay orders must be at least ₹1.00.");
        }

        String localReceipt = "IHBAS-" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 24).toUpperCase(Locale.ROOT);

        Map<String, Object> notes = Map.of(
                "ihbas_invoice_id", String.valueOf(invoiceId),
                "ihbas_receipt", localReceipt);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", amountPaise);
        payload.put("currency", currency);
        payload.put("receipt", localReceipt);
        payload.put("notes", notes);

        JsonNode remoteOrder = razorpayPost("/orders", payload);
        String remoteOrderId = remoteOrder == null ? "" : remoteOrder.path("id").asText("");
        if (!remoteOrderId.matches("^order_[A-Za-z0-9]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay did not return a valid order ID.");
        }

        jdbc.update(
                "INSERT INTO payment_gateway_orders " +
                "(invoice_id,created_by,local_receipt,razorpay_order_id,amount,amount_paise,currency,status,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,'CREATED',UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                invoiceId, actorId, localReceipt, remoteOrderId, amount, amountPaise, currency);

        Long localOrderId = jdbc.queryForObject(
                "SELECT gateway_order_id FROM payment_gateway_orders WHERE razorpay_order_id=?",
                Long.class,
                remoteOrderId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paymentOrderId", localOrderId);
        response.put("razorpayOrderId", remoteOrderId);
        response.put("keyId", keyId);
        response.put("amount", amountPaise);
        response.put("currency", currency);
        response.put("invoiceNo", invoice.get("invoice_no"));
        response.put("patientName", invoice.get("full_name"));
        response.put("email", invoice.get("email"));
        response.put("phone", invoice.get("phone"));
        response.put("testMode", testMode);
        return response;
    }

    @Transactional
    public Map<String, Object> verifyCheckout(long paymentOrderId, String paymentId, String checkoutSignature) {
        return verifyCheckoutInternal(paymentOrderId, paymentId, checkoutSignature, false);
    }

    @Transactional
    public Map<String, Object> verifyPatientCheckout(long paymentOrderId, String paymentId, String checkoutSignature) {
        return verifyCheckoutInternal(paymentOrderId, paymentId, checkoutSignature, true);
    }

    private Map<String, Object> verifyCheckoutInternal(
            long paymentOrderId,
            String paymentId,
            String checkoutSignature,
            boolean patientOnly) {

        requireCheckoutConfigured();
        if (paymentOrderId <= 0 || !isValidPaymentId(paymentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay payment details.");
        }
        if (checkoutSignature == null || !checkoutSignature.matches("(?i)^[0-9a-f]{64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay payment signature is invalid.");
        }

        Map<String, Object> order = findOrder(paymentOrderId, true);
        long actorId = currentUserId();

        if (((Number) order.get("created_by")).longValue() != actorId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This payment order belongs to another account.");
        }

        if (patientOnly) {
            Long ownerId = jdbc.queryForObject(
                    "SELECT p.user_id FROM invoices i " +
                    "JOIN patients p ON p.patient_id=i.patient_id WHERE i.invoice_id=?",
                    Long.class,
                    order.get("invoice_id"));
            if (ownerId == null || ownerId.longValue() != actorId) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found for this patient account.");
            }
        }

        String expectedSignature = hmacHex(
                String.valueOf(order.get("razorpay_order_id")) + "|" + paymentId,
                keySecret);
        if (!constantTimeEquals(expectedSignature, checkoutSignature.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay payment signature is invalid.");
        }

        String existingStatus = String.valueOf(order.get("status"));
        if (STATUS_CAPTURED.equalsIgnoreCase(existingStatus)) {
            if (!paymentId.equals(String.valueOf(order.get("razorpay_payment_id")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order was already settled with a different payment ID.");
            }
            return Map.of("message", "Payment already confirmed.", "status", "SUCCESS");
        }
        if (STATUS_REVIEW_REQUIRED.equalsIgnoreCase(existingStatus)) {
            return Map.of("message", "This payment requires manual reconciliation.", "status", STATUS_REVIEW_REQUIRED);
        }

        JsonNode payment = razorpayGet("/payments/" + paymentId);
        verifyPaymentEntity(payment, order, paymentId);

        String status = payment.path("status").asText("");
        if ("captured".equalsIgnoreCase(status) || payment.path("captured").asBoolean(false)) {
            boolean settled = settleCapturedPayment(order, paymentId, payment.path("amount").asLong(-1));
            return settled
                    ? Map.of("message", "Payment captured and invoice updated.", "status", "SUCCESS")
                    : Map.of("message", "Razorpay captured the payment, but the invoice requires manual reconciliation. It was not credited twice.", "status", STATUS_REVIEW_REQUIRED);
        }

        if ("authorized".equalsIgnoreCase(status)) {
            jdbc.update(
                    "UPDATE payment_gateway_orders SET status='AUTHORIZED',razorpay_payment_id=?,updated_at=UTC_TIMESTAMP() " +
                    "WHERE gateway_order_id=? AND status<>'CAPTURED' AND status<>'REVIEW_REQUIRED'",
                    paymentId, paymentOrderId);
            return Map.of("message", "Payment is authorized but not yet captured. Invoice will update after capture confirmation.", "status", "PENDING");
        }

        if ("failed".equalsIgnoreCase(status)) {
            jdbc.update(
                    "UPDATE payment_gateway_orders SET status='FAILED',razorpay_payment_id=?,failure_reason=?,updated_at=UTC_TIMESTAMP() " +
                    "WHERE gateway_order_id=? AND status NOT IN ('CAPTURED','REVIEW_REQUIRED')",
                    paymentId, "Payment failed at Razorpay", paymentOrderId);
            return Map.of("message", "Razorpay reports that the payment failed.", "status", "FAILED");
        }

        return Map.of("message", "Payment status is not final yet. Wait for the Razorpay webhook or refresh the invoice.", "status", "PENDING");
    }

    /** Validate the exact raw request bytes before parsing or processing a webhook. */
    public void validateWebhookSignature(byte[] rawBody, String suppliedSignature) {
        if (webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Razorpay webhook secret is not configured.");
        }
        if (rawBody == null || rawBody.length == 0 || suppliedSignature == null
                || !suppliedSignature.matches("(?i)^[0-9a-f]{64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay webhook signature header or body.");
        }

        String expected = hmacHex(rawBody, webhookSecret);
        if (!constantTimeEquals(expected, suppliedSignature.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay webhook signature verification failed.");
        }
    }

    @Transactional
    public void processWebhook(String eventId, byte[] rawBody) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing or invalid Razorpay event ID.");
        }
        if (rawBody == null || rawBody.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty Razorpay webhook payload.");
        }

        final JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay webhook payload.");
        }

        String eventType = root.path("event").asText("unknown");
        String payloadHash = sha256Hex(rawBody);

        int inserted = jdbc.update(
                "INSERT IGNORE INTO payment_gateway_events " +
                "(gateway_event_id,event_type,payload_sha256,event_status,received_at) " +
                "VALUES(?,?,?,'RECEIVED',UTC_TIMESTAMP())",
                eventId, eventType, payloadHash);

        if (inserted == 0) {
            Map<String, Object> previous;
            try {
                previous = jdbc.queryForMap(
                        "SELECT payload_sha256,event_status FROM payment_gateway_events WHERE gateway_event_id=? FOR UPDATE",
                        eventId);
            } catch (EmptyResultDataAccessException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Razorpay event could not be reconciled.");
            }

            String previousHash = String.valueOf(previous.get("payload_sha256"));
            if (!constantTimeEquals(previousHash, payloadHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Razorpay event ID was reused with a different payload.");
            }
            if ("PROCESSED".equalsIgnoreCase(String.valueOf(previous.get("event_status")))) {
                return;
            }
        }

        if (eventType.startsWith("payment.")) {
            JsonNode payment = root.path("payload").path("payment").path("entity");
            if (payment.isMissingNode() || payment.isNull()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment entity is missing from Razorpay webhook.");
            }

            String remoteOrderId = payment.path("order_id").asText("");
            String paymentId = payment.path("id").asText("");

            if (!remoteOrderId.isBlank()) {
                Map<String, Object> order = findOrderByRazorpayId(remoteOrderId);
                if (order != null) {
                    verifyPaymentEntity(payment, order, paymentId);
                    long localOrderId = ((Number) order.get("gateway_order_id")).longValue();

                    switch (eventType) {
                        case "payment.captured" -> settleCapturedPayment(
                                order, paymentId, payment.path("amount").asLong(-1));
                        case "payment.authorized" -> jdbc.update(
                                "UPDATE payment_gateway_orders SET status='AUTHORIZED',razorpay_payment_id=?,updated_at=UTC_TIMESTAMP() " +
                                "WHERE gateway_order_id=? AND status NOT IN ('CAPTURED','REVIEW_REQUIRED')",
                                paymentId, localOrderId);
                        case "payment.failed" -> jdbc.update(
                                "UPDATE payment_gateway_orders SET status='FAILED',razorpay_payment_id=?,failure_reason='Payment failed at Razorpay',updated_at=UTC_TIMESTAMP() " +
                                "WHERE gateway_order_id=? AND status NOT IN ('CAPTURED','REVIEW_REQUIRED') " +
                                "AND (razorpay_payment_id IS NULL OR razorpay_payment_id=?)",
                                paymentId, localOrderId, paymentId);
                        default -> {
                            // Other payment.* events are recorded but do not change the invoice ledger.
                        }
                    }
                }
            }
        }

        jdbc.update(
                "UPDATE payment_gateway_events SET event_status='PROCESSED',processed_at=UTC_TIMESTAMP() " +
                "WHERE gateway_event_id=?",
                eventId);
    }

    /**
     * Credits a captured payment exactly once. The order and invoice rows are locked
     * before the ledger is changed; the payment transaction reference is also unique.
     */
    private boolean settleCapturedPayment(Map<String, Object> order, String paymentId, long amountPaise) {
        if (!isValidPaymentId(paymentId) || amountPaise <= 0) {
            return markReviewRequired(order, "Invalid captured payment details");
        }

        long localOrderId = ((Number) order.get("gateway_order_id")).longValue();
        Map<String, Object> lockedOrder = findOrder(localOrderId, true);
        String currentStatus = String.valueOf(lockedOrder.get("status"));

        if (STATUS_CAPTURED.equalsIgnoreCase(currentStatus)) {
            String settledPaymentId = String.valueOf(lockedOrder.get("razorpay_payment_id"));
            if (paymentId.equals(settledPaymentId)) {
                return true;
            }
            // Do not overwrite the already-settled ledger entry for a second payment ID.
            jdbc.update(
                    "UPDATE payment_gateway_orders SET failure_reason=?,updated_at=UTC_TIMESTAMP() WHERE gateway_order_id=?",
                    "Additional captured payment " + paymentId + " requires manual reconciliation", localOrderId);
            return false;
        }

        if (STATUS_REVIEW_REQUIRED.equalsIgnoreCase(currentStatus)) {
            return false;
        }

        long expectedAmountPaise = ((Number) lockedOrder.get("amount_paise")).longValue();
        if (amountPaise != expectedAmountPaise) {
            return markReviewRequired(lockedOrder, "Captured amount mismatch");
        }

        long invoiceId = ((Number) lockedOrder.get("invoice_id")).longValue();
        Map<String, Object> invoice;
        try {
            invoice = jdbc.queryForMap(
                    "SELECT total_amount,currency,status FROM invoices WHERE invoice_id=? FOR UPDATE",
                    invoiceId);
        } catch (EmptyResultDataAccessException ex) {
            return markReviewRequired(lockedOrder, "Invoice no longer exists");
        }

        String invoiceStatus = String.valueOf(invoice.get("status"));
        if ("VOID".equalsIgnoreCase(invoiceStatus) || "DRAFT".equalsIgnoreCase(invoiceStatus)) {
            return markReviewRequired(lockedOrder, "Invoice is not payable at settlement time");
        }
        if (!String.valueOf(lockedOrder.get("currency")).equalsIgnoreCase(String.valueOf(invoice.get("currency")))) {
            return markReviewRequired(lockedOrder, "Invoice currency changed after order creation");
        }

        // A payment ID may be delivered through Checkout and webhook concurrently.
        // Reconcile against the unique transaction_ref before attempting another insert.
        List<Map<String, Object>> existingPayments = jdbc.queryForList(
                "SELECT invoice_id,amount,status FROM payments WHERE transaction_ref=?",
                paymentId);
        if (!existingPayments.isEmpty()) {
            Map<String, Object> existing = existingPayments.get(0);
            boolean samePayment = ((Number) existing.get("invoice_id")).longValue() == invoiceId
                    && asMoney(existing.get("amount")).compareTo(BigDecimal.valueOf(amountPaise, 2)) == 0
                    && "SUCCESS".equalsIgnoreCase(String.valueOf(existing.get("status")));
            if (samePayment) {
                jdbc.update(
                        "UPDATE payment_gateway_orders SET status='CAPTURED',razorpay_payment_id=?,failure_reason=NULL,updated_at=UTC_TIMESTAMP() WHERE gateway_order_id=?",
                        paymentId, localOrderId);
                return true;
            }
            return markReviewRequired(lockedOrder, "Payment ID is already recorded against a different ledger entry");
        }

        BigDecimal total = asMoney(invoice.get("total_amount"));
        BigDecimal paid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM payments WHERE invoice_id=? AND status='SUCCESS'",
                BigDecimal.class,
                invoiceId);
        BigDecimal refunded = jdbc.queryForObject(
                "SELECT COALESCE(SUM(r.amount),0) FROM payment_refunds r " +
                "JOIN payments p ON p.payment_id=r.payment_id " +
                "WHERE p.invoice_id=? AND r.status='PROCESSED'",
                BigDecimal.class,
                invoiceId);

        BigDecimal balance = total
                .subtract(paid == null ? BigDecimal.ZERO : paid)
                .add(refunded == null ? BigDecimal.ZERO : refunded);
        BigDecimal amount = BigDecimal.valueOf(amountPaise, 2);

        if (amount.compareTo(balance) > 0) {
            return markReviewRequired(lockedOrder, "Captured amount exceeds current invoice balance");
        }

        String receipt = "RCPT-" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        long createdBy = ((Number) lockedOrder.get("created_by")).longValue();

        jdbc.update(
                "INSERT INTO payments " +
                "(invoice_id,receipt_no,amount,payment_method,status,transaction_ref,received_by,received_at,notes) " +
                "VALUES(?,?,?,'RAZORPAY','SUCCESS',?,?,UTC_TIMESTAMP(),?)",
                invoiceId,
                receipt,
                amount,
                paymentId,
                createdBy,
                "Razorpay payment; order " + lockedOrder.get("razorpay_order_id"));

        BigDecimal newBalance = balance.subtract(amount);
        String newInvoiceStatus = newBalance.signum() == 0 ? "PAID" : "PARTIALLY_PAID";
        jdbc.update(
                "UPDATE invoices SET status=? WHERE invoice_id=? AND status<>'VOID'",
                newInvoiceStatus,
                invoiceId);

        jdbc.update(
                "UPDATE payment_gateway_orders SET status='CAPTURED',razorpay_payment_id=?,failure_reason=NULL,updated_at=UTC_TIMESTAMP() " +
                "WHERE gateway_order_id=?",
                paymentId,
                localOrderId);

        return true;
    }

    private boolean markReviewRequired(Map<String, Object> order, String reason) {
        long localOrderId = ((Number) order.get("gateway_order_id")).longValue();
        jdbc.update(
                "UPDATE payment_gateway_orders SET status='REVIEW_REQUIRED',failure_reason=?,updated_at=UTC_TIMESTAMP() " +
                "WHERE gateway_order_id=? AND status<>'CAPTURED'",
                reason,
                localOrderId);
        return false;
    }

    private void verifyPaymentEntity(JsonNode payment, Map<String, Object> order, String paymentId) {
        String expectedOrderId = String.valueOf(order.get("razorpay_order_id"));
        long expectedAmount = ((Number) order.get("amount_paise")).longValue();
        String expectedCurrency = String.valueOf(order.get("currency"));

        if (!isValidPaymentId(paymentId)
                || !paymentId.equals(payment.path("id").asText(""))
                || !expectedOrderId.equals(payment.path("order_id").asText(""))
                || payment.path("amount").asLong(-1) != expectedAmount
                || !expectedCurrency.equalsIgnoreCase(payment.path("currency").asText(""))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay payment does not match the locally created order.");
        }
    }

    private JsonNode razorpayPost(String path, Map<String, Object> payload) {
        try {
            JsonNode response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBasicAuth(keyId, keySecret))
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new IllegalStateException("Empty Razorpay response");
            }
            return response;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not create Razorpay order. Check the test account and server logs.");
        }
    }

    private JsonNode razorpayGet(String path) {
        try {
            JsonNode response = client.get()
                    .uri(path)
                    .headers(headers -> headers.setBasicAuth(keyId, keySecret))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new IllegalStateException("Empty Razorpay response");
            }
            return response;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not confirm payment with Razorpay. The invoice has not been marked paid.");
        }
    }

    private Map<String, Object> findOrder(long id, boolean forUpdate) {
        String sql = "SELECT gateway_order_id,invoice_id,created_by,razorpay_order_id,amount,amount_paise,currency,status,razorpay_payment_id " +
                "FROM payment_gateway_orders WHERE gateway_order_id=?" + (forUpdate ? " FOR UPDATE" : "");
        try {
            return jdbc.queryForMap(sql, id);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Razorpay order not found.");
        }
    }

    private Map<String, Object> findOrderByRazorpayId(String remoteId) {
        try {
            return jdbc.queryForMap(
                    "SELECT gateway_order_id,invoice_id,created_by,razorpay_order_id,amount,amount_paise,currency,status,razorpay_payment_id " +
                    "FROM payment_gateway_orders WHERE razorpay_order_id=? FOR UPDATE",
                    remoteId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private long currentUserId() {
        String username = RequestUser.username();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        try {
            Long userId = jdbc.queryForObject(
                    "SELECT user_id FROM users WHERE username=?",
                    Long.class,
                    username);
            if (userId == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user was not found.");
            }
            return userId;
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user was not found.");
        }
    }

    private void requireCheckoutConfigured() {
        if (!enabled || keyId.isBlank() || keySecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Razorpay is not configured. Set the Razorpay environment variables.");
        }
        if (testMode && !keyId.startsWith("rzp_test_")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Test mode is enabled, but the configured Razorpay key is not a test key.");
        }
        if (webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure RAZORPAY_WEBHOOK_SECRET before accepting online payments.");
        }
    }

    private static void validateApiBaseUrl(String apiBaseUrl) {
        try {
            URI uri = URI.create(clean(apiBaseUrl));
            boolean valid = "https".equalsIgnoreCase(uri.getScheme())
                    && "api.razorpay.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("/v1".equals(uri.getPath()) || "/v1/".equals(uri.getPath()))
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
            if (!valid) {
                throw new IllegalArgumentException("Razorpay API base URL must be https://api.razorpay.com/v1.");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid Razorpay API base URL. Use https://api.razorpay.com/v1.", ex);
        }
    }

    private static boolean isValidPaymentId(String id) {
        return id != null && id.matches("^pay_[A-Za-z0-9]{1,64}$");
    }

    private static BigDecimal asMoney(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal money ? money : new BigDecimal(String.valueOf(value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String hmacHex(String text, String secret) {
        return hmacHex(text.getBytes(StandardCharsets.UTF_8), secret);
    }

    private static String hmacHex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC is unavailable", ex);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

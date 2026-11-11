package com.ihbas.backend.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class Msg91SmsService {
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{8,15}$");
    private final RestClient client;
    private final boolean enabled;
    private final String authKey;
    private final String templateId;
    private final String otpVariable;
    private final String defaultCountryCode;

    public Msg91SmsService(
            RestClient.Builder builder,
            @Value("${app.msg91.enabled:false}") boolean enabled,
            @Value("${app.msg91.authkey:}") String authKey,
            @Value("${app.msg91.flow-template-id:}") String templateId,
            @Value("${app.msg91.otp-variable:OTP}") String otpVariable,
            @Value("${app.msg91.default-country-code:91}") String defaultCountryCode,
            @Value("${app.msg91.endpoint:https://control.msg91.com/api/v5/flow}") String endpoint) {
        if (endpoint == null || !endpoint.toLowerCase().startsWith("https://")) {
            throw new IllegalArgumentException("MSG91 endpoint must use HTTPS.");
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.client = builder.requestFactory(requestFactory).build();
        this.enabled = enabled;
        this.authKey = authKey;
        this.templateId = templateId;
        this.otpVariable = otpVariable;
        this.defaultCountryCode = defaultCountryCode;
        this.endpoint = endpoint;
    }

    private final String endpoint;

    public boolean isEnabled() { return enabled; }

    /** Sends an application-generated OTP through a pre-approved MSG91 Flow/DLT template. */
    public String sendOtp(String phone, String otp) {
        if (!enabled || authKey.isBlank() || templateId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OTP delivery is not configured. Set the MSG91 environment variables.");
        }
        String mobile = normalizeMobile(phone);
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("mobiles", mobile);
        recipient.put(otpVariable, otp);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("template_id", templateId);
        payload.put("short_url", "0");
        payload.put("recipients", List.of(recipient));

        try {
            JsonNode response = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("authkey", authKey)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                throw new IllegalStateException("MSG91 returned an empty response");
            }
            String type = response.path("type").asText("");
            if ("error".equalsIgnoreCase(type) || "failed".equalsIgnoreCase(type)
                    || (response.has("status") && response.get("status").isBoolean()
                    && !response.get("status").asBoolean())) {
                throw new IllegalStateException("MSG91 rejected the OTP request");
            }
            for (String field : List.of("request_id", "message_id", "messageId", "id")) {
                if (response.hasNonNull(field)) return response.get(field).asText();
            }
            if ("success".equalsIgnoreCase(type)) return null;
            throw new IllegalStateException("MSG91 did not confirm the OTP request");
        } catch (Exception ex) {
            // Never return provider credentials or provider response details to the caller.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MSG91 could not accept the OTP message. Check provider configuration and logs.");
        }
    }

    public String normalizeMobile(String phone) {
        if (phone == null || phone.isBlank()) throw new IllegalArgumentException("A mobile number is required.");
        String value = phone.trim().replaceAll("[\\s()-]", "");
        if (value.startsWith("+")) value = value.substring(1);
        if (value.startsWith("0") && value.length() == 11) value = value.substring(1);
        if (value.length() == 10) value = defaultCountryCode + value;
        if (!DIGITS.matcher(value).matches()) {
            throw new IllegalArgumentException("Use a valid mobile number with country code (or a 10-digit Indian number).");
        }
        return value;
    }
}

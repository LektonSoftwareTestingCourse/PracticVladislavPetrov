package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.config.SwitchProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class SwitchTestData {
    static final String PAN = "4000001234567890";
    static final String STAN = "000001";
    static final String RRN = "630500001000";

    private SwitchTestData() {
    }

    static AuthorizationRequest request() {
        return request(PAN);
    }

    static AuthorizationRequest request(String pan) {
        return new AuthorizationRequest("0100", STAN, pan, "000000", new BigDecimal("3000"),
                "643", Instant.parse("2026-11-01T12:00:00Z"), "TERM", "POS", "SHOP", "5411", "ACQ001", null);
    }

    static AuthorizationResponse approved() {
        return new AuthorizationResponse("0110", STAN, RRN, "ABC123", "00", "APPROVED", null, 5);
    }

    static AuthorizationResponse declined() {
        return new AuthorizationResponse("0110", STAN, null, null, "05", "DECLINED", "CARD_BLOCKED", 5);
    }

    static SwitchProperties properties() {
        return new SwitchProperties("1.0", Map.of("400000", "ISS001"), "http://authorization", "http://logger",
                "http://merchant", new SwitchProperties.HttpProperties(100, 200, 200),
                new SwitchProperties.RetryProperties(3, List.of(1L, 2L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 1000)));
    }
}

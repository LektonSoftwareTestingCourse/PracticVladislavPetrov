package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InMemoryCircuitBreakerTest {
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    @Mock private Clock clock;
    private InMemoryCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 3, clock);
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void shouldAllowWhenFailureThresholdHasNotBeenReached() {
        // Arrange
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");

        // Act
        boolean allowed = breaker.allowRequest("switch");

        // Assert
        assertThat(allowed).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"0, false", "9999, false", "10000, true"})
    void shouldAllowProbeOnlyWhenOpenIntervalHasExpired(long elapsedMillis, boolean expected) {
        // Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plusMillis(elapsedMillis));

        // Act
        boolean allowed = breaker.allowRequest("switch");

        // Assert
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldKeepServicesIndependentWhenOneCircuitIsOpen() {
        // Arrange
        openCircuit();

        // Act
        boolean allowed = breaker.allowRequest("card-management");

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldRejectSecondProbeWhenFirstProbeIsStillRunning() {
        // Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        breaker.allowRequest("switch");

        // Act
        boolean allowed = breaker.allowRequest("switch");

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldCloseCircuitWhenProbeSucceeds() {
        // Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        breaker.allowRequest("switch");

        // Act
        breaker.recordSuccess("switch");

        // Assert
        assertThat(breaker.allowRequest("switch")).isTrue();
        assertThat(breaker.allowRequest("switch")).isTrue();
    }

    @Test
    void shouldReopenCircuitWhenProbeFails() {
        // Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        breaker.allowRequest("switch");

        // Act
        breaker.recordFailure("switch");

        // Assert
        assertThat(breaker.allowRequest("switch")).isFalse();
    }

    @Test
    void shouldAllowReplacementProbeWhenRequestIsReleased() {
        // Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        breaker.allowRequest("switch");

        // Act
        breaker.releaseRequest("switch");

        // Assert
        assertThat(breaker.allowRequest("switch")).isTrue();
    }

    private void openCircuit() {
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");
    }
}

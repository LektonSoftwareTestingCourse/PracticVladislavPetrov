package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InMemoryRateLimiterTest {
    @Mock private Ticker ticker;

    @BeforeEach
    void setUp() {
        when(ticker.read()).thenReturn(0L);
    }

    @Test
    void shouldRejectWhenAllTokensAreConsumed() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 1);
        limiter.allowRequest("client-1");

        // Act
        boolean allowed = limiter.allowRequest("client-1");

        // Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"500000000, false", "999999999, false", "1000000000, true", "2000000000, true"})
    void shouldRefillOneTokenOnlyWhenEnoughTimeHasPassed(long nanos, boolean expected) {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 1);
        limiter.allowRequest("client-1");
        when(ticker.read()).thenReturn(nanos);

        // Act
        boolean allowed = limiter.allowRequest("client-1");

        // Assert
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldKeepClientBucketsIndependentWhenOneClientExhaustsTokens() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 0);
        limiter.allowRequest("client-1");

        // Act
        boolean allowed = limiter.allowRequest("client-2");

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldLimitRefillToCapacityWhenLongTimeHasPassed() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 1);
        limiter.allowRequest("client-1");
        when(ticker.read()).thenReturn(30_000_000_000L);
        limiter.allowRequest("client-1");

        // Act
        boolean allowed = limiter.allowRequest("client-1");

        // Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldRejectWhenCapacityIsNotPositive(int capacity) {
        // Arrange
        InMemoryRateLimiter limiter = limiter(capacity, 1);

        // Act
        boolean allowed = limiter.allowRequest("client-1");

        // Assert
        assertThat(allowed).isFalse();
    }

    private InMemoryRateLimiter limiter(int capacity, double refillRate) {
        return InMemoryRateLimiter.forTesting(capacity, refillRate, Duration.ofMinutes(10), 100, ticker);
    }
}

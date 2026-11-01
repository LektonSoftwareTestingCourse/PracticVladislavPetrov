package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.stream.Stream;

import static com.processing.service.SwitchTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {
    @Mock private RestClient restClient;
    @Mock(answer = Answers.RETURNS_SELF) private RestClient.RequestBodyUriSpec post;
    @Mock(answer = Answers.RETURNS_SELF) private RestClient.ResponseSpec response;
    private Retry retry;
    private CircuitBreaker breaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        retry = Retry.of("test", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ZERO).build());
        breaker = CircuitBreaker.ofDefaults("test");
        client = new AuthorizationClient(properties(), restClient, retry, breaker);
    }

    @ParameterizedTest
    @MethodSource("validResponses")
    void shouldReturnBusinessResponseWhenStatusIsValid(AuthorizationResponse expected) {
        // Arrange
        stubPost();
        when(response.body(AuthorizationResponse.class)).thenReturn(expected);

        // Act
        AuthorizationResponse actual = client.authorize(request());

        // Assert
        assertThat(actual).isEqualTo(expected);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void shouldRejectWhenResponseIsMissingOrStatusIsInvalid(AuthorizationResponse invalid) {
        // Arrange
        stubPost();
        when(response.body(AuthorizationResponse.class)).thenReturn(invalid);

        // Act
        Throwable error = catchThrowable(() -> client.authorize(request()));

        // Assert
        assertThat(error).isInstanceOf(AuthorizationException.class).hasMessageContaining("Invalid response");
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void shouldRecoverWhenNetworkFailureIsTemporary() {
        // Arrange
        stubPost();
        when(response.body(AuthorizationResponse.class))
                .thenThrow(new ResourceAccessException("timeout")).thenReturn(approved());

        // Act
        AuthorizationResponse actual = client.authorize(request());

        // Assert
        assertThat(actual.status()).isEqualTo("APPROVED");
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void shouldIncludeRootCauseWhenAllNetworkAttemptsFail() {
        // Arrange
        stubPost();
        when(response.body(AuthorizationResponse.class))
                .thenThrow(new ResourceAccessException("network failure", new IOException("connection refused")));

        // Act
        Throwable error = catchThrowable(() -> client.authorize(request()));

        // Assert
        assertThat(error).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("after 3 attempts").hasMessageContaining("connection refused");
    }

    @Test
    void shouldRejectWhenCircuitIsOpen() {
        // Arrange
        breaker.transitionToOpenState();

        // Act
        Throwable error = catchThrowable(() -> client.authorize(request()));

        // Assert
        assertThat(error).isInstanceOf(AuthorizationException.class).hasMessageContaining("Circuit breaker open");
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isZero();
    }

    @ParameterizedTest
    @MethodSource("validRollbacks")
    void shouldReturnRollbackWhenResponseStatusIsValid(RollbackResponse expected) {
        // Arrange
        stubPost();
        when(response.body(RollbackResponse.class)).thenReturn(expected);

        // Act
        RollbackResponse actual = client.rollback(request(), RRN);

        // Assert
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidRollbacks")
    void shouldReturnNoRollbackWhenResponseIsMissingOrInvalid(RollbackResponse invalid) {
        // Arrange
        stubPost();
        when(response.body(RollbackResponse.class)).thenReturn(invalid);

        // Act
        RollbackResponse actual = client.rollback(request(), RRN);

        // Assert
        assertThat(actual).isNull();
    }

    private void stubPost() {
        when(restClient.post()).thenReturn(post);
        when(post.retrieve()).thenReturn(response);
    }

    private static Stream<AuthorizationResponse> validResponses() {
        return Stream.of(approved(), declined());
    }

    private static Stream<AuthorizationResponse> invalidResponses() {
        return Stream.of(null, new AuthorizationResponse("0110", STAN, null, null, null, "PENDING", null, 0));
    }

    private static Stream<RollbackResponse> validRollbacks() {
        return Stream.of(new RollbackResponse(RRN, "00", "APPROVED", null, 0),
                new RollbackResponse(RRN, "05", "DECLINED", "ALREADY_ROLLED_BACK", 0));
    }

    private static Stream<RollbackResponse> invalidRollbacks() {
        return Stream.of(null, new RollbackResponse(RRN, null, "PENDING", null, 0));
    }
}

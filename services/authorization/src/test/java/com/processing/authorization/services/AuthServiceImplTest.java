package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.exceptions.*;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567890";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-11-30T23:59:59Z");
    @Mock private LimitUsageRepository limits;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient binLookup;
    @Mock private CardManagementClient cards;
    private AuthServiceImpl service;
    private Instant inputTime;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limits, events, binLookup, cards);
        inputTime = Instant.now();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "9999.99", "10000"})
    void shouldApproveWhenAmountDoesNotExceedBalance(String amount) {
        // Arrange
        AuthorizationRequest request = request(amount);
        stubActiveCard(YearMonth.of(2027, 12));
        stubAvailableLimits();

        // Act
        AuthorizationResponse response = service.authorize(request, inputTime);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).matches("\\d{12}");
        assertThat(response.authCode()).matches("[A-Z0-9]{6}");
        ArgumentCaptor<String> reservedRrn = ArgumentCaptor.forClass(String.class);
        verify(cards).reserve(eq(request.amount()), reservedRrn.capture(), eq(PAN));
        assertThat(reservedRrn.getValue()).isEqualTo(response.rrn());
        verify(events).notify(isA(AuthServiceAuthorizeEvent.class));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED, CARD_BLOCKED, 05", "INACTIVE, CARD_INACTIVE, 05", "EXPIRED, CARD_EXPIRED, 54"})
    void shouldDeclineWhenCardIsNotActive(CardModelStatus status, String reason, String code) {
        // Arrange
        when(cards.getCard(PAN)).thenReturn(card(status, YearMonth.of(2027, 12)));
        when(binLookup.getIssuerId(PAN)).thenReturn(Optional.empty());

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertDeclined(response, reason, code);
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10", "2025-11"})
    void shouldDeclineWhenExpiryMonthHasPassed(String expiry) {
        // Arrange
        stubActiveCard(YearMonth.parse(expiry));

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertDeclined(response, "CARD_EXPIRED", "54");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11", "2026-12"})
    void shouldApproveWhenCardIsValidOnLastDayOfTransmissionMonth(String expiry) {
        // Arrange
        stubActiveCard(YearMonth.parse(expiry));
        stubAvailableLimits();

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000.01", "10001"})
    void shouldDeclineWhenAmountExceedsBalance(String amount) {
        // Arrange
        stubActiveCard(YearMonth.of(2027, 12));

        // Act
        AuthorizationResponse response = service.authorize(request(amount), inputTime);

        // Assert
        assertDeclined(response, "INSUFFICIENT_FUNDS", "51");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("cardLookupFailures")
    void shouldMapDeclineWhenCardLookupFails(RuntimeException failure, String reason, String code) {
        // Arrange
        when(cards.getCard(PAN)).thenThrow(failure);

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertDeclined(response, reason, code);
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenSpendingLimitIsExceeded() {
        // Arrange
        stubActiveCard(YearMonth.of(2027, 12));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertDeclined(response, "EXCEEDS_AMOUNT_LIMIT", "61");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldRetryLimitUpdateWhenConcurrentInsertCausesDuplicate() {
        // Arrange
        stubActiveCard(YearMonth.of(2027, 12));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);
        when(limits.fetchRrnBlock()).thenReturn(1L);

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cards).reserve(new BigDecimal("3000"), response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenLimitDatabaseIsUnavailable() {
        // Arrange
        stubActiveCard(YearMonth.of(2027, 12));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertDeclined(response, "DB_UNAVAILABLE", "96");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("reserveFailures")
    void shouldMapDeclineWhenReservationFails(RuntimeException failure, String reason, String code) {
        // Arrange
        stubActiveCard(YearMonth.of(2027, 12));
        stubAvailableLimits();
        doThrow(failure).when(cards).reserve(any(), any(), eq(PAN));

        // Act
        AuthorizationResponse response = service.authorize(request("3000"), inputTime);

        // Assert
        assertDeclined(response, reason, code);
        verify(events, never()).notify(isA(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldApproveWhenRollbackSucceeds() {
        // Arrange
        RollbackRequest request = new RollbackRequest("630500001000", PAN, new BigDecimal("3000"));

        // Act
        RollbackResponse response = service.rollback(request, inputTime);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.rrn()).isEqualTo(request.rrn());
        verify(cards).rollback(request);
        verify(events).notify(isA(AuthServiceRollbackEvent.class));
    }

    @ParameterizedTest
    @MethodSource("rollbackFailures")
    void shouldMapDeclineWhenRollbackFails(RuntimeException failure, String reason, String code) {
        // Arrange
        RollbackRequest request = new RollbackRequest("630500001000", PAN, new BigDecimal("3000"));
        doThrow(failure).when(cards).rollback(request);

        // Act
        RollbackResponse response = service.rollback(request, inputTime);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        verify(events, never()).notify(isA(AuthServiceRollbackEvent.class));
    }

    private void stubActiveCard(YearMonth expiry) {
        when(cards.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, expiry));
        when(binLookup.getIssuerId(PAN)).thenReturn(Optional.empty());
    }

    private void stubAvailableLimits() {
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        when(limits.fetchRrnBlock()).thenReturn(1L);
    }

    private static AuthorizationRequest request(String amount) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", new BigDecimal(amount), "643",
                TRANSMISSION_TIME, "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }

    private static CardModel card(CardModelStatus status, YearMonth expiry) {
        return new CardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                expiry, status, "643", new BigDecimal("30000"), new BigDecimal("300000"), new BigDecimal("10000"),
                "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static void assertDeclined(AuthorizationResponse response, String reason, String code) {
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.rrn()).isNull();
    }

    private static Stream<Arguments> cardLookupFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InvalidGetCardRequestException("invalid"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("unavailable"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new PaymentRequiredException("blocked"), "CARD_BLOCKED", "05"),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05"));
    }

    private static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InsufficientFundsException("insufficient"), "INSUFFICIENT_FUNDS", "51"),
                Arguments.of(new ServiceUnavailableException("unavailable"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ReserveException("failed"), "RESERVATION_FAILED", "96"));
    }

    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new RollbackConflictException("duplicate"), "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(new ServiceUnavailableException("unavailable"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new RollbackFailureException("failed"), "ROLLBACK_FAILED", "96"));
    }
}

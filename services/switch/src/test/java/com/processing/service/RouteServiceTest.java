package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static com.processing.service.SwitchTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    @Mock private RoutingService routingService;
    @Mock private AuthorizationClient authorizationClient;
    @Mock private AcquiringFeeClient acquiringFeeClient;
    @Mock private LoggerClient loggerClient;
    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldPublishCompleteTransactionWhenAuthorizationIsApproved() {
        // Arrange
        AuthorizationRequest request = request();
        AuthorizationResponse approved = approved();
        stubAuthorization(approved);
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new BigDecimal("30"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(request);

        // Assert
        assertThat(response).isEqualTo(approved);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(published.capture());
        TransactionRequest transaction = published.getValue();
        assertThat(transaction.id()).isNotNull();
        assertThat(transaction.pan()).isEqualTo(PAN);
        assertThat(transaction.stan()).isEqualTo(STAN);
        assertThat(transaction.rrn()).isEqualTo(RRN);
        assertThat(transaction.amount()).isEqualByComparingTo("3000");
        assertThat(transaction.acquiringFee()).isEqualByComparingTo("30");
        assertThat(transaction.issuerId()).isEqualTo("ISS001");
        assertThat(transaction.terminalId()).isEqualTo("TERM0000");
        assertThat(transaction.merchantId()).isEqualTo("SHOP00000000000");
        assertThat(transaction.status()).isEqualTo(TransactionStatus.APPROVED);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldKeepDeclineWhenPublishingSucceedsOrFails(boolean published) {
        // Arrange
        AuthorizationResponse declined = declined();
        stubAuthorization(declined);
        when(loggerClient.log(any())).thenReturn(published);

        // Act
        AuthorizationResponse response = service.route(request());

        // Assert
        assertThat(response).isEqualTo(declined);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @MethodSource("rollbackResponses")
    void shouldReturnSystemErrorWhenPublishingApprovedPaymentFails(RollbackResponse rollback) {
        // Arrange
        stubAuthorization(approved());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN))).thenReturn(rollback);

        // Act
        AuthorizationResponse response = service.route(request());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        ArgumentCaptor<AuthorizationRequest> original = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationClient).rollback(original.capture(), eq(RRN));
        assertThat(original.getValue().pan()).isEqualTo(PAN);
        assertThat(original.getValue().amount()).isEqualByComparingTo("3000");
        assertThat(original.getValue().issuerId()).isEqualTo("ISS001");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "9999991234567890"})
    void shouldReturnCardNotFoundWhenBinCannotBeRouted(String pan) {
        // Arrange
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("unknown"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(request(pan));

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldReturnDeclineWhenAuthorizationIsUnavailable() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn("ISS001");
        when(authorizationClient.authorize(any())).thenThrow(new AuthorizationException(STAN, 3, "timeout"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(request());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("05");
        assertThat(response.stan()).isEqualTo(STAN);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldUseZeroProcessingTimeWhenAuthorizationOmitsIt() {
        // Arrange
        stubAuthorization(new AuthorizationResponse("0110", STAN, RRN, "ABC123", "00", "APPROVED", null, null));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(request());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().processingTimeMs()).isZero();
    }

    private void stubAuthorization(AuthorizationResponse response) {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn("ISS001");
        when(authorizationClient.authorize(any())).thenReturn(response);
    }

    private static Stream<RollbackResponse> rollbackResponses() {
        return Stream.of(null, new RollbackResponse(RRN, "00", "APPROVED", null, 1),
                new RollbackResponse(RRN, "96", "DECLINED", "unavailable", 1));
    }
}

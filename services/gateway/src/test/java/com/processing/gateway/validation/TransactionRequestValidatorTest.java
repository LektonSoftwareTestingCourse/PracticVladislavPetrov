package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TransactionRequestValidatorTest {
    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "10000"})
    void shouldAcceptWhenRequestIsValid(String amount) {
        // Arrange
        AuthorizationRequest request = validRequest().amount(new BigDecimal(amount)).build();

        // Act
        Throwable error = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(error).isNull();
    }

    @Test
    void shouldRejectWhenRequestBodyIsMissing() {
        // Arrange
        AuthorizationRequest request = null;

        // Act
        Throwable error = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(error).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void shouldRejectWhenRequiredFieldIsMissingOrInvalid(AuthorizationRequest request, String field) {
        // Arrange
        AuthorizationRequest invalid = request;

        // Act
        Throwable error = catchThrowable(() -> validator.validate(invalid));

        // Assert
        assertThat(error).isInstanceOf(TransactionValidationException.class).hasMessageContaining(field);
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(validRequest().mti(null).build(), "mti"),
                Arguments.of(validRequest().mti("0400").build(), "mti"),
                Arguments.of(validRequest().stan("").build(), "stan"),
                Arguments.of(validRequest().stan(" ").build(), "stan"),
                Arguments.of(validRequest().pan(null).build(), "pan"),
                Arguments.of(validRequest().pan("400000123456789").build(), "pan"),
                Arguments.of(validRequest().pan("40000012345678901").build(), "pan"),
                Arguments.of(validRequest().pan("400000123456789X").build(), "pan"),
                Arguments.of(validRequest().processingCode(null).build(), "processingCode"),
                Arguments.of(validRequest().amount(null).build(), "amount"),
                Arguments.of(validRequest().amount(BigDecimal.ZERO).build(), "amount"),
                Arguments.of(validRequest().amount(new BigDecimal("-0.01")).build(), "amount"),
                Arguments.of(validRequest().currencyCode(null).build(), "currencyCode"),
                Arguments.of(validRequest().currencyCode("64").build(), "currencyCode"),
                Arguments.of(validRequest().currencyCode("6430").build(), "currencyCode"),
                Arguments.of(validRequest().transmissionDateTime(null).build(), "transmissionDateTime"),
                Arguments.of(validRequest().terminalId(null).build(), "terminalId"),
                Arguments.of(validRequest().merchantId(null).build(), "merchantId"),
                Arguments.of(validRequest().mcc(null).build(), "mcc"),
                Arguments.of(validRequest().mcc("541").build(), "mcc"),
                Arguments.of(validRequest().mcc("54111").build(), "mcc"),
                Arguments.of(validRequest().mcc("ABCD").build(), "mcc"),
                Arguments.of(validRequest().acquirerId(null).build(), "acquirerId"));
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder().mti("0100").stan("000001").pan("4000001234567890")
                .processingCode("000000").amount(new BigDecimal("3000")).currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-11-01T12:00:00Z"))
                .terminalId("TERM0001").terminalType("POS").merchantId("MERCH0000000001")
                .mcc("5411").acquirerId("ACQ001").issuerId("ISS001");
    }
}

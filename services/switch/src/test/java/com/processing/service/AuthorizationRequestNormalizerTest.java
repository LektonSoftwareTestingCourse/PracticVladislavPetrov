package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {
    @ParameterizedTest
    @CsvSource({"T, T0000000", "TERM0001, TERM0001", "TERM000123, TERM0001"})
    void shouldNormalizeTerminalWhenLengthDiffers(String input, String expected) {
        // Arrange
        String terminalId = input;

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(terminalId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void shouldKeepTerminalWhenValueIsMissing(String terminalId) {
        // Arrange
        String original = terminalId;

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(original);

        // Assert
        assertThat(normalized).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource({
            "SHOP, SHOP00000000000",
            "123456789012345, 123456789012345",
            "MERCH0123456789X, MERCH123456789X",
            "MERCH1123456789X, MERCH1123456789",
            "OTHER0123456789X, OTHER0123456789",
            "MERCH01234567890XYZ, MERCH0123456789"
    })
    void shouldNormalizeMerchantWhenLengthDiffers(String input, String expected) {
        // Arrange
        String merchantId = input;

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(merchantId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void shouldKeepMerchantWhenValueIsMissing(String merchantId) {
        // Arrange
        String original = merchantId;

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(original);

        // Assert
        assertThat(normalized).isEqualTo(original);
    }

    @Test
    void shouldPreservePaymentDataWhenNormalizingRequest() {
        // Arrange
        AuthorizationRequest original = SwitchTestData.request().withIssuerId("ISS001");

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(original);

        // Assert
        assertThat(normalized).usingRecursiveComparison()
                .ignoringFields("terminalId", "merchantId").isEqualTo(original);
        assertThat(normalized.terminalId()).isEqualTo("TERM0000");
        assertThat(normalized.merchantId()).isEqualTo("SHOP00000000000");
        assertThat(original.terminalId()).isEqualTo("TERM");
    }
}

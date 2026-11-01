package com.processing.service;

import com.processing.exception.UnknownBinException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RoutingServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"400000", "4000001234567890", "4000009999999999"})
    void shouldReturnIssuerWhenBinIsKnown(String pan) {
        // Arrange
        RoutingService service = new RoutingService(SwitchTestData.properties());

        // Act
        String issuer = service.getIssuerIdByPan(pan);

        // Assert
        assertThat(issuer).isEqualTo("ISS001");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "999999", "9999991234567890"})
    void shouldRejectWhenPanIsTooShortOrBinIsUnknown(String pan) {
        // Arrange
        RoutingService service = new RoutingService(SwitchTestData.properties());

        // Act
        Throwable error = catchThrowable(() -> service.getIssuerIdByPan(pan));

        // Assert
        assertThat(error).isInstanceOf(UnknownBinException.class);
    }
}

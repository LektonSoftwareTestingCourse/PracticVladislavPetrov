package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CardTest {
    private static final String PAN = "4000001234567890";

    @ParameterizedTest
    @CsvSource({"0.01, 9999.99", "9999.99, 0.01", "10000, 0"})
    void shouldDecreaseBalanceWhenReservationFitsAvailableFunds(String amount, String expectedBalance) {
        // Arrange
        Card original = card(CardStatus.ACTIVE);
        Reservation reservation = original.startReservation(new BigDecimal(amount), "630500001000");

        // Act
        Card updated = original.withReservation(reservation);

        // Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(original.availableBalance()).isEqualByComparingTo("10000");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000.01", "10001"})
    void shouldRejectReservationWhenFundsAreInsufficient(String amount) {
        // Arrange
        Card card = card(CardStatus.ACTIVE);

        // Act
        Throwable error = catchThrowable(() -> card.startReservation(new BigDecimal(amount), "630500001000"));

        // Assert
        assertThat(error).isInstanceOf(InsufficientFundsException.class);
        assertThat(card.availableBalance()).isEqualByComparingTo("10000");
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        Card card = card(status);

        // Act
        Throwable error = catchThrowable(() -> card.startReservation(new BigDecimal("3000"), "630500001000"));

        // Assert
        assertThat(error).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1, 10000", "10000, -1", "10001, 10000"})
    void shouldRejectUpdateWhenLimitsAreNegativeOrDailyExceedsMonthly(String daily, String monthly) {
        // Arrange
        Card original = card(CardStatus.ACTIVE);

        // Act
        Throwable error = catchThrowable(() -> original.withData(CardStatus.ACTIVE,
                new BigDecimal(daily), new BigDecimal(monthly), original.availableBalance()));

        // Assert
        assertThat(error).isInstanceOf(IllegalArgumentException.class);
        assertThat(original.dailyLimit()).isEqualByComparingTo("30000");
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "10000, 10000", "9999.99, 10000"})
    void shouldAcceptUpdateWhenLimitsMeetBoundaries(String daily, String monthly) {
        // Arrange
        Card original = card(CardStatus.ACTIVE);

        // Act
        Card updated = original.withData(CardStatus.ACTIVE, new BigDecimal(daily), new BigDecimal(monthly),
                original.availableBalance());

        // Assert
        assertThat(updated.dailyLimit()).isEqualByComparingTo(daily);
        assertThat(updated.monthlyLimit()).isEqualByComparingTo(monthly);
    }

    @Test
    void shouldRejectWhenReservationBelongsToAnotherCard() {
        // Arrange
        Card original = card(CardStatus.ACTIVE);
        Reservation other = new Reservation("5000001234567890", new BigDecimal("3000"), "630500001000");

        // Act
        Throwable error = catchThrowable(() -> original.withReservation(other));

        // Assert
        assertThat(error).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectWhenReservationHasAlreadyBeenRolledBack() {
        // Arrange
        Reservation original = new Reservation(PAN, new BigDecimal("3000"), "630500001000");
        Reservation completed = original.rolledBack(original.startRollback(new BigDecimal("3000")));

        // Act
        Throwable error = catchThrowable(() -> completed.startRollback(new BigDecimal("3000")));

        // Assert
        assertThat(error).isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    private static Card card(CardStatus status) {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                YearMonth.of(2027, 12), status, "643", new BigDecimal("30000"), new BigDecimal("300000"),
                new BigDecimal("10000"), "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }
}

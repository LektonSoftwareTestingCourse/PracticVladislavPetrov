package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.*;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.*;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567890";
    private static final String RRN = "630500001000";
    @Mock private CardRepository cards;
    @Mock private ReservationRepository reservations;
    @Mock private ReservationRollbackRepository rollbacks;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator panGenerator;
    @Mock private CardEventNotifier events;
    @Mock private BinIssuerService issuers;
    @Mock private RetryService retries;
    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cards, reservations, rollbacks, settings, defaults, panGenerator,
                events, issuers, retries, new ImmediateTransactions());
    }

    @Test
    void shouldCreateActiveCardWhenInputIsValid() {
        // Arrange
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(settings.maxCardCreationRetries()).thenReturn(3);
        when(issuers.getIssuerId("400000")).thenReturn("ISS001");
        when(panGenerator.generatePan("400000")).thenReturn(PAN);
        when(retries.supply(eq(3), any())).thenAnswer(call -> call.<Supplier<Card>>getArgument(1).get());
        when(cards.create(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        Card created = service.createCard("400000", "IVAN IVANOV", "643", new BigDecimal("30000"),
                new BigDecimal("300000"), new BigDecimal("10000"));

        // Assert
        assertThat(created.pan()).isEqualTo(PAN);
        assertThat(created.issuerId()).isEqualTo("ISS001");
        assertThat(created.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(created.availableBalance()).isEqualByComparingTo("10000");
        verify(events).notifyListeners(isA(CardServiceCreationEvent.class));
    }

    @Test
    void shouldReturnCardWhenPanExists() {
        // Arrange
        Card expected = card("10000");
        when(cards.findByPan(PAN)).thenReturn(Optional.of(expected));

        // Act
        Card found = service.getCard(PAN);

        // Assert
        assertThat(found).isEqualTo(expected);
    }

    @Test
    void shouldRejectWhenCardDoesNotExist() {
        // Arrange
        when(cards.findByPan(PAN)).thenReturn(Optional.empty());

        // Act
        Throwable error = catchThrowable(() -> service.getCard(PAN));

        // Assert
        assertThat(error).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldUseDefaultPaginationWhenLimitAndOffsetAreMissing() {
        // Arrange
        Card expected = card("10000");
        when(defaults.pageLimit()).thenReturn(20);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cards.findCards(20, 0L, null, null, null, null, null)).thenReturn(List.of(expected));

        // Act
        List<Card> found = service.getCards(null, null, null, null, null, null, null);

        // Assert
        assertThat(found).containsExactly(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void shouldAcceptPageLimitWhenItIsWithinBoundary(int limit) {
        // Arrange
        Card expected = card("10000");
        when(settings.maxPageLimit()).thenReturn(1000);
        when(cards.findCards(limit, 10L, null, null, null, null, null)).thenReturn(List.of(expected));

        // Act
        List<Card> found = service.getCards(limit, 10L, null, null, null, null, null);

        // Assert
        assertThat(found).containsExactly(expected);
    }

    @Test
    void shouldRejectPageWhenLimitExceedsMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(1000);

        // Act
        Throwable error = catchThrowable(() -> service.getCards(1001, 0L, null, null, null, null, null));

        // Assert
        assertThat(error).isInstanceOf(TooLargeLimitException.class);
    }

    @Test
    void shouldPreserveOtherFieldsWhenOnlyDailyLimitIsPatched() {
        // Arrange
        Card original = card("10000");
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        Card updated = service.patchCard(PAN, null, new BigDecimal("50000"), null, null);

        // Assert
        assertThat(updated.dailyLimit()).isEqualByComparingTo("50000");
        assertThat(updated).usingRecursiveComparison().ignoringFields("dailyLimit").isEqualTo(original);
        verify(events).notifyListeners(isA(CardServicePatchEvent.class));
    }

    @Test
    void shouldMarkCardDeletedWhenDeletionIsRequested() {
        // Arrange
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("10000")));

        // Act
        service.deleteCard(PAN);

        // Assert
        ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
        verify(cards).update(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(CardStatus.DELETED);
        assertThat(saved.getValue().pan()).isEqualTo(PAN);
        verify(events).notifyListeners(isA(CardServiceDeletionEvent.class));
    }

    @Test
    void shouldReserveAndPublishEventWhenRrnIsUnique() {
        // Arrange
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("10000")));
        when(reservations.isUnique(RRN, PAN)).thenReturn(true);
        when(reservations.save(any())).thenAnswer(call -> call.getArgument(0));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        Card updated = service.reserve(PAN, new BigDecimal("3000"), RRN);

        // Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo("7000");
        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(saved.capture());
        assertThat(saved.getValue().rrn()).isEqualTo(RRN);
        assertThat(saved.getValue().reservationAmount()).isEqualByComparingTo("3000");
        verify(events).notifyListeners(isA(CardServiceReserveEvent.class));
    }

    @Test
    void shouldKeepBalanceWhenReservationIsDuplicate() {
        // Arrange
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("7000")));
        when(reservations.isUnique(RRN, PAN)).thenReturn(false);

        // Act
        Throwable error = catchThrowable(() -> service.reserve(PAN, new BigDecimal("3000"), RRN));

        // Assert
        assertThat(error).isInstanceOf(ReservationAlreadyExistsException.class);
        verify(cards, never()).update(any());
        verify(reservations, never()).save(any());
        verify(events, never()).notifyListeners(any());
    }

    @Test
    void shouldRestoreBalanceWhenReservationIsRolledBack() {
        // Arrange
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card("7000")));
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN))
                .thenReturn(Optional.of(new Reservation(PAN, new BigDecimal("3000"), RRN)));
        when(rollbacks.save(any())).thenAnswer(call -> call.getArgument(0));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        Card restored = service.rollback(PAN, new BigDecimal("3000"), RRN);

        // Assert
        assertThat(restored.availableBalance()).isEqualByComparingTo("10000");
        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        verify(events).notifyListeners(isA(CardServiceRollbackEvent.class));
    }

    private static Card card(String balance) {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                YearMonth.of(2027, 12), CardStatus.ACTIVE, "643", new BigDecimal("30000"), new BigDecimal("300000"),
                new BigDecimal(balance), "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static final class ImmediateTransactions implements TransactionRunner {
        @Override
        public void run(Runnable operation) {
            operation.run();
        }

        @Override
        public <T> T runSupplier(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> operation) {
            return operation.apply(this);
        }
    }
}

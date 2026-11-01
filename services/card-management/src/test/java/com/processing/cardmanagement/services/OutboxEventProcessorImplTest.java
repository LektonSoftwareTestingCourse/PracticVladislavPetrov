package com.processing.cardmanagement.services;

import com.processing.cardmanagement.config.RabbitMQConfig;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.models.CardOutboxEventData;
import com.processing.cardmanagement.models.OutboxEventDataStatus;
import com.processing.cardmanagement.options.OutboxOptions;
import com.processing.cardmanagement.repositories.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorImplTest {
    private static final CardServicePatchEvent EVENT = new CardServicePatchEvent("4000001234567890");
    private static final String ROUTING_KEY = RabbitMQConfig.ROUTING_KEY_PREFIX + "CardServicePatchEvent";
    @Mock private OutboxRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;
    private OutboxEventProcessorImpl processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxEventProcessorImpl(repository, rabbitTemplate, new OutboxOptions(1000, 3));
    }

    @Test
    void shouldMarkProcessedWhenPublicationSucceeds() {
        // Arrange
        CardOutboxEventData pending = pendingEvent(0);

        // Act
        processor.processSingleEvent(pending);

        // Assert
        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.CARD_EVENTS_EXCHANGE, ROUTING_KEY, EVENT);
        ArgumentCaptor<CardOutboxEventData> saved = ArgumentCaptor.forClass(CardOutboxEventData.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(OutboxEventDataStatus.PROCESSED);
        assertThat(saved.getValue().processedAt()).isNotNull();
        assertThat(saved.getValue().retryCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0, 1, PENDING", "1, 2, PENDING", "2, 3, FAILED"})
    void shouldCountFailureAndStopWhenRetryLimitIsReached(int previous, int expected, OutboxEventDataStatus status) {
        // Arrange
        CardOutboxEventData pending = pendingEvent(previous);
        doThrow(new AmqpException("broker unavailable")).when(rabbitTemplate)
                .convertAndSend(RabbitMQConfig.CARD_EVENTS_EXCHANGE, ROUTING_KEY, EVENT);

        // Act
        processor.processSingleEvent(pending);

        // Assert
        ArgumentCaptor<CardOutboxEventData> saved = ArgumentCaptor.forClass(CardOutboxEventData.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().retryCount()).isEqualTo(expected);
        assertThat(saved.getValue().status()).isEqualTo(status);
        assertThat(saved.getValue().lastError()).isEqualTo("broker unavailable");
        assertThat(saved.getValue().processedAt()).isNull();
    }

    private static CardOutboxEventData pendingEvent(int retries) {
        return new CardOutboxEventData(UUID.fromString("00000000-0000-0000-0000-000000000001"), EVENT,
                Instant.parse("2026-11-01T12:00:00Z"), null, retries, null, OutboxEventDataStatus.PENDING);
    }
}

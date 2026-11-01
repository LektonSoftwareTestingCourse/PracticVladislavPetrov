package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoggerClientTest {
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private TransactionRequest transaction;

    @Test
    void shouldPublishToTransactionExchangeWhenBrokerIsAvailable() {
        // Arrange
        LoggerClient client = new LoggerClient(rabbitTemplate);

        // Act
        boolean published = client.log(transaction);

        // Assert
        assertThat(published).isTrue();
        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }

    @Test
    void shouldReportFailureWhenBrokerRejectsPublication() {
        // Arrange
        LoggerClient client = new LoggerClient(rabbitTemplate);
        doThrow(new AmqpException("broker unavailable")).when(rabbitTemplate)
                .convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);

        // Act
        boolean published = client.log(transaction);

        // Assert
        assertThat(published).isFalse();
    }
}

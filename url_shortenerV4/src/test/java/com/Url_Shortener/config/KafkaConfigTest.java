package com.Url_Shortener.config;

import com.Url_Shortener.event.ClickEventCreatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void listenerFactoryUsesManualAcks() {
        ConcurrentKafkaListenerContainerFactory<String, ClickEventCreatedEvent> factory =
                new KafkaConfig().kafkaListenerContainerFactory(
                        mock(ConsumerFactory.class), mock(CommonErrorHandler.class));

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL);
    }
}

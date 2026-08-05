package com.bank.payment.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

@Configuration
public class InternalKafkaConfig {

    @Value("${payment.kafka.missing-topics-fatal:true}")
    private boolean missingTopicsFatal;

    @Value("${payment.kafka.internal.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${payment.kafka.internal.consumer-group}")
    private String consumerGroup;

    @Bean
    public DefaultKafkaProducerFactory<String, String> internalProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,           // 설정 변경 시 묵시적 비활성화 방지
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5   // 멱등 producer 전제조건 명시
        ));
    }

    @Bean
    public KafkaTemplate<String, String> internalKafkaTemplate() {
        return new KafkaTemplate<>(internalProducerFactory());
    }

    @Bean
    public DefaultKafkaConsumerFactory<String, String> internalConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, consumerGroup,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> internalListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(internalConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 토픽 자동생성이 꺼진 운영 환경에서는 토픽 부재를 기동 실패로 다뤄야 한다(P-009).
        // 다만 Kafka 가 아예 없는 환경(수신계 컨트롤러 슬라이스 테스트 등)에서는
        // 이 때문에 컨텍스트가 죽으므로 프로퍼티로 내릴 수 있게 한다. 기본값은 그대로 true.
        factory.setMissingTopicsFatal(missingTopicsFatal);
        return factory;
    }
}

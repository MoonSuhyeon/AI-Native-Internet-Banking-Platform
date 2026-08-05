package com.bank.payment.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@EnableKafka
@Configuration
public class KftcKafkaConfig {

    @Value("${payment.kafka.kftc.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${payment.kafka.kftc.consumer-group}")
    private String consumerGroup;

    @Autowired
    private PaymentMetrics paymentMetrics;

    @Bean
    public DefaultKafkaProducerFactory<String, String> kftcProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",                        // 청산상태 ACK 기준 (enum v9 §4)
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,           // 설정 변경 시 묵시적 비활성화 방지
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5   // 멱등 producer 전제조건 명시
        ));
    }

    @Bean
    public KafkaTemplate<String, String> kftcKafkaTemplate() {
        return new KafkaTemplate<>(kftcProducerFactory());
    }

    @Bean
    public DefaultKafkaConsumerFactory<String, String> kftcConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, consumerGroup,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false   // MANUAL_IMMEDIATE ack
        ));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kftcListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kftcConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setMissingTopicsFatal(true);   // auto-create 비활성화 환경 (P-009)

        // DLQ 라우팅: 1초 간격 3회 재시도 후 kftc.network.response.dlq (R12)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kftcKafkaTemplate(),
                (r, e) -> new TopicPartition("kftc.network.response.dlq", 0)
        ) {
            @Override
            public void accept(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                               org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                               Exception exception) {
                paymentMetrics.dlq("kftc");
                super.accept(record, consumer, exception);
            }
        };
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3)));

        return factory;
    }
}

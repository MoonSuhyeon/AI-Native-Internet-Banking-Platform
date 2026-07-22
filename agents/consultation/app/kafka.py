import json
from typing import Any

from app.config import Settings
from app.metrics import chatbot_kafka_consume_total, chatbot_kafka_publish_total


class KafkaEventPublisher:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._producer = None

    async def start(self) -> None:
        if not self._settings.kafka_enabled:
            return
        try:
            from aiokafka import AIOKafkaProducer
        except ImportError as exc:
            raise RuntimeError(
                "KAFKA_ENABLED=true requires aiokafka. Install requirements.txt first."
            ) from exc

        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._settings.kafka_bootstrap_servers
        )
        await self._producer.start()

    async def stop(self) -> None:
        if self._producer:
            await self._producer.stop()

    async def publish(self, event_type: str, payload: dict[str, Any]) -> None:
        """챗봇 이벤트 토픽으로 발행."""
        await self._send(self._settings.kafka_topic_chatbot_events, event_type, payload)

    async def publish_chat(self, event_type: str, payload: dict[str, Any]) -> None:
        """상담사 채팅 이벤트 토픽으로 발행."""
        await self._send(self._settings.kafka_topic_chat_events, event_type, payload)

    async def publish_chatbot_message(self, payload: dict[str, Any]) -> None:
        """챗봇 메시지 수신 토픽으로 발행."""
        await self._send(self._settings.kafka_topic_chatbot_message, "ChatbotMessageReceived", payload)

    async def _send(self, topic: str, event_type: str, payload: dict[str, Any]) -> None:
        if not self._producer:
            return
        message = {"eventType": event_type, "payload": payload}
        try:
            await self._producer.send_and_wait(
                topic,
                json.dumps(message, ensure_ascii=False, default=str).encode("utf-8"),
            )
            chatbot_kafka_publish_total.labels(topic=topic, status="success").inc()
        except Exception:
            chatbot_kafka_publish_total.labels(topic=topic, status="error").inc()
            raise


class KafkaEventConsumer:
    """실시간 알림용 Kafka Consumer.

    향후 WebSocket 푸시 알림 연동 시 사용.
    현재는 시작/중지 인터페이스만 제공하며 실제 소비는 호출 측에서 구현.
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        self._consumer = None

    async def start(self, topics: list[str], group_id: str = "consultation-service") -> None:
        if not self._settings.kafka_enabled:
            return
        try:
            from aiokafka import AIOKafkaConsumer
        except ImportError as exc:
            raise RuntimeError(
                "KAFKA_ENABLED=true requires aiokafka."
            ) from exc

        self._consumer = AIOKafkaConsumer(
            *topics,
            bootstrap_servers=self._settings.kafka_bootstrap_servers,
            group_id=group_id,
        )
        await self._consumer.start()

    async def stop(self) -> None:
        if self._consumer:
            await self._consumer.stop()

    async def __aiter__(self):
        """비동기 이터레이터로 메시지 소비."""
        if not self._consumer:
            return
        async for msg in self._consumer:
            try:
                data = json.loads(msg.value.decode("utf-8"))
                event_type = data.get("eventType", "UNKNOWN")
                chatbot_kafka_consume_total.labels(event_type=event_type, status="success").inc()
                yield data
            except Exception:
                chatbot_kafka_consume_total.labels(event_type="UNKNOWN", status="error").inc()

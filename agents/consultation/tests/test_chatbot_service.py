import asyncio

import pytest

from app.config import Settings
from app.kafka import KafkaEventPublisher
from app.schemas import ChatbotFeatureExecuteRequest


def test_categories_and_features(service):
    categories = service.categories()
    features = service.features()

    assert [category.code for category in categories] == [
        "PRODUCT_ADVICE",
        "USER_FINANCE",
        "STAFF_SUPPORT",
    ]
    assert "PRODUCT_GUIDE" in {feature.code for feature in features}
    assert "STAFF_CONSULTATION_HISTORY" in {feature.code for feature in features}


def test_seed_default_scenario(service):
    scenario_id, first_node_id = service.seed_default_scenario()

    assert scenario_id > 0
    assert first_node_id > 0
    assert len(service._button_responses(first_node_id)) == 4


def test_start_and_message_flow(service):
    service.seed_default_scenario()

    async def run_flow():
        started = await service.start("CUST001", "HOME", "0.1.0")
        response = await service.handle_message(
            started.chatbot_consultation_id,
            "금융상품 상담",
            "PRODUCT_ADVICE",
        )
        return started, response

    started, response = asyncio.run(run_flow())

    assert started.consultation_id > 0
    assert response.process_method == "SCENARIO"
    assert "예금" in response.message


def test_feature_execute_statuses(service):
    assert service.execute_feature("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest()).status == "OK"
    assert service.execute_feature(
        "PRODUCT_COMPARE",
        ChatbotFeatureExecuteRequest(compare_product_ids=[1]),
    ).status == "OK"
    assert service.execute_feature("FAQ", ChatbotFeatureExecuteRequest()).status == "OK"
    assert service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest()).status == "AUTH_REQUIRED"
    assert service.execute_feature("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no="CUST001")).status == "OK"
    assert service.execute_feature("STAFF_ACCOUNT", ChatbotFeatureExecuteRequest(customer_no="CUST001")).status == "STAFF_AUTH_REQUIRED"
    assert service.execute_feature(
        "STAFF_ACCOUNT",
        ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001"),
    ).status == "OK"
    assert service.execute_feature("NO_SUCH_FEATURE", ChatbotFeatureExecuteRequest()).status == "NOT_FOUND"


@pytest.mark.parametrize(
    ("feature_code", "request_payload", "expected_status"),
    [
        ("PRODUCT_GUIDE", ChatbotFeatureExecuteRequest(), "OK"),
        ("RATE_GUIDE", ChatbotFeatureExecuteRequest(), "OK"),
        ("JOIN_CONDITION", ChatbotFeatureExecuteRequest(), "OK"),
        ("PRODUCT_COMPARE", ChatbotFeatureExecuteRequest(), "OK"),
        ("PRODUCT_COMPARE", ChatbotFeatureExecuteRequest(compare_product_ids=[1]), "OK"),
        ("TERMS_RAG", ChatbotFeatureExecuteRequest(query="개인정보"), "OK"),
        ("FAQ", ChatbotFeatureExecuteRequest(), "OK"),
        ("MY_ACCOUNTS", ChatbotFeatureExecuteRequest(customer_no="CUST001"), "OK"),
        ("MY_PRODUCTS", ChatbotFeatureExecuteRequest(customer_no="CUST001"), "OK"),
        ("CONTRACT_STATUS", ChatbotFeatureExecuteRequest(customer_no="CUST001"), "OK"),
        ("MATURITY_SCHEDULE", ChatbotFeatureExecuteRequest(customer_no="CUST001"), "OK"),
        ("INTEREST_HISTORY", ChatbotFeatureExecuteRequest(customer_no="CUST001"), "OK"),
        ("STAFF_CUSTOMER", ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001"), "OK"),
        ("STAFF_CONTRACT", ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001"), "OK"),
        ("STAFF_ACCOUNT", ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001"), "OK"),
        ("STAFF_TRANSFER_FLOW", ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001"), "OK"),
        (
            "STAFF_CONSULTATION_HISTORY",
            ChatbotFeatureExecuteRequest(customer_no="CUST001", staff_id="EMP001"),
            "EMPTY",
        ),
    ],
)
def test_each_feature_execute_success_path(service, feature_code, request_payload, expected_status):
    result = service.execute_feature(feature_code, request_payload)

    assert result.feature_code == feature_code
    assert result.status == expected_status
    assert result.message


@pytest.mark.parametrize(
    "feature_code",
    [
        "MY_ACCOUNTS",
        "MY_PRODUCTS",
        "CONTRACT_STATUS",
        "MATURITY_SCHEDULE",
        "INTEREST_HISTORY",
    ],
)
def test_user_finance_features_require_customer_auth(service, feature_code):
    result = service.execute_feature(feature_code, ChatbotFeatureExecuteRequest())

    assert result.status == "AUTH_REQUIRED"
    assert result.requires_auth is True


@pytest.mark.parametrize(
    "feature_code",
    [
        "STAFF_CUSTOMER",
        "STAFF_CONTRACT",
        "STAFF_ACCOUNT",
        "STAFF_TRANSFER_FLOW",
        "STAFF_CONSULTATION_HISTORY",
    ],
)
def test_staff_features_require_staff_auth(service, feature_code):
    result = service.execute_feature(
        feature_code,
        ChatbotFeatureExecuteRequest(customer_no="CUST001"),
    )

    assert result.status == "STAFF_AUTH_REQUIRED"
    assert result.requires_staff_auth is True


def test_kafka_publisher_skips_when_disabled():
    publisher = KafkaEventPublisher(Settings(kafka_enabled=False))

    async def run_publish():
        await publisher.start()
        await publisher.publish("TestEvent", {"key": "value"})
        await publisher.stop()

    asyncio.run(run_publish())

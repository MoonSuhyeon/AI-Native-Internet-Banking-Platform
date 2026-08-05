-- =============================================
-- V12: 챗봇·상담 테이블 제거
--
-- 배경: V5에서 ERD 전체를 일괄 생성할 때 chatbot·consultation 테이블이
--       deposit-db에 포함됐으나, 해당 테이블의 실제 소유자는 consultation-service.
--       consultation-service가 SQLAlchemy create_all()로 올바른 스키마를 관리하므로
--       deposit-db에서 제거하고 consultation-service가 재생성하도록 한다.
--
-- 삭제 순서: FK 의존 순서 (자식 → 부모)
-- =============================================

DROP TABLE IF EXISTS chatbot_conversation_history  CASCADE;
DROP TABLE IF EXISTS chatbot_node_flow             CASCADE;
DROP TABLE IF EXISTS chatbot_node_button           CASCADE;
DROP TABLE IF EXISTS chatbot_consultation          CASCADE;
DROP TABLE IF EXISTS chat_message_history          CASCADE;
DROP TABLE IF EXISTS chat_consultation             CASCADE;
DROP TABLE IF EXISTS chatbot_node                  CASCADE;
DROP TABLE IF EXISTS chatbot_intent                CASCADE;
DROP TABLE IF EXISTS chatbot_scenario              CASCADE;
DROP TABLE IF EXISTS consultation                  CASCADE;

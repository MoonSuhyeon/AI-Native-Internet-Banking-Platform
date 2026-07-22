from datetime import date, datetime
from decimal import Decimal
import enum

from sqlalchemy import (
    BigInteger,
    CHAR,
    CheckConstraint,
    Date,
    Enum,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

# TIMESTAMPTZ(3) shorthand
TSTZ3 = TIMESTAMP(timezone=True, precision=3)


class DepositProductType(str, enum.Enum):
    DEPOSIT = "DEPOSIT"
    SAVINGS = "SAVINGS"
    SUBSCRIPTION = "SUBSCRIPTION"


class DepositProductStatus(str, enum.Enum):
    SELLING = "SELLING"
    SUSPENDED = "SUSPENDED"
    EXPIRED = "EXPIRED"


class DepositType(str, enum.Enum):
    TERM = "TERM"
    DEMAND = "DEMAND"


class SavingType(str, enum.Enum):
    REGULAR = "REGULAR"
    FREE = "FREE"


class JoinChannel(str, enum.Enum):
    BRANCH = "BRANCH"
    WEB = "WEB"
    MOBILE = "MOBILE"
    TELL = "TELL"
    RECRUITER = "RECRUITER"
    ETC = "ETC"


class RateType(str, enum.Enum):
    BASE = "BASE"
    PERIOD_BASE = "PERIOD_BASE"
    PREFERENTIAL = "PREFERENTIAL"
    EARLY_TERMINATION = "EARLY_TERMINATION"


class ContractStatus(str, enum.Enum):
    ACTIVE = "ACTIVE"
    MATURED = "MATURED"
    TERMINATED = "TERMINATED"
    SUSPENDED = "SUSPENDED"


class TaxBenefitType(str, enum.Enum):
    GENERAL = "GENERAL"
    NON_TAXABLE = "NON_TAXABLE"
    REDUCED_TAX = "REDUCED_TAX"


class AccountStatus(str, enum.Enum):
    ACTIVE = "ACTIVE"
    DORMANT = "DORMANT"
    SUSPENDED = "SUSPENDED"
    CLOSED = "CLOSED"


class TransactionType(str, enum.Enum):
    DEPOSIT = "DEPOSIT"
    WITHDRAW = "WITHDRAW"
    TRANSFER = "TRANSFER"
    INTEREST = "INTEREST"
    SAVINGS_PAYMENT = "SAVINGS_PAYMENT"
    PAYMENT = "PAYMENT"
    REVERSAL = "REVERSAL"


class DirectionType(str, enum.Enum):
    IN = "IN"
    OUT = "OUT"


class TransactionStatus(str, enum.Enum):
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    CANCELED = "CANCELED"
    PENDING = "PENDING"


class TransactionChannel(str, enum.Enum):
    BRANCH = "BRANCH"
    ATM = "ATM"
    INTERNET = "INTERNET"
    MOBILE = "MOBILE"
    SYSTEM = "SYSTEM"


class TransferType(str, enum.Enum):
    INTERNAL = "INTERNAL"
    EXTERNAL = "EXTERNAL"
    AUTO = "AUTO"
    SCHEDULED = "SCHEDULED"


class PaymentMethod(str, enum.Enum):
    CARD = "CARD"
    ACCOUNT_TRANSFER = "ACCOUNT_TRANSFER"
    EASY_PAY = "EASY_PAY"


class FailureType(str, enum.Enum):
    TRANSFER = "TRANSFER"
    CARD_PAYMENT = "CARD_PAYMENT"
    AUTH = "AUTH"
    LIMIT = "LIMIT"
    SYSTEM = "SYSTEM"


class FailureReasonCode(str, enum.Enum):
    INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    INVALID_ACCOUNT = "INVALID_ACCOUNT"
    CARD_DECLINED = "CARD_DECLINED"
    AUTH_FAILED = "AUTH_FAILED"
    SYSTEM_ERROR = "SYSTEM_ERROR"


class InterestReason(str, enum.Enum):
    REGULAR_INTEREST = "REGULAR_INTEREST"
    MATURITY_INTEREST = "MATURITY_INTEREST"
    BONUS_INTEREST = "BONUS_INTEREST"


class SpecialTermStatus(str, enum.Enum):
    ACTIVE = "ACTIVE"
    INACTIVE = "INACTIVE"


class RecognitionStatus(str, enum.Enum):
    RECOGNIZED = "RECOGNIZED"
    PARTIAL = "PARTIAL"
    REJECTED = "REJECTED"
    PENDING = "PENDING"


class DepartmentType(str, enum.Enum):
    PRODUCT = "PRODUCT"
    SALES = "SALES"
    OPERATION = "OPERATION"
    RISK = "RISK"
    IT = "IT"


def enum_col(enum_cls: type[enum.Enum], name: str):
    return Enum(enum_cls, name=name, native_enum=True, validate_strings=True)


class AuditMixin:
    created_at: Mapped[datetime] = mapped_column(TSTZ3, default=datetime.utcnow)
    updated_at: Mapped[datetime | None] = mapped_column(TSTZ3, onupdate=datetime.utcnow)


class Department(Base, AuditMixin):
    __tablename__ = "deposit_departments"

    department_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    department_name: Mapped[str] = mapped_column(String(100), nullable=False)
    department_code: Mapped[str] = mapped_column(String(30), nullable=False, unique=True)
    department_type: Mapped[DepartmentType] = mapped_column(enum_col(DepartmentType, "department_type_enum"), nullable=False)
    parent_department_id: Mapped[int | None] = mapped_column(ForeignKey("deposit_departments.department_id", ondelete="RESTRICT", onupdate="CASCADE"))
    is_active: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="Y")


class BankingProduct(Base, AuditMixin):
    __tablename__ = "deposit_banking_products"

    banking_product_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    deposit_product_type: Mapped[DepositProductType] = mapped_column(enum_col(DepositProductType, "deposit_product_type_enum"), nullable=False)
    deposit_product_name: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    department_id: Mapped[int | None] = mapped_column(ForeignKey("deposit_departments.department_id", ondelete="RESTRICT", onupdate="CASCADE"))
    base_interest_rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False, default=0)
    preferential_rate_condition: Mapped[str | None] = mapped_column(Text)
    min_join_amount: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    max_join_amount: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    min_period_month: Mapped[int | None] = mapped_column(Integer)
    max_period_month: Mapped[int | None] = mapped_column(Integer)
    is_early_termination_allowed: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="Y")
    is_tax_benefit_available: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    is_auto_renewal_available: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    is_passbook_issued: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    released_at: Mapped[str | None] = mapped_column(CHAR(8))
    ended_at: Mapped[str | None] = mapped_column(CHAR(8))
    deposit_product_status: Mapped[DepositProductStatus] = mapped_column(enum_col(DepositProductStatus, "deposit_product_status_enum"), nullable=False, default=DepositProductStatus.SELLING)


class DepositProduct(Base, AuditMixin):
    __tablename__ = "banking_deposit_products"

    deposit_product_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    deposit_type: Mapped[DepositType] = mapped_column(enum_col(DepositType, "deposit_type_enum"), nullable=False)
    is_compound_interest: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")


class SavingsProduct(Base, AuditMixin):
    __tablename__ = "deposit_savings_products"

    savings_product_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    saving_type: Mapped[SavingType] = mapped_column(enum_col(SavingType, "saving_type_enum"), nullable=False)
    monthly_payment_min: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    monthly_payment_max: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    payment_day: Mapped[int | None] = mapped_column(Integer)


class SubscriptionProduct(Base, AuditMixin):
    __tablename__ = "deposit_subscription_products"

    subscription_product_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False, unique=True)
    monthly_payment_amount: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    max_recognized_payment_amount: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))


class DepositProductJoinChannel(Base, AuditMixin):
    __tablename__ = "banking_deposit_product_join_channels"

    channel_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="CASCADE", onupdate="CASCADE"), nullable=False)
    join_channel_code: Mapped[JoinChannel] = mapped_column(enum_col(JoinChannel, "join_channel_enum"), nullable=False)

    __table_args__ = (UniqueConstraint("banking_product_id", "join_channel_code", name="uq_deposit_product_join_channel"),)


class TargetGroup(Base, AuditMixin):
    __tablename__ = "deposit_target_groups"

    target_group_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    target_group_name: Mapped[str] = mapped_column(String(100), nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    is_active: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="Y")


class DepositProductTargetGroup(Base, AuditMixin):
    __tablename__ = "banking_deposit_product_target_groups"

    deposit_product_target_group_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="CASCADE", onupdate="CASCADE"), nullable=False)
    target_group_id: Mapped[int] = mapped_column(ForeignKey("deposit_target_groups.target_group_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)

    __table_args__ = (UniqueConstraint("banking_product_id", "target_group_id", name="uq_deposit_product_target_group"),)


class DepositProductInterestRate(Base, AuditMixin):
    __tablename__ = "banking_deposit_product_interest_rates"

    rate_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    rate_type: Mapped[RateType] = mapped_column(enum_col(RateType, "rate_type_enum"), nullable=False)
    rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False)
    minimum_contract_period: Mapped[int | None] = mapped_column(Integer)
    maximum_contract_period: Mapped[int | None] = mapped_column(Integer)
    minimum_join_amount: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    maximum_join_amount: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    condition_description: Mapped[str | None] = mapped_column(Text)
    effective_start_date: Mapped[date] = mapped_column(Date, nullable=False)
    effective_end_date: Mapped[date | None] = mapped_column(Date)
    is_active: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="Y")


class SpecialTerm(Base, AuditMixin):
    __tablename__ = "deposit_special_terms"

    special_term_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    special_term_name: Mapped[str] = mapped_column(String(200), nullable=False)
    special_term_code: Mapped[str] = mapped_column(String(50), nullable=False, unique=True)
    description: Mapped[str | None] = mapped_column(Text)
    term_version: Mapped[str | None] = mapped_column(String(20))
    is_required: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    status: Mapped[SpecialTermStatus] = mapped_column(enum_col(SpecialTermStatus, "special_term_status_enum"), nullable=False, default=SpecialTermStatus.ACTIVE)


class DepositProductSpecialTerm(Base, AuditMixin):
    __tablename__ = "banking_deposit_product_special_terms"

    deposit_product_special_term_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="CASCADE", onupdate="CASCADE"), nullable=False)
    special_term_id: Mapped[int] = mapped_column(ForeignKey("deposit_special_terms.special_term_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    display_order: Mapped[int | None] = mapped_column(Integer)

    __table_args__ = (UniqueConstraint("banking_product_id", "special_term_id", name="uq_deposit_product_special_term"),)


class Contract(Base, AuditMixin):
    __tablename__ = "deposit_contracts"

    contract_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    contract_number: Mapped[str] = mapped_column(String(50), nullable=False, unique=True)
    customer_id: Mapped[str] = mapped_column(String(30), nullable=False)
    banking_product_id: Mapped[int] = mapped_column(ForeignKey("deposit_banking_products.banking_product_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    contract_interest_rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False, default=0)
    total_preferential_rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False, default=0)
    final_interest_rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False, default=0)
    tax_benefit_type: Mapped[TaxBenefitType] = mapped_column(enum_col(TaxBenefitType, "tax_benefit_type_enum"), nullable=False, default=TaxBenefitType.GENERAL)
    applied_tax_rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False, default=15.4)
    contract_period_month: Mapped[int] = mapped_column(Integer, nullable=False)
    started_at: Mapped[str] = mapped_column(CHAR(8), nullable=False)
    maturity_at: Mapped[str] = mapped_column(CHAR(8), nullable=False)
    terminated_at: Mapped[str | None] = mapped_column(CHAR(8))
    contract_status: Mapped[ContractStatus] = mapped_column(enum_col(ContractStatus, "contract_status_enum"), nullable=False, default=ContractStatus.ACTIVE)
    join_channel: Mapped[JoinChannel] = mapped_column(enum_col(JoinChannel, "join_channel_enum"), nullable=False)
    branch_id: Mapped[int | None] = mapped_column(BigInteger)
    manager_id: Mapped[int | None] = mapped_column(BigInteger)
    is_auto_renewal: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    auto_transfer_enabled: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    auto_transfer_day: Mapped[int | None] = mapped_column(Integer)
    is_proxy_joined: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    is_power_of_attorney_verified: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    power_of_attorney_file_url: Mapped[str | None] = mapped_column(String(500))
    terms_file_url: Mapped[str | None] = mapped_column(String(500))
    contract_file_url: Mapped[str | None] = mapped_column(String(500))

    __table_args__ = (
        CheckConstraint("is_proxy_joined = 'N' OR is_power_of_attorney_verified = 'Y'", name="ck_contracts_power_of_attorney"),
    )


class ContractAppliedRate(Base, AuditMixin):
    __tablename__ = "deposit_contract_applied_rates"

    applied_rate_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    contract_id: Mapped[int] = mapped_column(ForeignKey("deposit_contracts.contract_id", ondelete="CASCADE", onupdate="CASCADE"), nullable=False)
    rate_id: Mapped[int] = mapped_column(ForeignKey("banking_deposit_product_interest_rates.rate_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    applied_rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False)
    condition_verified_yn: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")


class ContractSpecialTermAgreement(Base, AuditMixin):
    __tablename__ = "deposit_contract_special_term_agreements"

    agreement_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    contract_id: Mapped[int] = mapped_column(ForeignKey("deposit_contracts.contract_id", ondelete="CASCADE", onupdate="CASCADE"), nullable=False)
    special_term_id: Mapped[int] = mapped_column(ForeignKey("deposit_special_terms.special_term_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    is_agreed: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    agreed_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    withdrawn_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    agreement_ip_address: Mapped[str | None] = mapped_column(String(45))
    agreement_device_info: Mapped[str | None] = mapped_column(String(255))
    is_electronic_signed: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")


class SubscriptionPaymentRecognitionHistory(Base, AuditMixin):
    __tablename__ = "deposit_subscription_payment_recognition_history"

    recognition_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    contract_id: Mapped[int] = mapped_column(ForeignKey("deposit_contracts.contract_id", ondelete="CASCADE", onupdate="CASCADE"), nullable=False)
    payment_month: Mapped[date] = mapped_column(Date, nullable=False)
    payment_amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False)
    recognized_amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False)
    recognition_status: Mapped[RecognitionStatus] = mapped_column(enum_col(RecognitionStatus, "recognition_status_enum"), nullable=False, default=RecognitionStatus.PENDING)


class Account(Base, AuditMixin):
    __tablename__ = "deposit_accounts"

    account_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    account_number: Mapped[str] = mapped_column(String(30), nullable=False, unique=True)
    customer_id: Mapped[str] = mapped_column(String(30), nullable=False)
    contract_id: Mapped[int] = mapped_column(ForeignKey("deposit_contracts.contract_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False, unique=True)
    account_type: Mapped[DepositProductType] = mapped_column(enum_col(DepositProductType, "account_type_enum"), nullable=False)
    saving_type: Mapped[SavingType | None] = mapped_column(enum_col(SavingType, "account_saving_type_enum"))
    balance: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False, default=0)
    total_paid_amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False, default=0)
    total_interest_amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False, default=0)
    last_transaction_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    last_interest_paid_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    daily_withdraw_limit: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    daily_withdraw_count_limit: Mapped[int | None] = mapped_column(Integer)
    atm_withdraw_limit: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    is_online_banking_enabled: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="Y")
    is_mobile_banking_enabled: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="Y")
    is_phone_banking_enabled: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    account_status: Mapped[AccountStatus] = mapped_column(enum_col(AccountStatus, "account_status_enum"), nullable=False, default=AccountStatus.ACTIVE)
    account_alias: Mapped[str | None] = mapped_column(String(100))
    opened_at: Mapped[str] = mapped_column(CHAR(8), nullable=False)
    maturity_at: Mapped[str | None] = mapped_column(CHAR(8))
    dormant_at: Mapped[str | None] = mapped_column(CHAR(8))
    closed_at: Mapped[str | None] = mapped_column(CHAR(8))


class InterestHistory(Base, AuditMixin):
    __tablename__ = "deposit_interest_history"

    interest_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    contract_id: Mapped[int] = mapped_column(ForeignKey("deposit_contracts.contract_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    account_id: Mapped[int] = mapped_column(ForeignKey("deposit_accounts.account_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    applied_interest_rate: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False)
    interest_before_tax: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False)
    interest_tax_amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False, default=0)
    local_income_tax_amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False, default=0)
    interest_after_tax: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False)
    interest_reason: Mapped[InterestReason] = mapped_column(enum_col(InterestReason, "interest_reason_enum"), nullable=False)
    interest_calculation_start_date: Mapped[date | None] = mapped_column(Date)
    interest_calculation_end_date: Mapped[date | None] = mapped_column(Date)
    interest_paid_at: Mapped[datetime] = mapped_column(TSTZ3, nullable=False, default=datetime.utcnow)


class Transaction(Base, AuditMixin):
    __tablename__ = "deposit_transactions"

    transaction_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    transaction_number: Mapped[str] = mapped_column(String(50), nullable=False, unique=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("deposit_accounts.account_id", ondelete="RESTRICT", onupdate="CASCADE"), nullable=False)
    contract_id: Mapped[int | None] = mapped_column(ForeignKey("deposit_contracts.contract_id", ondelete="RESTRICT", onupdate="CASCADE"))
    transaction_type: Mapped[TransactionType] = mapped_column(enum_col(TransactionType, "transaction_type_enum"), nullable=False)
    direction_type: Mapped[DirectionType] = mapped_column(enum_col(DirectionType, "direction_type_enum"), nullable=False)
    amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False)
    balance_before: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False)
    balance_after: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False)
    available_balance_after: Mapped[Decimal | None] = mapped_column(Numeric(18, 2))
    fee_amount: Mapped[Decimal] = mapped_column(Numeric(18, 2), nullable=False, default=0)
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="KRW")
    status: Mapped[TransactionStatus] = mapped_column(enum_col(TransactionStatus, "transaction_status_enum"), nullable=False, default=TransactionStatus.SUCCESS)
    channel_type: Mapped[TransactionChannel] = mapped_column(enum_col(TransactionChannel, "transaction_channel_enum"), nullable=False)
    ip_address: Mapped[str | None] = mapped_column(String(45))
    terminal_id: Mapped[str | None] = mapped_column(String(50))
    transaction_location: Mapped[str | None] = mapped_column(String(100))
    transaction_memo: Mapped[str | None] = mapped_column(String(255))
    transaction_summary: Mapped[str | None] = mapped_column(String(100))
    transaction_at: Mapped[datetime] = mapped_column(TSTZ3, nullable=False, default=datetime.utcnow)
    posted_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    canceled_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    depositor_customer_id: Mapped[str | None] = mapped_column(String(30))
    depositor_name: Mapped[str | None] = mapped_column(String(100))
    delegate_customer_id: Mapped[str | None] = mapped_column(String(30))
    delegate_customer_name: Mapped[str | None] = mapped_column(String(100))
    transfer_type: Mapped[TransferType | None] = mapped_column(enum_col(TransferType, "transfer_type_enum"))
    counterparty_bank_code: Mapped[str | None] = mapped_column(String(10))
    counterparty_bank_name: Mapped[str | None] = mapped_column(String(100))
    counterparty_account_no: Mapped[str | None] = mapped_column(String(30))
    counterparty_account_id: Mapped[int | None] = mapped_column(ForeignKey("deposit_accounts.account_id", ondelete="RESTRICT", onupdate="CASCADE"))
    counterparty_customer_id: Mapped[str | None] = mapped_column(String(30))
    counterparty_name: Mapped[str | None] = mapped_column(String(100))
    counterparty_name_verified_yn: Mapped[str | None] = mapped_column(CHAR(1))
    transfer_requested_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    transfer_completed_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    payment_method: Mapped[PaymentMethod | None] = mapped_column(enum_col(PaymentMethod, "payment_method_enum"))
    merchant_id: Mapped[str | None] = mapped_column(String(50))
    merchant_name: Mapped[str | None] = mapped_column(String(100))
    approval_number: Mapped[str | None] = mapped_column(String(50))
    external_transaction_no: Mapped[str | None] = mapped_column(String(100))
    payment_round: Mapped[int | None] = mapped_column(Integer)
    original_transaction_id: Mapped[int | None] = mapped_column(ForeignKey("deposit_transactions.transaction_id", ondelete="RESTRICT", onupdate="CASCADE"))
    failure_type: Mapped[FailureType | None] = mapped_column(enum_col(FailureType, "failure_type_enum"))
    failure_code: Mapped[str | None] = mapped_column(String(50))
    failure_reason_code: Mapped[FailureReasonCode | None] = mapped_column(enum_col(FailureReasonCode, "failure_reason_code_enum"))
    failure_at: Mapped[datetime | None] = mapped_column(TSTZ3)
    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    __table_args__ = (CheckConstraint("amount > 0 AND balance_before >= 0 AND balance_after >= 0", name="ck_transactions_amounts"),)


class TermApplicationManagement(Base):
    __tablename__ = "deposit_term_application_management"

    term_application_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    common_term_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    term_target_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    business_type_code: Mapped[str] = mapped_column(String(10), nullable=False)
    is_required: Mapped[str] = mapped_column(CHAR(1), nullable=False, default="N")
    registered_at: Mapped[str | None] = mapped_column(CHAR(8))
    modified_at: Mapped[str | None] = mapped_column(CHAR(8))

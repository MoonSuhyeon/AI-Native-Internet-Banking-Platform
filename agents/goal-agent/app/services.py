from datetime import datetime
from decimal import Decimal

from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import models
from app.utils import new_number, today_text


def is_sqlite(db: Session) -> bool:
    return db.bind is not None and db.bind.dialect.name == "sqlite"


def next_id(db: Session, model, pk_name: str) -> int:
    current = db.scalar(select(func.max(getattr(model, pk_name)))) or 0
    return int(current) + 1


def get_account(db: Session, account_id: int) -> models.Account:
    account = db.get(models.Account, account_id)
    if not account:
        raise HTTPException(status_code=404, detail="account not found")
    if account.account_status != models.AccountStatus.ACTIVE:
        raise HTTPException(status_code=400, detail="account is not active")
    return account


def create_contract_with_account(db: Session, payload: dict) -> models.Contract:
    product = db.get(models.BankingProduct, payload["banking_product_id"])
    if not product:
        raise HTTPException(status_code=404, detail="banking_product not found")

    contract = models.Contract(
        contract_id=payload.get("contract_id") or (next_id(db, models.Contract, "contract_id") if is_sqlite(db) else None),
        contract_number=payload.get("contract_number") or new_number("CTR"),
        customer_id=payload["customer_id"],
        banking_product_id=product.banking_product_id,
        contract_interest_rate=payload.get("contract_interest_rate", product.base_interest_rate),
        total_preferential_rate=payload.get("total_preferential_rate", 0),
        final_interest_rate=payload.get("final_interest_rate", product.base_interest_rate),
        tax_benefit_type=payload.get("tax_benefit_type", models.TaxBenefitType.GENERAL),
        applied_tax_rate=payload.get("applied_tax_rate", 15.4),
        contract_period_month=payload["contract_period_month"],
        started_at=payload.get("started_at") or today_text(),
        maturity_at=payload["maturity_at"],
        contract_status=models.ContractStatus.ACTIVE,
        join_channel=payload.get("join_channel", models.JoinChannel.WEB),
        branch_id=payload.get("branch_id"),
        manager_id=payload.get("manager_id"),
        is_auto_renewal=payload.get("is_auto_renewal", "N"),
        auto_transfer_enabled=payload.get("auto_transfer_enabled", "N"),
        auto_transfer_day=payload.get("auto_transfer_day"),
        is_proxy_joined=payload.get("is_proxy_joined", "N"),
        is_power_of_attorney_verified=payload.get("is_power_of_attorney_verified", "N"),
        power_of_attorney_file_url=payload.get("power_of_attorney_file_url"),
        terms_file_url=payload.get("terms_file_url"),
        contract_file_url=payload.get("contract_file_url"),
    )
    db.add(contract)
    db.flush()

    account = models.Account(
        account_id=payload.get("account_id") or (next_id(db, models.Account, "account_id") if is_sqlite(db) else None),
        account_number=payload.get("account_number") or new_number("ACC"),
        customer_id=contract.customer_id,
        contract_id=contract.contract_id,
        account_type=product.deposit_product_type,
        saving_type=payload.get("saving_type"),
        balance=payload.get("initial_balance", 0),
        opened_at=contract.started_at,
        maturity_at=contract.maturity_at,
    )
    db.add(account)
    db.commit()
    db.refresh(contract)
    return contract


def record_transaction(db: Session, account: models.Account, payload: dict, *, tx_type: models.TransactionType, direction: models.DirectionType, amount: Decimal) -> models.Transaction:
    before = Decimal(account.balance)
    after = before + amount if direction == models.DirectionType.IN else before - amount
    if after < 0:
        raise HTTPException(status_code=400, detail="insufficient balance")

    account.balance = after
    account.last_transaction_at = datetime.utcnow()

    tx = models.Transaction(
        transaction_id=payload.get("transaction_id") or (next_id(db, models.Transaction, "transaction_id") if is_sqlite(db) else None),
        transaction_number=payload.get("transaction_number") or new_number("TXN"),
        account_id=account.account_id,
        contract_id=payload.get("contract_id"),
        transaction_type=tx_type,
        direction_type=direction,
        amount=amount,
        balance_before=before,
        balance_after=after,
        available_balance_after=after,
        fee_amount=payload.get("fee_amount", 0),
        status=models.TransactionStatus.SUCCESS,
        channel_type=payload.get("channel_type", models.TransactionChannel.INTERNET),
        ip_address=payload.get("ip_address"),
        terminal_id=payload.get("terminal_id"),
        transaction_location=payload.get("transaction_location"),
        transaction_memo=payload.get("transaction_memo"),
        transaction_summary=payload.get("transaction_summary"),
        transaction_at=payload.get("transaction_at") or datetime.utcnow(),
        posted_at=datetime.utcnow(),
        depositor_customer_id=payload.get("depositor_customer_id"),
        depositor_name=payload.get("depositor_name"),
        delegate_customer_id=payload.get("delegate_customer_id"),
        delegate_customer_name=payload.get("delegate_customer_name"),
        payment_round=payload.get("payment_round"),
        payment_method=payload.get("payment_method"),
        merchant_id=payload.get("merchant_id"),
        merchant_name=payload.get("merchant_name"),
        approval_number=payload.get("approval_number"),
        external_transaction_no=payload.get("external_transaction_no"),
    )
    db.add(tx)
    return tx


def deposit(db: Session, payload: dict) -> models.Transaction:
    account = get_account(db, payload["account_id"])
    tx = record_transaction(db, account, payload, tx_type=models.TransactionType.DEPOSIT, direction=models.DirectionType.IN, amount=Decimal(str(payload["amount"])))
    db.commit()
    db.refresh(tx)
    return tx


def withdraw(db: Session, payload: dict) -> models.Transaction:
    account = get_account(db, payload["account_id"])
    tx = record_transaction(db, account, payload, tx_type=models.TransactionType.WITHDRAW, direction=models.DirectionType.OUT, amount=Decimal(str(payload["amount"])))
    db.commit()
    db.refresh(tx)
    return tx


def transfer(db: Session, payload: dict) -> models.Transaction:
    source = get_account(db, payload["account_id"])
    amount = Decimal(str(payload["amount"]))
    out_tx = record_transaction(db, source, payload, tx_type=models.TransactionType.TRANSFER, direction=models.DirectionType.OUT, amount=amount)
    out_tx.transfer_type = payload.get("transfer_type", models.TransferType.INTERNAL)
    out_tx.counterparty_account_id = payload.get("counterparty_account_id")
    out_tx.counterparty_account_no = payload.get("counterparty_account_no")
    out_tx.counterparty_bank_code = payload.get("counterparty_bank_code")
    out_tx.counterparty_bank_name = payload.get("counterparty_bank_name")
    out_tx.counterparty_customer_id = payload.get("counterparty_customer_id")
    out_tx.counterparty_name = payload.get("counterparty_name")
    out_tx.counterparty_name_verified_yn = payload.get("counterparty_name_verified_yn")
    out_tx.transfer_requested_at = datetime.utcnow()
    out_tx.transfer_completed_at = datetime.utcnow()

    if out_tx.counterparty_account_id:
        target = get_account(db, out_tx.counterparty_account_id)
        record_transaction(db, target, payload | {"transaction_summary": "internal transfer received"}, tx_type=models.TransactionType.TRANSFER, direction=models.DirectionType.IN, amount=amount)

    db.commit()
    db.refresh(out_tx)
    return out_tx


def payment(db: Session, payload: dict) -> models.Transaction:
    account = get_account(db, payload["account_id"])
    tx = record_transaction(db, account, payload, tx_type=models.TransactionType.PAYMENT, direction=models.DirectionType.OUT, amount=Decimal(str(payload["amount"])))
    db.commit()
    db.refresh(tx)
    return tx


def savings_payment(db: Session, payload: dict) -> models.Transaction:
    account = get_account(db, payload["account_id"])
    tx = record_transaction(db, account, payload, tx_type=models.TransactionType.SAVINGS_PAYMENT, direction=models.DirectionType.OUT, amount=Decimal(str(payload["amount"])))
    account.total_paid_amount = Decimal(account.total_paid_amount) + Decimal(str(payload["amount"]))
    db.commit()
    db.refresh(tx)
    return tx


def reverse_transaction(db: Session, transaction_id: int, payload: dict) -> models.Transaction:
    original = db.get(models.Transaction, transaction_id)
    if not original:
        raise HTTPException(status_code=404, detail="transaction not found")
    if original.status == models.TransactionStatus.CANCELED:
        raise HTTPException(status_code=400, detail="transaction already canceled")
    account = get_account(db, original.account_id)
    direction = models.DirectionType.OUT if original.direction_type == models.DirectionType.IN else models.DirectionType.IN
    reversal = record_transaction(
        db,
        account,
        payload | {"contract_id": original.contract_id, "transaction_summary": "reversal"},
        tx_type=models.TransactionType.REVERSAL,
        direction=direction,
        amount=Decimal(original.amount),
    )
    reversal.original_transaction_id = original.transaction_id
    original.status = models.TransactionStatus.CANCELED
    original.canceled_at = datetime.utcnow()
    db.commit()
    db.refresh(reversal)
    return reversal


def pay_interest(db: Session, payload: dict) -> models.InterestHistory:
    account = get_account(db, payload["account_id"])
    contract_id = payload.get("contract_id") or account.contract_id
    interest_amount = payload.get("interest_amount", payload.get("interest_after_tax"))
    if interest_amount is None:
        raise HTTPException(status_code=400, detail="interest_amount is required")
    interest_after_tax = Decimal(str(interest_amount))
    history_payload = {
        "contract_id": contract_id,
        "account_id": account.account_id,
        "applied_interest_rate": payload.get("applied_interest_rate", payload.get("interest_rate", 0)),
        "interest_before_tax": payload.get("interest_before_tax", interest_after_tax),
        "interest_tax_amount": payload.get("interest_tax_amount", 0),
        "local_income_tax_amount": payload.get("local_income_tax_amount", 0),
        "interest_after_tax": payload.get("interest_after_tax", interest_after_tax),
        "interest_reason": payload.get("interest_reason", "REGULAR_INTEREST"),
    }
    before = Decimal(account.balance)
    account.balance = before + interest_after_tax
    account.total_interest_amount = Decimal(account.total_interest_amount) + interest_after_tax
    account.last_interest_paid_at = datetime.utcnow()
    history = models.InterestHistory(**history_payload)
    if is_sqlite(db) and not getattr(history, "interest_id", None):
        history.interest_id = next_id(db, models.InterestHistory, "interest_id")
    db.add(history)
    db.flush()
    db.add(
        models.Transaction(
            transaction_id=next_id(db, models.Transaction, "transaction_id") if is_sqlite(db) else None,
            transaction_number=new_number("INT"),
            account_id=account.account_id,
            contract_id=contract_id,
            transaction_type=models.TransactionType.INTEREST,
            direction_type=models.DirectionType.IN,
            amount=interest_after_tax,
            balance_before=before,
            balance_after=account.balance,
            available_balance_after=account.balance,
            channel_type=models.TransactionChannel.SYSTEM,
            transaction_at=datetime.utcnow(),
            posted_at=datetime.utcnow(),
            transaction_summary="interest paid",
        )
    )
    db.commit()
    db.refresh(history)
    return history

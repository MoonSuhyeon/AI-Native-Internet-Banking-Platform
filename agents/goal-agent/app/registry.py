from app import models


TABLES = {
    "deposit_departments": (models.Department, "department_id"),
    "deposit_banking_products": (models.BankingProduct, "banking_product_id"),
    "banking_deposit_products": (models.DepositProduct, "deposit_product_id"),
    "deposit_savings_products": (models.SavingsProduct, "savings_product_id"),
    "deposit_subscription_products": (models.SubscriptionProduct, "subscription_product_id"),
    "banking_deposit_product_join_channels": (models.DepositProductJoinChannel, "channel_id"),
    "deposit_target_groups": (models.TargetGroup, "target_group_id"),
    "banking_deposit_product_target_groups": (models.DepositProductTargetGroup, "deposit_product_target_group_id"),
    "banking_deposit_product_interest_rates": (models.DepositProductInterestRate, "rate_id"),
    "deposit_special_terms": (models.SpecialTerm, "special_term_id"),
    "banking_deposit_product_special_terms": (models.DepositProductSpecialTerm, "deposit_product_special_term_id"),
    "deposit_contracts": (models.Contract, "contract_id"),
    "deposit_contract_applied_rates": (models.ContractAppliedRate, "applied_rate_id"),
    "deposit_contract_special_term_agreements": (models.ContractSpecialTermAgreement, "agreement_id"),
    "deposit_subscription_payment_recognition_history": (models.SubscriptionPaymentRecognitionHistory, "recognition_id"),
    "deposit_accounts": (models.Account, "account_id"),
    "deposit_interest_history": (models.InterestHistory, "interest_id"),
    "deposit_transactions": (models.Transaction, "transaction_id"),
    "deposit_term_application_management": (models.TermApplicationManagement, "term_application_id"),
}

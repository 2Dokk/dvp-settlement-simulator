package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "cash_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    @Column(nullable = false)
    private Long version;

    public CashAccount(Long participantId, String currency, BigDecimal openingBalance) {
        this.participantId = participantId;
        this.currency = currency;
        this.balance = openingBalance;
    }

    /**
     * Caller must hold a pessimistic lock on this row (see CashAccountRepository#lockById)
     * before calling debit/credit within the same transaction, otherwise this check
     * is not safe against concurrent updates.
     */
    public void debit(BigDecimal amount) {
        BigDecimal result = this.balance.subtract(amount);
        if (result.signum() < 0) {
            throw new InsufficientBalanceException(
                    "cash account %d has insufficient balance: balance=%s, requested=%s"
                            .formatted(id, balance, amount));
        }
        this.balance = result;
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /**
     * Debits without the non-negative guard. Reserved for the CCP's own account during netted
     * batch settlement — see SettlementService for why the CCP is allowed to run temporarily
     * negative within a batch even though every other participant is not.
     */
    public void debitUnchecked(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }
}

package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Double-entry audit trail. Every settled instruction produces exactly four entries:
 * seller SECURITIES debit, buyer SECURITIES credit, buyer CASH debit, seller CASH credit.
 * account_id references either cash_accounts.id or securities_holdings.id depending on
 * account_type (polymorphic — no FK constraint, resolved by application code).
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private EntryType entryType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "related_instruction_id", nullable = false)
    private Long relatedInstructionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntry(AccountType accountType, Long accountId, EntryType entryType,
                        BigDecimal amount, Long relatedInstructionId) {
        this.accountType = accountType;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.relatedInstructionId = relatedInstructionId;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}

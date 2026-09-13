package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "securities_holdings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecurityHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(nullable = false)
    private long quantity;

    @Version
    @Column(nullable = false)
    private Long version;

    public SecurityHolding(Long participantId, Long instrumentId, long openingQuantity) {
        this.participantId = participantId;
        this.instrumentId = instrumentId;
        this.quantity = openingQuantity;
    }

    /**
     * Caller must hold a pessimistic lock on this row (see SecurityHoldingRepository#lockById)
     * before calling debit/credit within the same transaction.
     */
    public void debit(long amount) {
        long result = this.quantity - amount;
        if (result < 0) {
            throw new InsufficientBalanceException(
                    "securities holding %d has insufficient quantity: quantity=%d, requested=%d"
                            .formatted(id, quantity, amount));
        }
        this.quantity = result;
    }

    public void credit(long amount) {
        this.quantity += amount;
    }
}

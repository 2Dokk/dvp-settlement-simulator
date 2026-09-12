package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The atomic unit of DVP execution: move `quantity` of `instrumentId` from one participant
 * to another, against `amount` of cash moving the other way. Either both movements happen
 * (status SETTLED) or neither does (status FAILED) — see SettlementService.
 */
@Entity
@Table(name = "settlement_instructions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "trade_id")
    private Long tradeId;

    /** Securities seller / cash receiver. */
    @Column(name = "from_participant_id", nullable = false)
    private Long fromParticipantId;

    /** Securities buyer / cash payer. */
    @Column(name = "to_participant_id", nullable = false)
    private Long toParticipantId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InstructionStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    public static SettlementInstruction forTrade(Trade trade) {
        SettlementInstruction instruction = new SettlementInstruction();
        instruction.tradeId = trade.getId();
        instruction.fromParticipantId = trade.getSellerId();
        instruction.toParticipantId = trade.getBuyerId();
        instruction.instrumentId = trade.getInstrumentId();
        instruction.quantity = trade.getQuantity();
        instruction.amount = trade.getAmount();
        instruction.status = InstructionStatus.PENDING;
        return instruction;
    }

    public static SettlementInstruction forNetPositions(Long batchId, Long fromParticipantId, Long toParticipantId,
                                                          Long instrumentId, long quantity, BigDecimal amount) {
        SettlementInstruction instruction = new SettlementInstruction();
        instruction.batchId = batchId;
        instruction.fromParticipantId = fromParticipantId;
        instruction.toParticipantId = toParticipantId;
        instruction.instrumentId = instrumentId;
        instruction.quantity = quantity;
        instruction.amount = amount;
        instruction.status = InstructionStatus.PENDING;
        return instruction;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markSettled() {
        this.status = InstructionStatus.SETTLED;
        this.settledAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = InstructionStatus.FAILED;
        this.failureReason = reason;
        this.settledAt = Instant.now();
    }
}

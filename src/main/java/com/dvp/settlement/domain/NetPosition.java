package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "net_positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NetPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    /** Net quantity to receive (positive) or deliver (negative) for this participant/instrument. */
    @Column(name = "net_quantity", nullable = false)
    private long netQuantity;

    /** Net cash amount to receive (positive) or pay (negative), mirrors netQuantity's sign. */
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    public NetPosition(Long batchId, Long participantId, Long instrumentId, long netQuantity, BigDecimal netAmount) {
        this.batchId = batchId;
        this.participantId = participantId;
        this.instrumentId = instrumentId;
        this.netQuantity = netQuantity;
        this.netAmount = netAmount;
    }
}

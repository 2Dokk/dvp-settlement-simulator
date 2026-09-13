package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "trades")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TradeStatus status;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Trade(Long buyerId, Long sellerId, Long instrumentId, long quantity,
                 BigDecimal price, LocalDate tradeDate, LocalDate settlementDate) {
        if (buyerId.equals(sellerId)) {
            throw new IllegalArgumentException("buyer and seller must differ");
        }
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.instrumentId = instrumentId;
        this.quantity = quantity;
        this.price = price;
        this.amount = price.multiply(BigDecimal.valueOf(quantity));
        this.tradeDate = tradeDate;
        this.settlementDate = settlementDate;
        this.status = TradeStatus.PENDING;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markSettled() {
        this.status = TradeStatus.SETTLED;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = TradeStatus.FAILED;
        this.failureReason = reason;
    }
}

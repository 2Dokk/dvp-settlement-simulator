package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "settlement_batches")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public SettlementBatch(LocalDate settlementDate) {
        this.settlementDate = settlementDate;
        this.status = BatchStatus.CREATED;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}

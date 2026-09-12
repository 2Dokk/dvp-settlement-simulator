package com.dvp.settlement.repository;

import com.dvp.settlement.domain.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    /**
     * Atomically claims every still-PENDING trade for a settlement date into this batch by
     * flipping status PENDING -> NETTED and stamping batch_id, in one conditional UPDATE.
     * A trade inserted concurrently, after this statement's snapshot, simply won't match
     * status = 'PENDING'... AND is picked up by the next batch run instead — no lost or
     * double-claimed trades.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update Trade t set t.status = com.dvp.settlement.domain.TradeStatus.NETTED, t.batchId = :batchId
            where t.settlementDate = :settlementDate and t.status = com.dvp.settlement.domain.TradeStatus.PENDING
            """)
    int claimPendingForBatch(@Param("settlementDate") LocalDate settlementDate, @Param("batchId") Long batchId);

    List<Trade> findByBatchId(Long batchId);
}

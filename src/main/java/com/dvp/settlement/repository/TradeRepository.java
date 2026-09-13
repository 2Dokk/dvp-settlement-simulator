package com.dvp.settlement.repository;

import com.dvp.settlement.domain.Trade;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    /**
     * Locks the trade row. Both settlement paths take trade locks before account locks (the batch
     * path via claimPendingForBatch's UPDATE), so a direct settlement and a batch can't deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trade t where t.id = :id")
    Optional<Trade> lockById(@Param("id") Long id);
}

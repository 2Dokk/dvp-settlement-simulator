package com.dvp.settlement.repository;

import com.dvp.settlement.domain.SettlementInstruction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, Long> {
    List<SettlementInstruction> findByBatchId(Long batchId);

    /** Guards against the same instruction being executed twice concurrently. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from SettlementInstruction i where i.id = :id")
    Optional<SettlementInstruction> lockById(@Param("id") Long id);
}

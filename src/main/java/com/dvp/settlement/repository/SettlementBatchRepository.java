package com.dvp.settlement.repository;

import com.dvp.settlement.domain.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {
}

package com.dvp.settlement.repository;

import com.dvp.settlement.domain.NetPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NetPositionRepository extends JpaRepository<NetPosition, Long> {
    List<NetPosition> findByBatchId(Long batchId);
}

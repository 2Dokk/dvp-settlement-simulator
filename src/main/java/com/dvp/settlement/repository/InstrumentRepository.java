package com.dvp.settlement.repository;

import com.dvp.settlement.domain.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
    Optional<Instrument> findByIsin(String isin);
}

package com.dvp.settlement.repository;

import com.dvp.settlement.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {
    Optional<Participant> findByName(String name);
}

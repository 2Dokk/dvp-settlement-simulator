package com.dvp.settlement.repository;

import com.dvp.settlement.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {
    Optional<Participant> findByName(String name);

    /** Relies on the partial unique index ux_participants_ccp, so concurrent callers create one CCP. */
    @Modifying
    @Query(value = """
            insert into participants (name, created_at) values ('CCP', now())
            on conflict (name) where name = 'CCP' do nothing
            """, nativeQuery = true)
    void insertCcpIfAbsent();
}

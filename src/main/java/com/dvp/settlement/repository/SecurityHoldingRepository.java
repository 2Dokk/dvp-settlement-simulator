package com.dvp.settlement.repository;

import com.dvp.settlement.domain.SecurityHolding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SecurityHoldingRepository extends JpaRepository<SecurityHolding, Long> {

    Optional<SecurityHolding> findByParticipantIdAndInstrumentId(Long participantId, Long instrumentId);

    /** Id only — see CashAccountRepository#findIdByParticipantIdAndCurrency for why. */
    @Query("select h.id from SecurityHolding h where h.participantId = :participantId and h.instrumentId = :instrumentId")
    Optional<Long> findIdByParticipantIdAndInstrumentId(@Param("participantId") Long participantId,
                                                        @Param("instrumentId") Long instrumentId);

    /** Race-free provisioning: two transactions creating the same holding can't both insert. */
    @Modifying
    @Query(value = """
            insert into securities_holdings (participant_id, instrument_id, quantity, version)
            values (:participantId, :instrumentId, 0, 0)
            on conflict (participant_id, instrument_id) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("participantId") Long participantId, @Param("instrumentId") Long instrumentId);

    List<SecurityHolding> findByParticipantId(Long participantId);

    /**
     * Acquires a row-level pessimistic write lock (SELECT ... FOR UPDATE). Callers must
     * always lock accounts in ascending id order across a settlement to avoid deadlocks —
     * see SettlementService.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SecurityHolding h where h.id = :id")
    Optional<SecurityHolding> lockById(@Param("id") Long id);
}

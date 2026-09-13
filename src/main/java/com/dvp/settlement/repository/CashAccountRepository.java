package com.dvp.settlement.repository;

import com.dvp.settlement.domain.CashAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CashAccountRepository extends JpaRepository<CashAccount, Long> {

    Optional<CashAccount> findByParticipantIdAndCurrency(Long participantId, String currency);

    /** Race-free provisioning: two transactions creating the same account can't both insert. */
    @Modifying
    @Query(value = """
            insert into cash_accounts (participant_id, currency, balance, version)
            values (:participantId, :currency, 0, 0)
            on conflict (participant_id, currency) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("participantId") Long participantId, @Param("currency") String currency);

    /**
     * Acquires a row-level pessimistic write lock (SELECT ... FOR UPDATE). Callers must
     * always lock accounts in ascending id order across a settlement to avoid deadlocks —
     * see SettlementService.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAccount a where a.id = :id")
    Optional<CashAccount> lockById(@Param("id") Long id);
}

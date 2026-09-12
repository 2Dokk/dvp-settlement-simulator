package com.dvp.settlement.repository;

import com.dvp.settlement.domain.AccountType;
import com.dvp.settlement.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByAccountTypeAndAccountIdOrderByCreatedAtAsc(AccountType accountType, Long accountId);
}

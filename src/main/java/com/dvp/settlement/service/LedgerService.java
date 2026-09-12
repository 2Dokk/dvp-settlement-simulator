package com.dvp.settlement.service;

import com.dvp.settlement.domain.AccountType;
import com.dvp.settlement.domain.EntryType;
import com.dvp.settlement.domain.LedgerEntry;
import com.dvp.settlement.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Records the four-entry double-entry trail for one settled DVP instruction:
     * securities move seller -> buyer, cash moves buyer -> seller.
     */
    public void recordSettlement(Long instructionId, Long sellerSecurityHoldingId, Long buyerSecurityHoldingId,
                                  Long buyerCashAccountId, Long sellerCashAccountId,
                                  long quantity, BigDecimal amount) {
        BigDecimal qty = BigDecimal.valueOf(quantity);
        ledgerEntryRepository.save(new LedgerEntry(AccountType.SECURITIES, sellerSecurityHoldingId, EntryType.DEBIT, qty, instructionId));
        ledgerEntryRepository.save(new LedgerEntry(AccountType.SECURITIES, buyerSecurityHoldingId, EntryType.CREDIT, qty, instructionId));
        ledgerEntryRepository.save(new LedgerEntry(AccountType.CASH, buyerCashAccountId, EntryType.DEBIT, amount, instructionId));
        ledgerEntryRepository.save(new LedgerEntry(AccountType.CASH, sellerCashAccountId, EntryType.CREDIT, amount, instructionId));
    }
}

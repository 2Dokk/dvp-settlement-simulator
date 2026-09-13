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
        ledgerEntryRepository.save(LedgerEntry.forInstruction(AccountType.SECURITIES, sellerSecurityHoldingId, EntryType.DEBIT, qty, instructionId));
        ledgerEntryRepository.save(LedgerEntry.forInstruction(AccountType.SECURITIES, buyerSecurityHoldingId, EntryType.CREDIT, qty, instructionId));
        ledgerEntryRepository.save(LedgerEntry.forInstruction(AccountType.CASH, buyerCashAccountId, EntryType.DEBIT, amount, instructionId));
        ledgerEntryRepository.save(LedgerEntry.forInstruction(AccountType.CASH, sellerCashAccountId, EntryType.CREDIT, amount, instructionId));
    }

    /**
     * Books one net position against the CCP. The securities and cash legs are signed
     * independently, so a position that receives both securities and cash (possible when a
     * participant bought and sold the same instrument at different prices) is recorded correctly.
     * Zero legs produce no entries.
     *
     * @param netQuantity positive = participant receives securities
     * @param netAmount   positive = participant pays cash
     */
    public void recordNetPosition(Long netPositionId, Long participantHoldingId, Long ccpHoldingId,
                                  Long participantCashId, Long ccpCashId, long netQuantity, BigDecimal netAmount) {
        if (netQuantity != 0) {
            BigDecimal qty = BigDecimal.valueOf(Math.abs(netQuantity));
            EntryType participantSide = netQuantity > 0 ? EntryType.CREDIT : EntryType.DEBIT;
            ledgerEntryRepository.save(LedgerEntry.forNetPosition(AccountType.SECURITIES, participantHoldingId, participantSide, qty, netPositionId));
            ledgerEntryRepository.save(LedgerEntry.forNetPosition(AccountType.SECURITIES, ccpHoldingId, opposite(participantSide), qty, netPositionId));
        }
        if (netAmount.signum() != 0) {
            EntryType participantSide = netAmount.signum() > 0 ? EntryType.DEBIT : EntryType.CREDIT;
            ledgerEntryRepository.save(LedgerEntry.forNetPosition(AccountType.CASH, participantCashId, participantSide, netAmount.abs(), netPositionId));
            ledgerEntryRepository.save(LedgerEntry.forNetPosition(AccountType.CASH, ccpCashId, opposite(participantSide), netAmount.abs(), netPositionId));
        }
    }

    private static EntryType opposite(EntryType type) {
        return type == EntryType.DEBIT ? EntryType.CREDIT : EntryType.DEBIT;
    }
}

package com.dvp.settlement.settlement;

import com.dvp.settlement.domain.AccountType;
import com.dvp.settlement.domain.EntryType;
import com.dvp.settlement.domain.LedgerEntry;
import com.dvp.settlement.domain.SecurityHolding;
import com.dvp.settlement.repository.CashAccountRepository;
import com.dvp.settlement.repository.LedgerEntryRepository;
import com.dvp.settlement.repository.SecurityHoldingRepository;
import com.dvp.settlement.service.AccountService;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class LedgerAssertions {

    private LedgerAssertions() {
    }

    /** Every CCP account's ledger debits equal its credits: the CCP carries no residual. */
    static void assertCcpLedgerBalances(LedgerEntryRepository ledger, CashAccountRepository cash,
                                        SecurityHoldingRepository holdings, Long ccpId) {
        Long ccpCashId = cash.findByParticipantIdAndCurrency(ccpId, AccountService.DEFAULT_CURRENCY).orElseThrow().getId();
        assertThat(net(ledger.findByAccountTypeAndAccountIdOrderByCreatedAtAsc(AccountType.CASH, ccpCashId)))
                .isEqualByComparingTo(BigDecimal.ZERO);
        for (SecurityHolding h : holdings.findByParticipantId(ccpId)) {
            assertThat(net(ledger.findByAccountTypeAndAccountIdOrderByCreatedAtAsc(AccountType.SECURITIES, h.getId())))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    private static BigDecimal net(List<LedgerEntry> entries) {
        return entries.stream()
                .map(e -> e.getEntryType() == EntryType.CREDIT ? e.getAmount() : e.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

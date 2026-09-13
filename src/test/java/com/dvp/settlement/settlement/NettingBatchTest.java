package com.dvp.settlement.settlement;

import com.dvp.settlement.concurrency.AbstractIntegrationTest;
import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.AccountService;
import com.dvp.settlement.service.BatchSettlementService;
import com.dvp.settlement.service.TradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A buys 100 from B, B buys 40 from C, C buys 20 from A (same instrument, same day).
 * Net per participant: A +80/+800, B -60/-600, C -20/-200 — sums to zero both ways.
 */
class NettingBatchTest extends AbstractIntegrationTest {

    @Autowired
    private ParticipantRepository participantRepository;
    @Autowired
    private InstrumentRepository instrumentRepository;
    @Autowired
    private TradeRepository tradeRepository;
    @Autowired
    private CashAccountRepository cashAccountRepository;
    @Autowired
    private SecurityHoldingRepository securityHoldingRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TradeService tradeService;
    @Autowired
    private BatchSettlementService batchSettlementService;

    @Test
    void threeWayNettingSettlesAndTheCcpLedgerBalances() {
        Participant a = participantRepository.save(new Participant("NetA"));
        Participant b = participantRepository.save(new Participant("NetB"));
        Participant c = participantRepository.save(new Participant("NetC"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000003", "Netting Corp"));
        LocalDate settlementDate = LocalDate.now().plusDays(1);

        accountService.depositCash(a.getId(), new BigDecimal("10000.00"));
        accountService.depositCash(b.getId(), new BigDecimal("10000.00"));
        accountService.depositCash(c.getId(), new BigDecimal("10000.00"));
        accountService.depositSecurities(b.getId(), instrument.getId(), 100);
        accountService.depositSecurities(c.getId(), instrument.getId(), 40);
        accountService.depositSecurities(a.getId(), instrument.getId(), 20);

        tradeService.registerTrade(a.getId(), b.getId(), instrument.getId(), 100, new BigDecimal("10.00"), LocalDate.now(), settlementDate);
        tradeService.registerTrade(b.getId(), c.getId(), instrument.getId(), 40, new BigDecimal("10.00"), LocalDate.now(), settlementDate);
        tradeService.registerTrade(c.getId(), a.getId(), instrument.getId(), 20, new BigDecimal("10.00"), LocalDate.now(), settlementDate);

        SettlementBatch batch = batchSettlementService.runBatch(settlementDate);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(tradeRepository.findAll()).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TradeStatus.SETTLED));

        assertThat(holdingOf(a.getId(), instrument.getId())).isEqualTo(100);
        assertThat(balanceOf(a.getId())).isEqualByComparingTo("9200.00");
        assertThat(holdingOf(b.getId(), instrument.getId())).isEqualTo(40);
        assertThat(balanceOf(b.getId())).isEqualByComparingTo("10600.00");
        assertThat(holdingOf(c.getId(), instrument.getId())).isEqualTo(20);
        assertThat(balanceOf(c.getId())).isEqualByComparingTo("10200.00");

        Long ccpId = accountService.ccpParticipantId();
        assertThat(holdingOf(ccpId, instrument.getId())).isZero();
        assertThat(balanceOf(ccpId)).isEqualByComparingTo("0.00");
        LedgerAssertions.assertCcpLedgerBalances(ledgerEntryRepository, cashAccountRepository, securityHoldingRepository, ccpId);
    }

    private BigDecimal balanceOf(Long participantId) {
        return cashAccountRepository.findByParticipantIdAndCurrency(participantId, AccountService.DEFAULT_CURRENCY)
                .orElseThrow().getBalance();
    }

    private long holdingOf(Long participantId, Long instrumentId) {
        return securityHoldingRepository.findByParticipantIdAndInstrumentId(participantId, instrumentId)
                .orElseThrow().getQuantity();
    }
}

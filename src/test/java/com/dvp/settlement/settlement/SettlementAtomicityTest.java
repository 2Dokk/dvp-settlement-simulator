package com.dvp.settlement.settlement;

import com.dvp.settlement.concurrency.AbstractIntegrationTest;
import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.AccountService;
import com.dvp.settlement.service.SettlementOrchestrator;
import com.dvp.settlement.service.TradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two halves of the DVP atomicity guarantee: a settlement that can complete moves
 * both legs, and one that can't moves neither.
 */
class SettlementAtomicityTest extends AbstractIntegrationTest {

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
    private SettlementOrchestrator orchestrator;

    @Test
    void successfulSettlementMovesBothLegsTogether() {
        Participant buyer = participantRepository.save(new Participant("AtomicBuyer"));
        Participant seller = participantRepository.save(new Participant("AtomicSeller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000001", "Atomicity Corp"));

        accountService.depositCash(buyer.getId(), new BigDecimal("5000.00"));
        accountService.depositSecurities(seller.getId(), instrument.getId(), 100);

        Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), LocalDate.now());

        SettlementInstruction instruction = orchestrator.settleTradeNow(trade.getId());

        assertThat(instruction.getStatus()).isEqualTo(InstructionStatus.SETTLED);
        assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.SETTLED);

        assertThat(balanceOf(buyer.getId())).isEqualByComparingTo("4500.00"); // 5000 - 50*10
        assertThat(balanceOf(seller.getId())).isEqualByComparingTo("500.00");
        assertThat(holdingOf(buyer.getId(), instrument.getId())).isEqualTo(50);
        assertThat(holdingOf(seller.getId(), instrument.getId())).isEqualTo(50);

        assertThat(ledgerEntryRepository.findAll()).hasSize(4);
    }

    @Test
    void failedSettlementMovesNeitherLeg() {
        Participant buyer = participantRepository.save(new Participant("FailBuyer"));
        Participant seller = participantRepository.save(new Participant("FailSeller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000002", "Shortfall Corp"));

        accountService.depositCash(buyer.getId(), new BigDecimal("5000.00"));
        accountService.depositSecurities(seller.getId(), instrument.getId(), 10); // less than the trade needs

        Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), LocalDate.now());

        SettlementInstruction instruction = orchestrator.settleTradeNow(trade.getId());

        assertThat(instruction.getStatus()).isEqualTo(InstructionStatus.FAILED);
        assertThat(instruction.getFailureReason()).contains("insufficient securities");
        assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.FAILED);

        // Neither leg moved.
        assertThat(balanceOf(buyer.getId())).isEqualByComparingTo("5000.00");
        assertThat(balanceOf(seller.getId())).isEqualByComparingTo("0.00");
        assertThat(holdingOf(buyer.getId(), instrument.getId())).isZero();
        assertThat(holdingOf(seller.getId(), instrument.getId())).isEqualTo(10);

        assertThat(ledgerEntryRepository.findAll()).isEmpty();
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

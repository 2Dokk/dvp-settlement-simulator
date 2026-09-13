package com.dvp.settlement.settlement;

import com.dvp.settlement.concurrency.AbstractIntegrationTest;
import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.AccountService;
import com.dvp.settlement.service.BatchSettlementService;
import com.dvp.settlement.service.SettlementOrchestrator;
import com.dvp.settlement.service.SettlementService;
import com.dvp.settlement.service.TradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deliberately induced failures, to prove the system behaves the way the design docs claim
 * rather than just the way the happy path looks.
 */
class FailureScenarioTest extends AbstractIntegrationTest {

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
    private SettlementService settlementService;
    @Autowired
    private SettlementOrchestrator orchestrator;
    @Autowired
    private BatchSettlementService batchSettlementService;

    @Test
    void insufficientCashLeavesNeitherLegMoved() {
        Participant buyer = participantRepository.save(new Participant("PoorBuyer"));
        Participant seller = participantRepository.save(new Participant("RichSeller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000004", "Cash Shortfall Corp"));

        accountService.depositCash(buyer.getId(), new BigDecimal("100.00")); // needs 500
        accountService.depositSecurities(seller.getId(), instrument.getId(), 100);

        Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), LocalDate.now());

        SettlementInstruction instruction = orchestrator.settleTradeNow(trade.getId());

        assertThat(instruction.getStatus()).isEqualTo(InstructionStatus.FAILED);
        assertThat(instruction.getFailureReason()).contains("insufficient cash");
        assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.FAILED);

        assertThat(balanceOf(buyer.getId())).isEqualByComparingTo("100.00");
        assertThat(balanceOf(seller.getId())).isEqualByComparingTo("0.00");
        assertThat(holdingOf(seller.getId(), instrument.getId())).isEqualTo(100);
        assertThat(holdingOf(buyer.getId(), instrument.getId())).isZero();
        assertThat(ledgerEntryRepository.findAll()).isEmpty();
    }

    @Test
    void reSettlingAnAlreadySettledInstructionIsANoOp() {
        Participant buyer = participantRepository.save(new Participant("IdemBuyer"));
        Participant seller = participantRepository.save(new Participant("IdemSeller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000005", "Idempotency Corp"));

        accountService.depositCash(buyer.getId(), new BigDecimal("5000.00"));
        accountService.depositSecurities(seller.getId(), instrument.getId(), 100);

        Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), LocalDate.now());

        SettlementInstruction first = orchestrator.settleTradeNow(trade.getId());
        assertThat(first.getStatus()).isEqualTo(InstructionStatus.SETTLED);

        SettlementInstruction second = settlementService.settleInstruction(first.getId());
        assertThat(second.getStatus()).isEqualTo(InstructionStatus.SETTLED);

        assertThat(balanceOf(buyer.getId())).isEqualByComparingTo("4500.00");
        assertThat(balanceOf(seller.getId())).isEqualByComparingTo("500.00");
        assertThat(ledgerEntryRepository.findAll()).hasSize(4);
    }

    @Test
    void settlingAnUnknownInstructionThrows() {
        assertThatThrownBy(() -> settlementService.settleInstruction(-1L))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Regression test for the CCP residual imbalance. Two independent instrument groups share a
     * batch; BX can't pay. Previously SX's delivery to the CCP settled in its own transaction
     * before BX's payment failed, leaving the CCP holding +50 X and -500 cash forever. Now the
     * batch plans first, excludes BX's trade before anything moves, and commits the rest in one
     * transaction.
     */
    @Test
    void partialFailureInABatchLeavesNoCcpResidual() {
        Participant bx = participantRepository.save(new Participant("ShortfallBuyer"));
        Participant sx = participantRepository.save(new Participant("ShortfallSeller"));
        Participant by = participantRepository.save(new Participant("HealthyBuyer"));
        Participant sy = participantRepository.save(new Participant("HealthySeller"));
        Instrument instrumentX = instrumentRepository.save(new Instrument("KR0000000006", "Shortfall Instrument"));
        Instrument instrumentY = instrumentRepository.save(new Instrument("KR0000000007", "Healthy Instrument"));
        LocalDate settlementDate = LocalDate.now().plusDays(3);

        accountService.depositCash(bx.getId(), new BigDecimal("100.00"));  // needs 500
        accountService.depositSecurities(sx.getId(), instrumentX.getId(), 50);
        accountService.depositCash(by.getId(), new BigDecimal("1000.00")); // needs 300
        accountService.depositSecurities(sy.getId(), instrumentY.getId(), 100);

        Trade tradeX = tradeService.registerTrade(bx.getId(), sx.getId(), instrumentX.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), settlementDate);
        Trade tradeY = tradeService.registerTrade(by.getId(), sy.getId(), instrumentY.getId(),
                30, new BigDecimal("10.00"), LocalDate.now(), settlementDate);

        batchSettlementService.runBatch(settlementDate);

        Trade failed = tradeRepository.findById(tradeX.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(TradeStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("participant " + bx.getId()).contains("insufficient cash");
        assertThat(tradeRepository.findById(tradeY.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.SETTLED);

        // The failing group: nothing moved on either side.
        assertThat(holdingOf(sx.getId(), instrumentX.getId())).isEqualTo(50);
        assertThat(balanceOf(sx.getId())).isEqualByComparingTo("0.00");
        assertThat(holdingOf(bx.getId(), instrumentX.getId())).isZero();
        assertThat(balanceOf(bx.getId())).isEqualByComparingTo("100.00");

        // The healthy group settled completely.
        assertThat(holdingOf(sy.getId(), instrumentY.getId())).isEqualTo(70);
        assertThat(balanceOf(sy.getId())).isEqualByComparingTo("300.00");
        assertThat(holdingOf(by.getId(), instrumentY.getId())).isEqualTo(30);
        assertThat(balanceOf(by.getId())).isEqualByComparingTo("700.00");

        Long ccpId = accountService.ccpParticipantId();
        assertThat(holdingOf(ccpId, instrumentX.getId())).isZero();
        assertThat(holdingOf(ccpId, instrumentY.getId())).isZero();
        assertThat(balanceOf(ccpId)).isEqualByComparingTo("0.00");
        LedgerAssertions.assertCcpLedgerBalances(ledgerEntryRepository, cashAccountRepository, securityHoldingRepository, ccpId);
    }

    /**
     * B holds no X but nets to zero (sells 50 to A, buys 50 from C). C can't deliver, so once C
     * is excluded B becomes a net deliverer with nothing to deliver and is excluded too — nothing
     * in the batch may move.
     */
    @Test
    void cascadingExclusionMovesNothing() {
        Participant a = participantRepository.save(new Participant("CascadeA"));
        Participant b = participantRepository.save(new Participant("CascadeB"));
        Participant c = participantRepository.save(new Participant("CascadeC"));
        Instrument x = instrumentRepository.save(new Instrument("KR0000000011", "Cascade Corp"));
        LocalDate settlementDate = LocalDate.now().plusDays(5);

        accountService.depositCash(a.getId(), new BigDecimal("10000.00"));
        accountService.depositCash(b.getId(), new BigDecimal("10000.00"));

        tradeService.registerTrade(a.getId(), b.getId(), x.getId(), 50, new BigDecimal("10.00"), LocalDate.now(), settlementDate);
        tradeService.registerTrade(b.getId(), c.getId(), x.getId(), 50, new BigDecimal("10.00"), LocalDate.now(), settlementDate);

        batchSettlementService.runBatch(settlementDate);

        assertThat(tradeRepository.findAll()).allSatisfy(t -> {
            assertThat(t.getStatus()).isEqualTo(TradeStatus.FAILED);
            assertThat(t.getFailureReason()).isNotBlank();
        });
        assertThat(balanceOf(a.getId())).isEqualByComparingTo("10000.00");
        assertThat(balanceOf(b.getId())).isEqualByComparingTo("10000.00");
        assertThat(ledgerEntryRepository.findAll()).isEmpty();
    }

    /**
     * An instruction is created for a trade, then a batch claims that trade before the
     * instruction runs. The direct path must notice under lock and refuse, not settle it twice.
     */
    @Test
    void directSettlementOfATradeAlreadyClaimedByABatchIsRefused() {
        Participant buyer = participantRepository.save(new Participant("ClaimedBuyer"));
        Participant seller = participantRepository.save(new Participant("ClaimedSeller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000012", "Double Path Corp"));
        LocalDate settlementDate = LocalDate.now().plusDays(6);

        accountService.depositCash(buyer.getId(), new BigDecimal("5000.00"));
        accountService.depositSecurities(seller.getId(), instrument.getId(), 100);

        Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), settlementDate);
        Long instructionId = tradeService.createInstructionForTrade(trade.getId());

        batchSettlementService.runBatch(settlementDate);
        SettlementInstruction instruction = settlementService.settleInstruction(instructionId);

        assertThat(instruction.getStatus()).isEqualTo(InstructionStatus.FAILED);
        assertThat(instruction.getFailureReason()).contains("no longer PENDING");
        assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.SETTLED);
        // Moved exactly once, by the batch.
        assertThat(balanceOf(buyer.getId())).isEqualByComparingTo("4500.00");
        assertThat(holdingOf(buyer.getId(), instrument.getId())).isEqualTo(50);
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

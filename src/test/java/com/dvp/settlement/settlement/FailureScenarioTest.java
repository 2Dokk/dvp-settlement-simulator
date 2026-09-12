package com.dvp.settlement.settlement;

import com.dvp.settlement.concurrency.AbstractIntegrationTest;
import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.AccountService;
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

        // Call it again directly with the same, already-SETTLED instruction id.
        SettlementInstruction second = settlementService.settleInstruction(first.getId());
        assertThat(second.getStatus()).isEqualTo(InstructionStatus.SETTLED);

        // Balances moved exactly once, not twice.
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
     * Two independent instrument groups netted into the same batch: one buyer (BX) can't cover
     * their net cash obligation, the other (BY) can. Because every settlement instruction is its
     * own transaction, BX's shortfall must not block or roll back BY's settlement.
     *
     * <p>This also surfaces a real limitation of the CCP-intermediary design (see
     * SettlementService's class doc): once a batch is netted, the original trade's two sides are
     * settled as separate instructions against the CCP. If one side settles and the other later
     * fails, the CCP is left holding a permanent residual imbalance — not just the transient,
     * self-correcting one a fully-successful batch always resolves back to zero. A production
     * system would need a guarantee fund (or a reservation/two-phase-commit protocol across a
     * net position's instructions) to absorb exactly this case; this simulator does not attempt
     * that and instead documents it here as a known, deliberately-scoped-out gap.
     */
    @Test
    void partialFailureInABatchSettlesUnaffectedParticipantsButLeavesCcpResidual() {
        Participant bx = participantRepository.save(new Participant("ShortfallBuyer"));
        Participant sx = participantRepository.save(new Participant("ShortfallSeller"));
        Participant by = participantRepository.save(new Participant("HealthyBuyer"));
        Participant sy = participantRepository.save(new Participant("HealthySeller"));
        Instrument instrumentX = instrumentRepository.save(new Instrument("KR0000000006", "Shortfall Instrument"));
        Instrument instrumentY = instrumentRepository.save(new Instrument("KR0000000007", "Healthy Instrument"));
        LocalDate settlementDate = LocalDate.now().plusDays(3);

        accountService.depositCash(bx.getId(), new BigDecimal("100.00"));  // needs 500 — will fail
        accountService.depositSecurities(sx.getId(), instrumentX.getId(), 50);
        accountService.depositCash(by.getId(), new BigDecimal("1000.00")); // needs 300 — fine
        accountService.depositSecurities(sy.getId(), instrumentY.getId(), 100);

        Trade tradeX = tradeService.registerTrade(bx.getId(), sx.getId(), instrumentX.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), settlementDate);
        Trade tradeY = tradeService.registerTrade(by.getId(), sy.getId(), instrumentY.getId(),
                30, new BigDecimal("10.00"), LocalDate.now(), settlementDate);

        orchestrator.runBatch(settlementDate);

        assertThat(tradeRepository.findById(tradeX.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.FAILED);
        assertThat(tradeRepository.findById(tradeY.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.SETTLED);

        // The failing group: SX still delivered (its own leg had no shortfall) and got paid;
        // BX never paid and never received anything.
        assertThat(holdingOf(sx.getId(), instrumentX.getId())).isZero();
        assertThat(balanceOf(sx.getId())).isEqualByComparingTo("500.00");
        assertThat(holdingOf(bx.getId(), instrumentX.getId())).isZero();
        assertThat(balanceOf(bx.getId())).isEqualByComparingTo("100.00");

        // The healthy group settled completely, unaffected by the other group's failure.
        assertThat(holdingOf(sy.getId(), instrumentY.getId())).isEqualTo(70);
        assertThat(balanceOf(sy.getId())).isEqualByComparingTo("300.00");
        assertThat(holdingOf(by.getId(), instrumentY.getId())).isEqualTo(30);
        assertThat(balanceOf(by.getId())).isEqualByComparingTo("700.00");

        // The CCP is left holding SX's 50 units with no one to deliver them to, and owes the
        // 500 it already paid SX but never collected from BX — a permanent residual, unlike the
        // fully-successful batch in NettingBatchTest where the CCP always returns to exactly zero.
        Long ccpId = accountService.getOrCreateCcpParticipantId();
        assertThat(holdingOf(ccpId, instrumentX.getId())).isEqualTo(50);
        assertThat(balanceOf(ccpId)).isEqualByComparingTo("-500.00");
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

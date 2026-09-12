package com.dvp.settlement.concurrency;

import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.AccountService;
import com.dvp.settlement.service.SettlementService;
import com.dvp.settlement.service.TradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires the same settlement instruction id at settleInstruction() from many threads at once.
 * The PENDING-status guard under a pessimistic lock (see SettlementService) should let exactly
 * one caller actually move money; everyone else should see a non-PENDING instruction and no-op.
 * Without the lock, two threads could both read status=PENDING before either writes back and
 * both apply the movement, double-crediting the buyer and double-debiting the seller.
 */
class ConcurrentInstructionExecutionTest extends AbstractIntegrationTest {

    @Autowired
    private ParticipantRepository participantRepository;
    @Autowired
    private InstrumentRepository instrumentRepository;
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

    @Test
    void concurrentCallsToSettleTheSameInstructionSettleItExactlyOnce() throws Exception {
        Participant buyer = participantRepository.save(new Participant("RaceBuyer"));
        Participant seller = participantRepository.save(new Participant("RaceSeller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000008", "Duplicate Execution Corp"));

        accountService.depositCash(buyer.getId(), new BigDecimal("5000.00"));
        accountService.depositSecurities(seller.getId(), instrument.getId(), 100);

        Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                50, new BigDecimal("10.00"), LocalDate.now(), LocalDate.now());
        Long instructionId = tradeService.createInstructionForTrade(trade.getId());

        int concurrentCalls = 10;
        ExecutorService pool = Executors.newFixedThreadPool(concurrentCalls);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SettlementInstruction>> futures = new ArrayList<>();
        for (int i = 0; i < concurrentCalls; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return settlementService.settleInstruction(instructionId);
            }));
        }
        start.countDown();
        for (Future<SettlementInstruction> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(InstructionStatus.SETTLED);
        }
        pool.shutdown();

        BigDecimal buyerCash = cashAccountRepository.findByParticipantIdAndCurrency(buyer.getId(), AccountService.DEFAULT_CURRENCY)
                .orElseThrow().getBalance();
        BigDecimal sellerCash = cashAccountRepository.findByParticipantIdAndCurrency(seller.getId(), AccountService.DEFAULT_CURRENCY)
                .orElseThrow().getBalance();
        long buyerHolding = securityHoldingRepository.findByParticipantIdAndInstrumentId(buyer.getId(), instrument.getId())
                .orElseThrow().getQuantity();
        long sellerHolding = securityHoldingRepository.findByParticipantIdAndInstrumentId(seller.getId(), instrument.getId())
                .orElseThrow().getQuantity();

        // Moved exactly once across 10 concurrent callers, not 10 times.
        assertThat(buyerCash).isEqualByComparingTo("4500.00");
        assertThat(sellerCash).isEqualByComparingTo("500.00");
        assertThat(buyerHolding).isEqualTo(50);
        assertThat(sellerHolding).isEqualTo(50);
        assertThat(ledgerEntryRepository.findAll()).hasSize(4);
    }
}

package com.dvp.settlement.concurrency;

import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.AccountService;
import com.dvp.settlement.service.SettlementOrchestrator;
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
 * Fires many concurrent DVP settlements at a single seller's securities holding to prove the
 * pessimistic-locking strategy in SettlementService prevents both overdrafts and lost updates.
 *
 * <p>Without {@code SELECT ... FOR UPDATE} on the holding row, two threads could both read
 * quantity=100 before either writes back, both pass the "is 60 <= 100" check, and both commit a
 * debit — leaving a negative balance and silently dropping one of the two updates. Locking in a
 * fixed order (see SettlementService) is what rules that out.
 */
class SettlementConcurrencyTest extends AbstractIntegrationTest {

    private static final int BUYER_COUNT = 20;
    private static final long QUANTITY_PER_TRADE = 60;
    private static final long SELLER_OPENING_QUANTITY = 1000;

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
    private AccountService accountService;
    @Autowired
    private TradeService tradeService;
    @Autowired
    private SettlementOrchestrator orchestrator;

    @Test
    void concurrentSettlementsNeverOverdraftAndNeverLoseAnUpdate() throws Exception {
        Participant seller = participantRepository.save(new Participant("Seller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000099", "Concurrency Test Corp"));
        accountService.depositSecurities(seller.getId(), instrument.getId(), SELLER_OPENING_QUANTITY);

        List<Long> buyerIds = new ArrayList<>();
        List<Long> tradeIds = new ArrayList<>();
        for (int i = 0; i < BUYER_COUNT; i++) {
            Participant buyer = participantRepository.save(new Participant("Buyer-" + i));
            accountService.depositCash(buyer.getId(), new BigDecimal("1000000"));
            buyerIds.add(buyer.getId());

            Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                    QUANTITY_PER_TRADE, BigDecimal.TEN, LocalDate.now(), LocalDate.now());
            tradeIds.add(trade.getId());
        }

        BigDecimal totalCashBefore = totalCash(buyerIds, seller.getId());

        ExecutorService pool = Executors.newFixedThreadPool(BUYER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Long tradeId : tradeIds) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    orchestrator.settleTradeNow(tradeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long settledCount = tradeRepository.findAllById(tradeIds).stream()
                .filter(t -> t.getStatus() == TradeStatus.SETTLED).count();
        long failedCount = tradeRepository.findAllById(tradeIds).stream()
                .filter(t -> t.getStatus() == TradeStatus.FAILED).count();
        long expectedSettled = SELLER_OPENING_QUANTITY / QUANTITY_PER_TRADE; // 1000 / 60 = 16

        assertThat(settledCount).isEqualTo(expectedSettled);
        assertThat(failedCount).isEqualTo(BUYER_COUNT - expectedSettled);

        long sellerFinalQuantity = securityHoldingRepository
                .findByParticipantIdAndInstrumentId(seller.getId(), instrument.getId()).orElseThrow().getQuantity();
        assertThat(sellerFinalQuantity).isGreaterThanOrEqualTo(0);
        assertThat(sellerFinalQuantity).isEqualTo(SELLER_OPENING_QUANTITY - expectedSettled * QUANTITY_PER_TRADE);

        long totalBuyerQuantity = buyerIds.stream()
                .mapToLong(id -> securityHoldingRepository.findByParticipantIdAndInstrumentId(id, instrument.getId())
                        .map(SecurityHolding::getQuantity).orElse(0L))
                .sum();
        // Conservation: nothing created or destroyed by concurrent execution.
        assertThat(sellerFinalQuantity + totalBuyerQuantity).isEqualTo(SELLER_OPENING_QUANTITY);

        BigDecimal totalCashAfter = totalCash(buyerIds, seller.getId());
        assertThat(totalCashAfter).isEqualByComparingTo(totalCashBefore);
    }

    private BigDecimal totalCash(List<Long> buyerIds, Long sellerId) {
        BigDecimal total = cashAccountRepository.findByParticipantIdAndCurrency(sellerId, AccountService.DEFAULT_CURRENCY)
                .map(CashAccount::getBalance).orElse(BigDecimal.ZERO);
        for (Long buyerId : buyerIds) {
            total = total.add(cashAccountRepository.findByParticipantIdAndCurrency(buyerId, AccountService.DEFAULT_CURRENCY)
                    .map(CashAccount::getBalance).orElse(BigDecimal.ZERO));
        }
        return total;
    }
}

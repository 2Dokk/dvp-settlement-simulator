package com.dvp.settlement.concurrency;

import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.AccountService;
import com.dvp.settlement.service.BatchSettlementService;
import com.dvp.settlement.service.TradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs two batches for the SAME settlement date concurrently. TradeRepository#claimPendingForBatch
 * is a single conditional UPDATE (status PENDING -> NETTED) that Postgres serializes at the row
 * level, so every trade should end up claimed by exactly one of the two batches — never both,
 * never neither. Without that conditional WHERE clause, both batches could read the same PENDING
 * trades and each build their own net positions from them, double-settling every trade.
 */
class ConcurrentBatchClaimTest extends AbstractIntegrationTest {

    @Autowired
    private ParticipantRepository participantRepository;
    @Autowired
    private InstrumentRepository instrumentRepository;
    @Autowired
    private TradeRepository tradeRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TradeService tradeService;
    @Autowired
    private BatchSettlementService batchSettlementService;

    @Test
    void concurrentBatchRunsForTheSameDateNeverDoubleClaimOrLoseATrade() throws Exception {
        Participant buyer = participantRepository.save(new Participant("ClaimRaceBuyer"));
        Participant seller = participantRepository.save(new Participant("ClaimRaceSeller"));
        Instrument instrument = instrumentRepository.save(new Instrument("KR0000000009", "Claim Race Corp"));
        LocalDate settlementDate = LocalDate.now().plusDays(4);

        accountService.depositCash(buyer.getId(), new BigDecimal("100000.00"));
        accountService.depositSecurities(seller.getId(), instrument.getId(), 1000);

        int tradeCount = 12;
        List<Long> tradeIds = new ArrayList<>();
        for (int i = 0; i < tradeCount; i++) {
            Trade trade = tradeService.registerTrade(buyer.getId(), seller.getId(), instrument.getId(),
                    5, BigDecimal.TEN, LocalDate.now(), settlementDate);
            tradeIds.add(trade.getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SettlementBatch>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return batchSettlementService.runBatch(settlementDate);
            }));
        }
        start.countDown();
        List<SettlementBatch> batches = new ArrayList<>();
        for (Future<SettlementBatch> f : futures) {
            batches.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        Set<Long> batchIds = Set.of(batches.get(0).getId(), batches.get(1).getId());
        List<Trade> reloaded = tradeRepository.findAllById(tradeIds);

        // Every trade landed in exactly one of the two batches, and every one of them settled.
        assertThat(reloaded).hasSize(tradeCount);
        assertThat(reloaded).allSatisfy(t -> {
            assertThat(t.getBatchId()).isIn(batchIds);
            assertThat(t.getStatus()).isEqualTo(TradeStatus.SETTLED);
        });

        long claimedByFirst = reloaded.stream().filter(t -> t.getBatchId().equals(batches.get(0).getId())).count();
        long claimedBySecond = reloaded.stream().filter(t -> t.getBatchId().equals(batches.get(1).getId())).count();
        // No trade lost, none double-claimed: the two disjoint counts add up to the whole set.
        assertThat(claimedByFirst + claimedBySecond).isEqualTo(tradeCount);
    }
}

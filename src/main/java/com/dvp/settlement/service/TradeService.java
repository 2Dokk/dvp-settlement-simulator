package com.dvp.settlement.service;

import com.dvp.settlement.domain.SettlementInstruction;
import com.dvp.settlement.domain.Trade;
import com.dvp.settlement.domain.TradeStatus;
import com.dvp.settlement.repository.InstrumentRepository;
import com.dvp.settlement.repository.ParticipantRepository;
import com.dvp.settlement.repository.SettlementInstructionRepository;
import com.dvp.settlement.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradeRepository tradeRepository;
    private final ParticipantRepository participantRepository;
    private final InstrumentRepository instrumentRepository;
    private final SettlementInstructionRepository instructionRepository;
    private final AccountService accountService;

    @Transactional
    public Trade registerTrade(Long buyerId, Long sellerId, Long instrumentId, long quantity,
                                BigDecimal price, LocalDate tradeDate, LocalDate settlementDate) {
        if (!participantRepository.existsById(buyerId)) {
            throw new NoSuchElementException("unknown buyer participant: " + buyerId);
        }
        if (!participantRepository.existsById(sellerId)) {
            throw new NoSuchElementException("unknown seller participant: " + sellerId);
        }
        if (!instrumentRepository.existsById(instrumentId)) {
            throw new NoSuchElementException("unknown instrument: " + instrumentId);
        }
        Long ccpId = accountService.ccpParticipantId();
        if (ccpId.equals(buyerId) || ccpId.equals(sellerId)) {
            throw new IllegalArgumentException("the CCP cannot be a party to a trade");
        }

        Trade trade = new Trade(buyerId, sellerId, instrumentId, quantity, price, tradeDate, settlementDate);
        trade = tradeRepository.save(trade);

        // Provision accounts up front (not lock-critical) so SettlementService only ever has to
        // lock rows that already exist.
        accountService.getOrCreateCashAccount(buyerId, AccountService.DEFAULT_CURRENCY);
        accountService.getOrCreateCashAccount(sellerId, AccountService.DEFAULT_CURRENCY);
        accountService.getOrCreateSecurityHolding(buyerId, instrumentId);
        accountService.getOrCreateSecurityHolding(sellerId, instrumentId);

        return trade;
    }

    /**
     * Creates the single settlement instruction for an un-netted trade and returns its id. Kept
     * separate (own transaction) from actually executing it — see SettlementOrchestrator — so
     * that instruction creation and DVP execution are independently retryable steps.
     */
    @Transactional
    public Long createInstructionForTrade(Long tradeId) {
        Trade trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new NoSuchElementException("trade not found: " + tradeId));
        if (trade.getStatus() != TradeStatus.PENDING) {
            throw new IllegalStateException("trade %d is not PENDING (status=%s)".formatted(tradeId, trade.getStatus()));
        }
        SettlementInstruction instruction = instructionRepository.save(SettlementInstruction.forTrade(trade));
        return instruction.getId();
    }
}

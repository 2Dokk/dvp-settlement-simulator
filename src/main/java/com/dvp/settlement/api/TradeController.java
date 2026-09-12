package com.dvp.settlement.api;

import com.dvp.settlement.domain.Trade;
import com.dvp.settlement.repository.TradeRepository;
import com.dvp.settlement.service.SettlementOrchestrator;
import com.dvp.settlement.service.TradeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

import static com.dvp.settlement.api.dto.SettlementDtos.*;
import static com.dvp.settlement.api.dto.TradeDtos.*;

@RestController
@RequestMapping("/trades")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;
    private final TradeRepository tradeRepository;
    private final SettlementOrchestrator orchestrator;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse create(@Valid @RequestBody CreateTradeRequest request) {
        Trade trade = tradeService.registerTrade(request.buyerId(), request.sellerId(), request.instrumentId(),
                request.quantity(), request.price(), request.tradeDate(), request.settlementDate());
        return TradeResponse.from(trade);
    }

    @GetMapping
    public List<TradeResponse> list() {
        return tradeRepository.findAll().stream().map(TradeResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TradeResponse get(@PathVariable Long id) {
        return tradeRepository.findById(id).map(TradeResponse::from)
                .orElseThrow(() -> new NoSuchElementException("trade not found: " + id));
    }

    /** Settles this trade immediately as a single DVP instruction, bypassing netting. */
    @PostMapping("/{id}/settle")
    public SettlementInstructionResponse settleNow(@PathVariable Long id) {
        return SettlementInstructionResponse.from(orchestrator.settleTradeNow(id));
    }
}

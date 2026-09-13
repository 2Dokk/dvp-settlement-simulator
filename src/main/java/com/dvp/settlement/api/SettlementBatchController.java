package com.dvp.settlement.api;

import com.dvp.settlement.domain.SettlementBatch;
import com.dvp.settlement.repository.NetPositionRepository;
import com.dvp.settlement.repository.SettlementBatchRepository;
import com.dvp.settlement.repository.TradeRepository;
import com.dvp.settlement.service.BatchSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.NoSuchElementException;

import static com.dvp.settlement.api.dto.SettlementDtos.SettlementBatchResponse;

@RestController
@RequestMapping("/settlement-batches")
@RequiredArgsConstructor
public class SettlementBatchController {

    private final BatchSettlementService batchSettlementService;
    private final SettlementBatchRepository batchRepository;
    private final NetPositionRepository netPositionRepository;
    private final TradeRepository tradeRepository;

    /** Nets every PENDING trade for {@code date} and settles the batch atomically. */
    @PostMapping("/{date}/run")
    public SettlementBatchResponse run(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return toResponse(batchSettlementService.runBatch(date));
    }

    @GetMapping("/{id}")
    public SettlementBatchResponse get(@PathVariable Long id) {
        SettlementBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("settlement batch not found: " + id));
        return toResponse(batch);
    }

    private SettlementBatchResponse toResponse(SettlementBatch batch) {
        return SettlementBatchResponse.from(batch,
                netPositionRepository.findByBatchId(batch.getId()),
                tradeRepository.findByBatchId(batch.getId()));
    }
}

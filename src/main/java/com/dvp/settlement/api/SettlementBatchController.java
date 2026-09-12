package com.dvp.settlement.api;

import com.dvp.settlement.domain.SettlementBatch;
import com.dvp.settlement.repository.NetPositionRepository;
import com.dvp.settlement.repository.SettlementBatchRepository;
import com.dvp.settlement.repository.SettlementInstructionRepository;
import com.dvp.settlement.service.SettlementOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.NoSuchElementException;

import static com.dvp.settlement.api.dto.SettlementDtos.*;

@RestController
@RequestMapping("/settlement-batches")
@RequiredArgsConstructor
public class SettlementBatchController {

    private final SettlementOrchestrator orchestrator;
    private final SettlementBatchRepository batchRepository;
    private final NetPositionRepository netPositionRepository;
    private final SettlementInstructionRepository instructionRepository;

    /** Nets every PENDING trade for {@code date} and executes the resulting instructions. */
    @PostMapping("/{date}/run")
    public SettlementBatchResponse run(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        SettlementBatch batch = orchestrator.runBatch(date);
        return toResponse(batch);
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
                instructionRepository.findByBatchId(batch.getId()));
    }
}

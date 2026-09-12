package com.dvp.settlement.api;

import com.dvp.settlement.domain.Instrument;
import com.dvp.settlement.repository.InstrumentRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.dvp.settlement.api.dto.InstrumentDtos.*;

@RestController
@RequestMapping("/instruments")
@RequiredArgsConstructor
public class InstrumentController {

    private final InstrumentRepository instrumentRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstrumentResponse create(@Valid @RequestBody CreateInstrumentRequest request) {
        Instrument saved = instrumentRepository.save(new Instrument(request.isin(), request.name()));
        return InstrumentResponse.from(saved);
    }

    @GetMapping
    public List<InstrumentResponse> list() {
        return instrumentRepository.findAll().stream().map(InstrumentResponse::from).toList();
    }
}

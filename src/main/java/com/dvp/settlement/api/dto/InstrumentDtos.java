package com.dvp.settlement.api.dto;

import com.dvp.settlement.domain.Instrument;
import jakarta.validation.constraints.NotBlank;

public class InstrumentDtos {

    public record CreateInstrumentRequest(@NotBlank String isin, @NotBlank String name) {
    }

    public record InstrumentResponse(Long id, String isin, String name) {
        public static InstrumentResponse from(Instrument i) {
            return new InstrumentResponse(i.getId(), i.getIsin(), i.getName());
        }
    }
}

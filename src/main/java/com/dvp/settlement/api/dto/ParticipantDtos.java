package com.dvp.settlement.api.dto;

import com.dvp.settlement.domain.Participant;
import jakarta.validation.constraints.NotBlank;

public class ParticipantDtos {

    public record CreateParticipantRequest(@NotBlank String name) {
    }

    public record ParticipantResponse(Long id, String name) {
        public static ParticipantResponse from(Participant p) {
            return new ParticipantResponse(p.getId(), p.getName());
        }
    }
}

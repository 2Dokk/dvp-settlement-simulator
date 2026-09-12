package com.dvp.settlement.api;

import com.dvp.settlement.domain.Participant;
import com.dvp.settlement.repository.ParticipantRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.dvp.settlement.api.dto.ParticipantDtos.*;

@RestController
@RequestMapping("/participants")
@RequiredArgsConstructor
public class ParticipantController {

    private final ParticipantRepository participantRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantResponse create(@Valid @RequestBody CreateParticipantRequest request) {
        Participant saved = participantRepository.save(new Participant(request.name()));
        return ParticipantResponse.from(saved);
    }

    @GetMapping
    public List<ParticipantResponse> list() {
        return participantRepository.findAll().stream().map(ParticipantResponse::from).toList();
    }
}

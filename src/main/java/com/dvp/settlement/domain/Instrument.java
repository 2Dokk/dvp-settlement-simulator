package com.dvp.settlement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "instruments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12, unique = true)
    private String isin;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Instrument(String isin, String name) {
        this.isin = isin;
        this.name = name;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}

package com.medical.careslot.models;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


import java.time.Instant;
import java.util.UUID;

// ==================== Clinic ====================
@Getter
@Setter
//@NoArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor
@Entity
@Table(name = "clinic")
public class Clinic {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 400)
    private String address;

    @Column(nullable = false, length = 50)
    private String timezone = "Australia/Melbourne";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // getters/setters omitted for brevity
}

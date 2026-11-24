package com.college.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "face_embeddings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false, unique = true)
    private Student student;

    @Column(nullable = false, length = 12)
    private byte[] nonce; // 12‑byte GCM nonce

    @Column(nullable = false, columnDefinition = "VARBINARY(4096)")
    private byte[] ciphertext;

    @Column(nullable = false, length = 16)
    private byte[] tag; // 16‑byte GCM auth tag

    @Builder.Default
    @Column(name = "encryption_version", nullable = false, length = 20)
    private String encryptionVersion = "v1";

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (encryptionVersion == null || encryptionVersion.isBlank()) {
            encryptionVersion = "v1";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}

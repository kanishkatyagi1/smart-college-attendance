package com.college.attendance.repo;

import com.college.attendance.entity.FaceEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FaceEmbeddingRepository extends JpaRepository<FaceEmbedding, Long> {
    Optional<FaceEmbedding> findByStudent_Id(Long studentId);
    boolean existsByStudent_Id(Long studentId);
}

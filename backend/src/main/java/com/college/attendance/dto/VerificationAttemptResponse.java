package com.college.attendance.dto;

import com.college.attendance.entity.AttendanceVerificationAttempt;
import com.college.attendance.entity.VerificationAttemptStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter @AllArgsConstructor
public class VerificationAttemptResponse {
    private String attemptId;
    private Long sessionId;
    private LocalDateTime expiresAt;
    private VerificationAttemptStatus status;

    public static VerificationAttemptResponse from(AttendanceVerificationAttempt attempt) {
        return new VerificationAttemptResponse(attempt.getId(), attempt.getSession().getId(), attempt.getExpiresAt(), attempt.getStatus());
    }
}

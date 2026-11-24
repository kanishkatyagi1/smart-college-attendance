
package com.college.attendance.dto;

public record AdminStudentResponse(
        Long userId,
        Long studentId,
        String name,
        String email,
        String phone,
        String rollNumber,
        String course,
        int semester,
        String section,
        String approvalStatus,
        boolean active
) {
}

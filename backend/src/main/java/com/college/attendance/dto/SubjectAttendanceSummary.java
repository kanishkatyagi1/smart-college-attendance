package com.college.attendance.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SubjectAttendanceSummary {
    private Long subjectId;
    private String subjectCode;
    private String subjectName;
    private int totalClasses;
    private int attendedClasses;
    private double attendancePercentage;
}
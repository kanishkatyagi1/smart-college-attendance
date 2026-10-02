package com.college.attendance.dto; import lombok.*; @Getter @Setter public class SessionRequest { private Long subjectId; private String section; private int durationMinutes=15; }

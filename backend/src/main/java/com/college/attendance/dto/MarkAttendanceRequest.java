package com.college.attendance.dto; import lombok.*; @Getter @Setter public class MarkAttendanceRequest { private Long sessionId; private String verificationMethod="FACE"; }

package com.college.attendance.dto;

import com.college.attendance.entity.ApprovalStatus;
import com.college.attendance.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PendingStaffResponse {

    private Long userId;
    private String name;
    private String email;
    private String phone;
    private Role role;
    private String employeeId;
    private String department;
    private boolean emailVerified;
    private ApprovalStatus approvalStatus;
}

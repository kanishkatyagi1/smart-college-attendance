package com.college.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StaffApprovalRequest {

    @NotBlank(message = "Password is required")
    @Size(min = 12, message = "Password must contain at least 12 characters")
    private String password;
}
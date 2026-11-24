package com.college.attendance.mobile.model;

public class AdminStudentResponse {

    private Long userId;
    private Long studentId;
    private String name;
    private String email;
    private String phone;
    private String rollNumber;
    private String course;
    private int semester;
    private String section;
    private String approvalStatus;
    private boolean active;

    public Long getUserId() { return userId; }
    public Long getStudentId() { return studentId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getRollNumber() { return rollNumber; }
    public String getCourse() { return course; }
    public int getSemester() { return semester; }
    public String getSection() { return section; }
    public String getApprovalStatus() { return approvalStatus; }
    public boolean isActive() { return active; }
}

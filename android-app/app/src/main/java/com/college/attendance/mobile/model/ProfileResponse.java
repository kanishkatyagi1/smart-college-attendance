
package com.college.attendance.mobile.model;

public class ProfileResponse {
    public Long id;

    // Student details
    public String rollNumber;
    public String course;
    public String section;
    public int semester;

    // Teacher details
    public String employeeId;
    public String department;

    // Associated user details
    public UserInfo user;

    public static class UserInfo {
        public String name;
        public String email;
        public String role;

        public String phone;
    }
}

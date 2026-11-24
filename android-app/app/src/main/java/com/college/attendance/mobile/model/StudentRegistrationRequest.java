package com.college.attendance.mobile.model;

public class StudentRegistrationRequest {

    public String name;
    public String email;
    public String phone;
    public String rollNumber;
    public String course;
    public int semester;
    public String section;
    public String password;
    public String confirmPassword;

    public StudentRegistrationRequest(
            String name,
            String email,
            String phone,
            String rollNumber,
            String course,
            int semester,
            String section,
            String password,
            String confirmPassword) {

        this.name = name;
        this.email = email;
        this.phone = phone;
        this.rollNumber = rollNumber;
        this.course = course;
        this.semester = semester;
        this.section = section;
        this.password = password;
        this.confirmPassword = confirmPassword;
    }
}

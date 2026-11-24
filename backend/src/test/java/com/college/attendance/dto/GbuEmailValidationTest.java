package com.college.attendance.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GbuEmailValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setup() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private StudentRegistrationRequest build(String email) {
        StudentRegistrationRequest req = new StudentRegistrationRequest();
        req.setName("Test Student");
        req.setEmail(email);
        req.setPhone("9876543210");
        req.setRollNumber("2023CS001");
        req.setCourse("Computer Science");
        req.setSemester(3);
        req.setSection("A");
        req.setPassword("password123");
        req.setConfirmPassword("password123");
        return req;
    }

    // ---------- Valid @gbu.ac.in addresses ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "student@gbu.ac.in",
            "STUDENT@GBU.AC.IN",
            "Student@Gbu.Ac.In",
            "john.doe@gbu.ac.in",
            "a123+tag@gbu.ac.in",
            "test_user@gbu.ac.in"
    })
    void validGbuEmailsPass(String email) {
        Set<ConstraintViolation<StudentRegistrationRequest>> violations =
                validator.validate(build(email));
        assertTrue(violations.isEmpty(),
                "Expected no violations for: " + email + " but got: " + violations);
    }

    // ---------- Personal emails must be rejected ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "user@gmail.com",
            "user@outlook.com",
            "user@yahoo.com",
            "user@hotmail.com",
            "user@protonmail.com"
    })
    void personalEmailsRejected(String email) {
        Set<ConstraintViolation<StudentRegistrationRequest>> violations =
                validator.validate(build(email));
        assertFalse(violations.isEmpty(),
                "Personal email should be rejected: " + email);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("@gbu.ac.in")),
                "Error message should mention @gbu.ac.in");
    }

    // ---------- Lookalike domains must be rejected ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "user@gbu.ac.in.evil.com",
            "user@fakegbu.ac.in",
            "user@gbu.ac.in.example.com",
            "user@not-gbu.ac.in",
            "user@gbu.ac.in2",
            "user@gbu.ac.info"
    })
    void lookalikeDomainsRejected(String email) {
        Set<ConstraintViolation<StudentRegistrationRequest>> violations =
                validator.validate(build(email));
        assertFalse(violations.isEmpty(),
                "Lookalike domain should be rejected: " + email);
    }

    // ---------- Blank / null / empty ----------

    @Test
    void blankEmailRejected() {
        Set<ConstraintViolation<StudentRegistrationRequest>> violations =
                validator.validate(build(""));
        assertFalse(violations.isEmpty());
    }
}

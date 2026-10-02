CREATE DATABASE IF NOT EXISTS smart_attendance;
USE smart_attendance;

-- Spring Boot/JPA creates the tables automatically with ddl-auto=update.
-- This file creates the DB and optional seed/reference data only.

CREATE TABLE IF NOT EXISTS app_info (
  id INT PRIMARY KEY AUTO_INCREMENT,
  project_name VARCHAR(255) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO app_info(project_name) SELECT 'Smart College Attendance & Academic Management System'
WHERE NOT EXISTS (SELECT 1 FROM app_info);

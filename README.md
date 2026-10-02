# Smart College Attendance & Academic Management System

Minor/Major project implementation based on the uploaded prototype.

## Stack
- Android: Java + XML
- Backend: Spring Boot 3 + Java 17 + Spring Security + JWT + JPA
- Database: MySQL 8
- Face service: Python + Flask + OpenCV
- API testing: Postman

## Implemented prototype areas
- Login + role-based access: Student, Teacher, HOD/Dean
- Student: mark attendance, view attendance, assignments, feedback/complaints
- Teacher: create/close attendance session, view attendance, assignments, doubts/complaints
- HOD: search records, monitoring/analytics, complaints, user management
- Attendance flow with active session validation
- Face verification service using OpenCV Haar + ORB (demo-grade)
- Major-feature backend endpoints: assignments/submissions, complaints, feedback, notifications/report placeholders

## Demo credentials
- Student: student@example.com / student123
- Teacher: teacher@example.com / teacher123
- HOD: hod@example.com / hod123

Change these immediately for any real deployment.

## Folder structure
```
smart-college-attendance/
  android-app/
  backend/
  face-service/
  database/
  docs/
```

See `docs/IMPLEMENTATION_GUIDE.md` for exact setup and run steps.

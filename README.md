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

## Local backend environment

Before starting the backend, set `DB_PASSWORD` to the local MySQL password and `JWT_SECRET` to a private, random signing secret of at least 32 bytes. `DB_USERNAME` defaults to `root` and can be overridden. These values are intentionally not stored in `application.properties`.

## Secure attendance verification

Students create a short-lived attendance verification attempt for an active session, then upload a face image using that server-generated attempt ID. The backend binds the attempt to the authenticated student and session, checks expiry, eligibility, geofence, and duplicates, calls the local face service, then records `PRESENT` only after a successful match. Direct calls to `/api/student/attendance/mark` are rejected. Attempts expire after 120 seconds by default; set `ATTENDANCE_VERIFICATION_EXPIRATION_SECONDS` to change that for development.

Hibernate creates `attendance_verification_attempts` when `spring.jpa.hibernate.ddl-auto=update` is used. A deployment that disables automatic schema updates must create that table before enabling this release; existing attendance records and face files are unchanged.

The Flask service stores a demo face crop per student in `face-service/faces/` and uses Haar detection with ORB similarity. It has no liveness detection and is suitable only for a prototype demonstration.

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

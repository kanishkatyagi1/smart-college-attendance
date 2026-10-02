# Exact implementation pathway

The uploaded image is the functional prototype. This codebase maps it to a real implementation. Build it in this order.

## 1. Install software

1. Java JDK 17
2. IntelliJ IDEA or VS Code for Spring Boot
3. Maven 3.9+
4. MySQL 8 + MySQL Workbench
5. Python 3.11 recommended
6. Android Studio
7. Git + GitHub
8. Postman

## 2. Database

Open MySQL Workbench and run `database/schema.sql`.
Then open `backend/src/main/resources/application.properties` and change:

`spring.datasource.username=root`
`spring.datasource.password=YOUR_MYSQL_PASSWORD`

Spring Boot will create/update the project tables automatically.

## 3. Run backend

Open a terminal in `backend`:

```bash
mvn clean spring-boot:run
```

Test:

```text
GET http://localhost:8080/api/health
```

Expected:

```json
{"status":"UP","service":"smart-attendance-backend"}
```

The first run creates demo accounts.

## 4. Run face service

Open another terminal in `face-service`:

Windows:

```powershell
py -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

Test:

```text
GET http://localhost:5001/health
```

The service uses OpenCV Haar face detection + ORB similarity. This is suitable for a college prototype/demo, not production biometric security.

## 5. Android configuration

Open `android-app` in Android Studio.

The default base URL is:

`http://10.0.2.2:8080/`

That is correct when the Android Emulator accesses a Spring Boot server running on the same PC.

For a physical phone, put your PC's LAN IP instead, for example:

`http://192.168.1.10:8080/`

Change it in `ApiClient.java` or make it a settings value.

For a physical phone, also ensure Windows Firewall allows TCP 8080 and both devices are on the same LAN.

## 6. First working test

Use these accounts:

Student:
- email: `student@example.com`
- password: `student123`

Teacher:
- email: `teacher@example.com`
- password: `teacher123`

HOD:
- email: `hod@example.com`
- password: `hod123`

### Test attendance

1. Start backend.
2. Start face service.
3. Open Android app.
4. Login as Teacher.
5. Create Attendance Session.
6. Login as Student on another emulator/device.
7. Register the student's face first.
8. Go back to Student dashboard.
9. Press Mark Attendance (Face).
10. Capture a clear single face.
11. Face service verifies it.
12. Backend records PRESENT.
13. Teacher can view the session records.
14. HOD can view all attendance records and basic analytics.

## 7. How each prototype box maps to code

### Login
- Android: `MainActivity.java`
- Backend: `AuthController.java`, `AuthService.java`
- Security: `JwtService.java`, `JwtFilter.java`, `SecurityConfig.java`

### Student
- `StudentController.java`
- Student dashboard buttons
- Face registration/verification
- Attendance history
- Assignment endpoint
- Complaint endpoint

### Teacher
- `TeacherController.java`
- Create/close session
- Assigned subjects
- Session attendance
- Academic assignment endpoints

### HOD / Dean
- `HodController.java`
- Student/teacher records
- Attendance monitoring
- Analytics endpoint
- Complaint list
- User disable endpoint

### Attendance flow

Teacher creates session -> active session is stored -> student checks active session -> face verification -> mark endpoint checks session, section and duplicate attendance -> record stored in MySQL.

## 8. Important improvement for the final college submission

The starter UI intentionally uses a simple dashboard to keep the code manageable. After the backend is working, make separate XML screens matching your uploaded prototype:

Student:
- `student_dashboard.xml`
- `mark_attendance.xml`
- `view_attendance.xml`
- `assignments.xml`
- `feedback.xml`

Teacher:
- `teacher_dashboard.xml`
- `create_session.xml`
- `student_attendance.xml`
- `teacher_assignments.xml`
- `teacher_complaints.xml`

HOD:
- `hod_dashboard.xml`
- `search_records.xml`
- `analytics.xml`
- `complaints.xml`
- `user_management.xml`

Use cards, icons and the same green/yellow/red/blue section hierarchy from the uploaded prototype.

## 9. Major-project extensions already prepared in backend

The backend includes models/endpoints for:
- assignments
- assignment submissions
- complaints
- complaint resolution
- feedback data
- HOD monitoring

Add later:
- PDF/Excel reports
- notifications
- location/classroom validation
- anti-spoofing/liveness
- stronger face embeddings
- full assignment file upload
- richer analytics
- MDM/EDR/PAM/DLP integrations if required by your final JD/college scope

## 10. Security checklist for viva

Explain that:
- Passwords are BCrypt hashed.
- JWT is used for authenticated API requests.
- Role-based access is enforced by Spring Security.
- Students cannot mark attendance without an active session.
- Duplicate attendance is blocked by both application logic and a DB unique constraint.
- Section eligibility is checked on the backend.
- Face verification is performed by a separate Python/OpenCV service.
- Production would require HTTPS, a stronger JWT secret, encrypted biometric storage, liveness detection, audit logs, rate limiting and proper secrets management.

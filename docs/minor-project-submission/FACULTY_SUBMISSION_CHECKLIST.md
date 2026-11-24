# FACULTY SUBMISSION CHECKLIST
## Smart College Attendance & Academic Management System
### Date: 2026-11-23

This checklist maps every faculty-required item to its location, verification status, and any missing evidence.

---

## DIAGRAMS

| # | Diagram | File | Status | Notes |
|---|---|---|---|---|
| 1 | Complete system flow diagram | `05_System_Flow_Diagrams/` — see `PROJECT_CURRENT_STATE_AUDIT.md` §17 Diagram 4 | ✅ Mermaid source in audit | Render with Mermaid Live or mermaid-js CLI |
| 2 | System architecture diagram | `02_Architecture_Diagrams/` — see audit §17 Diagram 1 | ✅ Mermaid source in audit | |
| 3 | Use-case diagram (Student/Teacher/HOD) | `03_UML_Use_Case/` — see audit §17 Diagram 6 | ✅ Mermaid source in audit | |
| 4 | Database design diagram | `04_Database_Design/` — see audit §10 Entity Inventory | ✅ Mermaid ER in audit §17 Diagram 7 | All 15 tables documented |
| 5 | Entity Relationship Diagram (ERD) | `04_Database_Design/` — see audit §17 Diagram 7 | ✅ Mermaid ER source in audit | |
| 6 | Module design diagram | `06_Module_Design/` — see audit §17 Diagram 8 | ✅ Mermaid source in audit | |
| 7 | Authentication flow diagram | `05_System_Flow_Diagrams/` — see audit §17 Diagram 2 | ✅ Mermaid sequence in audit | |
| 8 | Attendance creation and marking flow | `05_System_Flow_Diagrams/` — see audit §17 Diagram 4 | ✅ Mermaid sequence in audit | |
| 9 | Face registration and verification flow | `05_System_Flow_Diagrams/` — see audit §17 Diagrams 3+4 | ✅ Mermaid sequence in audit | |
| 10 | Geofence validation diagram | `05_System_Flow_Diagrams/` — see audit §17 Diagram 5 | ✅ Mermaid flowchart in audit | |
| 11 | Complete system implementation diagram | `02_Architecture_Diagrams/` — see audit §17 Diagram 1 | ✅ Mermaid source in audit | |

**How to render diagrams:** Paste any Mermaid source from the audit into [mermaid.live](https://mermaid.live) → export as PNG or SVG → save to the matching folder.

---

## SCREENSHOTS

### Authentication

| Required | Status | Notes |
|---|---|---|
| Student login screen | ⬜ MISSING — not captured | Run app on emulator, navigate to login, screenshot |
| Teacher login screen | ⬜ MISSING | Same screen, different credentials |
| HOD login screen | ⬜ MISSING | Same screen, different credentials |
| Role-specific dashboard after login | ⬜ MISSING | Capture all three roles |

### Teacher Module

| Required | Status | Notes |
|---|---|---|
| Teacher dashboard | ⬜ MISSING | Requires Teacher account with subjects seeded |
| Create attendance session | ⬜ MISSING | |
| Active session | ⬜ MISSING | |
| View sessions | ⬜ MISSING | |
| View student attendance | ⬜ MISSING | |
| Other implemented functions (assignments) | ⬜ MISSING | |

### Student Module

| Required | Status | Notes |
|---|---|---|
| Student dashboard | ⬜ MISSING | |
| Active attendance session visible | ⬜ MISSING | Requires active session in DB |
| Face registration screen | ⬜ MISSING | CameraX preview in FaceCaptureActivity |
| Face verification screen | ⬜ MISSING | |
| Successful attendance marking | ⬜ MISSING | Response: `{verified:true, attendanceMarked:true}` |
| Attendance history | ⬜ MISSING | |
| Attendance summary | ⬜ MISSING | |

### HOD Module

| Required | Status | Notes |
|---|---|---|
| HOD dashboard | ⬜ MISSING | |
| Implemented HOD functionality | ⬜ MISSING | Pending teacher approval is fully implemented |
| Incomplete functionality — analytics | ⚠️ Document only | Analytics returns raw counts, not percentages |

### Security and Testing

| Required | Status | Notes |
|---|---|---|
| Invalid login rejection | ⬜ MISSING | Expected: 401 "Invalid credentials" |
| Unauthorized access rejection | ⬜ MISSING | Use Postman: Student JWT → GET /api/teacher/profile → 403 |
| Geofence rejection | ⬜ MISSING | Use mock coordinates far from ICT Building |
| Mock location rejection | ⬜ MISSING | Enable mock location app on Android, attempt verify |
| Printed photo rejection | ⬜ MISSING | Requires physical print + device |
| Screen photo rejection | ⬜ MISSING | Requires second phone showing face photo |
| Prerecorded video rejection | ⬜ MISSING / OPTIONAL | Anti-spoofing covers this but no test fixture available |
| Successful genuine face verification | ⬜ MISSING | Requires registered student + face |
| Successful attendance DB entry | ⬜ MISSING | MySQL screenshot of attendance_records row |

---

## FUNCTIONAL TESTING DOCUMENT

| Item | File | Status |
|---|---|---|
| Flask test results (106 pass) | `08_Functional_Testing/flask_test_results.txt` | ⬜ Run `pytest face-service/tests -v > output.txt` |
| Backend test results | `08_Functional_Testing/backend_test_results.txt` | ⬜ Run `mvn test` in backend/ |
| Android compile result | `08_Functional_Testing/android_build.txt` | ⬜ Run `gradlew :app:compileDebugJavaWithJavac` |
| Test inventory table | Included in `PROJECT_CURRENT_STATE_AUDIT.md` §14 | ✅ Written |

---

## MILESTONE AUDIT

| Item | File | Status |
|---|---|---|
| Milestone Verification Report | `10_Milestone_Audit/MILESTONE_VERIFICATION_REPORT.md` | ✅ Complete |
| All 8 milestones evaluated | Inside report | ✅ |
| COMPLETE / PARTIAL / NOT IMPLEMENTED classification | Inside report | ✅ |

---

## KEY GAPS BEFORE SUBMISSION

1. **All screenshots** — none captured yet. Use Android Emulator (API 33+) or real device.
2. **Test output files** — run the three test commands and save output to `08_Functional_Testing/`.
3. **Rendered diagram PNGs** — paste Mermaid source into mermaid.live, export PNG into relevant folders.
4. **Fingerprint attendance** — clearly state NOT IMPLEMENTED in report. Do not fabricate.
5. **Search milestone** — clearly state NOT IMPLEMENTED. Add a note on how it could be added.

---

## EVIDENCE THAT CANNOT BE FABRICATED

The following must come from actual running software:
- Screenshots of the running Android application
- MySQL table rows after a real attendance marking
- Flask Logcat output showing `p_real=0.7581` for a genuine face
- Test suite output with pass counts

Do not use placeholder images, mock data screenshots, or generated UI mockups as evidence.

---

*End of FACULTY_SUBMISSION_CHECKLIST.md*

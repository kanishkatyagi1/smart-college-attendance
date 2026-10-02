package com.college.attendance.controller;
import com.college.attendance.dto.MarkAttendanceRequest; import com.college.attendance.entity.*; import com.college.attendance.repo.*; import org.springframework.security.core.context.SecurityContextHolder; import org.springframework.web.bind.annotation.*; import java.time.*; import java.util.*;
@RestController @RequestMapping("/api/student") public class StudentController {
 private final UserRepository users; private final StudentRepository students; private final AttendanceSessionRepository sessions; private final AttendanceRecordRepository records; private final AssignmentRepository assignments; private final SubjectRepository subjects;
 public StudentController(UserRepository u,StudentRepository s,AttendanceSessionRepository as,AttendanceRecordRepository ar,AssignmentRepository a,SubjectRepository sub){users=u;students=s;sessions=as;records=ar;assignments=a;subjects=sub;}
 private Student me(){String e=SecurityContextHolder.getContext().getAuthentication().getName(); return students.findByUser(users.findByEmail(e).orElseThrow()).orElseThrow();}
 @GetMapping("/profile") public Student profile(){return me();}
 @GetMapping("/attendance") public List<AttendanceRecord> attendance(){return records.findByStudentOrderByMarkedAtDesc(me());}
 @GetMapping("/active-session") public Object active(){return sessions.findFirstBySectionAndActiveTrue(me().getSection()).orElse(null);}
 @PostMapping("/attendance/mark") public Object mark(@RequestBody MarkAttendanceRequest r){Student st=me(); AttendanceSession s=sessions.findById(r.getSessionId()).orElseThrow(); if(!s.isActive()||s.getEndTime().isBefore(LocalDateTime.now())) throw new RuntimeException("Session closed"); if(!s.getSection().equals(st.getSection())) throw new RuntimeException("Wrong section"); if(records.existsBySessionAndStudent(s,st)) throw new RuntimeException("Already marked"); return records.save(AttendanceRecord.builder().session(s).student(st).markedAt(LocalDateTime.now()).status("PRESENT").verificationMethod(r.getVerificationMethod()).build());}
 @GetMapping("/assignments") public List<Assignment> assignments(){return assignments.findBySubjectIn(subjects.findBySection(me().getSection()));}
}

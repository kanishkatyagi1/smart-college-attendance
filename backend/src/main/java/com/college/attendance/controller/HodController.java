package com.college.attendance.controller;
import com.college.attendance.entity.*; import com.college.attendance.repo.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/hod") public class HodController {
 private final UserRepository users; private final StudentRepository students; private final TeacherRepository teachers; private final AttendanceRecordRepository records; private final FeedbackComplaintRepository complaints;
 public HodController(UserRepository u,StudentRepository s,TeacherRepository t,AttendanceRecordRepository r,FeedbackComplaintRepository c){users=u;students=s;teachers=t;records=r;complaints=c;}
 @GetMapping("/students") public List<Student> students(){return students.findAll();}
 @GetMapping("/teachers") public List<Teacher> teachers(){return teachers.findAll();}
 @GetMapping("/attendance") public List<AttendanceRecord> attendance(){return records.findAll();}
 @GetMapping("/analytics") public Object analytics(){long total=students.count();long present=records.count();return Map.of("students",total,"attendanceRecords",present,"note","For percentage analytics, aggregate PRESENT vs total scheduled sessions.");}
 @GetMapping("/complaints") public List<FeedbackComplaint> complaints(){return complaints.findAll();}
 @PostMapping("/user/{id}/disable") public Object disable(@PathVariable Long id){User u=users.findById(id).orElseThrow();u.setActive(false);return users.save(u);}
}

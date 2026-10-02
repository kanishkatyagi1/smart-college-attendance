package com.college.attendance.repo; import com.college.attendance.entity.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface FeedbackComplaintRepository extends JpaRepository<FeedbackComplaint,Long>{ List<FeedbackComplaint> findByRaisedBy(User u); List<FeedbackComplaint> findByStatus(String s); }

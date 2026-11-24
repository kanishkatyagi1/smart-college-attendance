package com.college.attendance.service;

import com.college.attendance.dto.ActiveLivenessSessionResponse;
import com.college.attendance.entity.Student;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, in-memory phase-1 active-liveness session registry.
 *
 * <p>It is deliberately not distributed: a load-balanced deployment must replace
 * it with a shared, atomic store before active liveness is enabled. Session data
 * contains only protocol metadata; frames are passed to the evaluator and never
 * retained. A monitor per session serialises transitions and frame processing.
 */
@Service
public class ActiveLivenessSessionService {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ActiveLivenessEvaluator evaluator;
    private final Clock clock;
    private final int timeoutSeconds;
    private final int maxSessions;
    private final int maxFrameBytes;
    private final int maxFrames;
    private final int maxRetries;
    private final int terminalRetentionSeconds;
    private final long minFrameIntervalMillis;

    @org.springframework.beans.factory.annotation.Autowired
    public ActiveLivenessSessionService(
            ActiveLivenessEvaluator evaluator,
            @Value("${active.liveness.session-timeout-seconds:6}") int timeoutSeconds,
            @Value("${active.liveness.max-sessions:1000}") int maxSessions,
            @Value("${active.liveness.max-frame-bytes:1048576}") int maxFrameBytes,
            @Value("${active.liveness.max-frames:30}") int maxFrames,
            @Value("${active.liveness.max-retries:1}") int maxRetries,
            @Value("${active.liveness.terminal-retention-seconds:60}") int terminalRetentionSeconds,
            @Value("${active.liveness.min-frame-interval-ms:100}") long minFrameIntervalMillis) {
        this(evaluator, Clock.systemUTC(), timeoutSeconds, maxSessions, maxFrameBytes,
                maxFrames, maxRetries, terminalRetentionSeconds, minFrameIntervalMillis);
    }

    public ActiveLivenessSessionService(ActiveLivenessEvaluator evaluator, Clock clock,
                                  int timeoutSeconds, int maxSessions, int maxFrameBytes,
                                  int maxFrames, int maxRetries, int terminalRetentionSeconds,
                                  long minFrameIntervalMillis) {
        this.evaluator = evaluator;
        this.clock = clock;
        this.timeoutSeconds = positive(timeoutSeconds, "session timeout");
        this.maxSessions = positive(maxSessions, "max sessions");
        this.maxFrameBytes = positive(maxFrameBytes, "max frame bytes");
        this.maxFrames = positive(maxFrames, "max frames");
        this.maxRetries = nonNegative(maxRetries, "max retries");
        this.terminalRetentionSeconds = nonNegative(terminalRetentionSeconds, "terminal retention");
        if (minFrameIntervalMillis < 0) throw new IllegalArgumentException("minimum frame interval must not be negative");
        this.minFrameIntervalMillis = minFrameIntervalMillis;
    }

    public ActiveLivenessSessionResponse start(Student student, ActiveLivenessPurpose purpose,
                                               String attemptId) {
        requireStudent(student);
        if (purpose == null) throw badRequest("purpose is required");
        if (purpose == ActiveLivenessPurpose.VERIFICATION && (attemptId == null || attemptId.isBlank())) {
            throw badRequest("attemptId is required for verification liveness");
        }
        if (purpose == ActiveLivenessPurpose.REGISTRATION && attemptId != null && !attemptId.isBlank()) {
            throw badRequest("attemptId is not allowed for registration liveness");
        }
        cleanupExpiredSessions();
        if (sessions.size() >= maxSessions) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Active liveness capacity is unavailable");
        }
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        Session session = new Session(id, student.getId(), purpose, attemptId, randomChallenge(),
                now, now.plusSeconds(timeoutSeconds));
        if (sessions.putIfAbsent(id, session) != null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to create liveness session");
        }
        return response(session);
    }

    public ActiveLivenessSessionResponse status(Student student, String sessionId) {
        Session session = owned(student, sessionId);
        synchronized (session) {
            expireIfNeeded(session);
            return response(session);
        }
    }

    public ActiveLivenessSessionResponse cancel(Student student, String sessionId) {
        Session session = owned(student, sessionId);
        synchronized (session) {
            expireIfNeeded(session);
            if (session.state == ActiveLivenessState.ACTIVE) terminal(session, ActiveLivenessState.CANCELLED, "CANCELLED");
            else if (session.state == ActiveLivenessState.PROCESSING) throw conflict("Session frame is processing");
            return response(session);
        }
    }

    public ActiveLivenessSessionResponse submitFrame(Student student, String sessionId, long sequence,
                                                      MultipartFile frame) {
        Session session = owned(student, sessionId);
        byte[] bytes = validateAndReadFrame(frame);
        synchronized (session) {
            expireIfNeeded(session);
            if (session.state != ActiveLivenessState.ACTIVE) throw conflict("Liveness session is not active");
            if (sequence != session.nextSequence) throw conflict("Frame sequence is out of order");
            Instant now = clock.instant();
            if (session.lastFrameReceivedAt != null
                    && now.isBefore(session.lastFrameReceivedAt.plusMillis(minFrameIntervalMillis))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Frames are arriving too quickly");
            }
            if (session.framesAccepted >= maxFrames) {
                terminal(session, ActiveLivenessState.FAILED, "FRAME_LIMIT");
                return response(session);
            }
            session.lastFrameReceivedAt = now;
            session.state = ActiveLivenessState.PROCESSING;
        }

        ActiveLivenessEvaluator.Evaluation evaluation;
        try {
            evaluation = evaluator.evaluate(session.id, List.copyOf(session.challenge), sequence, bytes,
                    normalisedContentType(frame.getContentType()));
        } catch (RuntimeException e) {
            synchronized (session) {
                terminal(session, ActiveLivenessState.FAILED, "EVALUATOR_UNAVAILABLE");
                return response(session);
            }
        }

        synchronized (session) {
            expireIfNeeded(session);
            if (session.state == ActiveLivenessState.EXPIRED) return response(session);
            session.framesAccepted++;
            session.nextSequence++;
            switch (evaluation.decision()) {
                case PENDING -> session.state = ActiveLivenessState.ACTIVE;
                case RETRYABLE_FAILURE -> {
                    if (session.retryCount >= maxRetries) terminal(session, ActiveLivenessState.FAILED, safeReason(evaluation.safeReason(), "RETRY_LIMIT"));
                    else { session.retryCount++; session.challenge = randomChallenge(); session.state = ActiveLivenessState.ACTIVE; }
                }
                case FAILED -> terminal(session, ActiveLivenessState.FAILED, safeReason(evaluation.safeReason(), "LIVENESS_FAILED"));
                case PASSED -> terminal(session, ActiveLivenessState.COMPLETED, "ACTIVE_LIVENESS_PASSED");
            }
            return response(session);
        }
    }

    /** Scheduled as well as lazily invoked, so abandoned sessions are bounded. */
    @Scheduled(fixedDelayString = "${active.liveness.cleanup-interval-ms:30000}")
    public void cleanupExpiredSessions() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            synchronized (session) {
                if (session.state == ActiveLivenessState.ACTIVE || session.state == ActiveLivenessState.PROCESSING) expireIfNeeded(session);
                return session.terminalAt != null && !now.isBefore(session.terminalAt.plusSeconds(terminalRetentionSeconds));
            }
        });
    }

    private Session owned(Student student, String id) {
        requireStudent(student);
        if (id == null || id.isBlank()) throw badRequest("sessionId is required");
        Session session = sessions.get(id);
        if (session == null || !student.getId().equals(session.studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active liveness session not found");
        }
        return session;
    }

    private byte[] validateAndReadFrame(MultipartFile frame) {
        if (frame == null || frame.isEmpty()) throw badRequest("frame is required");
        if (!"image/jpeg".equals(normalisedContentType(frame.getContentType()))) throw badRequest("Only image/jpeg frames are supported");
        if (frame.getSize() > maxFrameBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Frame exceeds maximum size");
        try { return frame.getBytes(); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read frame"); }
    }

    private List<ActiveLivenessAction> randomChallenge() {
        List<ActiveLivenessAction> pool = new ArrayList<>(EnumSet.allOf(ActiveLivenessAction.class));
        java.util.Collections.shuffle(pool, secureRandom);
        return List.copyOf(pool.subList(0, secureRandom.nextBoolean() ? 1 : 2));
    }

    private void expireIfNeeded(Session session) {
        if ((session.state == ActiveLivenessState.ACTIVE || session.state == ActiveLivenessState.PROCESSING)
                && !clock.instant().isBefore(session.expiresAt)) terminal(session, ActiveLivenessState.EXPIRED, "EXPIRED");
    }
    private void terminal(Session session, ActiveLivenessState state, String reason) {
        session.state = state; session.reason = reason; session.terminalAt = clock.instant();
    }
    private ActiveLivenessSessionResponse response(Session s) {
        return new ActiveLivenessSessionResponse(s.id, s.purpose, s.state, List.copyOf(s.challenge),
                s.expiresAt, s.retryCount, s.nextSequence, s.reason);
    }
    private static void requireStudent(Student s) { if (s == null || s.getId() == null) throw new IllegalArgumentException("Student is required"); }
    private static String normalisedContentType(String value) { return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT); }
    private static String safeReason(String candidate, String fallback) { return candidate != null && candidate.matches("[A-Z_]{1,64}") ? candidate : fallback; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static int positive(int value, String label) { if (value <= 0) throw new IllegalArgumentException(label + " must be positive"); return value; }
    private static int nonNegative(int value, String label) { if (value < 0) throw new IllegalArgumentException(label + " must not be negative"); return value; }

    private static final class Session {
        final String id; final Long studentId; final ActiveLivenessPurpose purpose; final String attemptId;
        final Instant createdAt; final Instant expiresAt;
        List<ActiveLivenessAction> challenge;
        ActiveLivenessState state = ActiveLivenessState.ACTIVE;
        long nextSequence = 0; int retryCount; int framesAccepted; String reason; Instant terminalAt;
        Instant lastFrameReceivedAt;
        Session(String id, Long studentId, ActiveLivenessPurpose purpose, String attemptId,
                List<ActiveLivenessAction> challenge, Instant createdAt, Instant expiresAt) {
            this.id=id; this.studentId=studentId; this.purpose=purpose; this.attemptId=attemptId;
            this.challenge=challenge; this.createdAt=createdAt; this.expiresAt=expiresAt;
        }
    }
}

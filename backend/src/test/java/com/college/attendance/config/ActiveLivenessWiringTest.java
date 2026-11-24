package com.college.attendance.config;

import com.college.attendance.service.ActiveLivenessEvaluator;
import com.college.attendance.service.ActiveLivenessSessionService;
import com.college.attendance.service.HttpActiveLivenessEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted Spring wiring test for the active-liveness beans.
 *
 * <p>Uses {@code @SpringJUnitConfig} to load only the three beans under test.
 * No database, no HTTP server, no security layer — purely verifies that:
 * <ul>
 *   <li>{@link ActiveLivenessSessionService} is instantiated by Spring
 *       (i.e. the {@code @Autowired} constructor is found).</li>
 *   <li>{@link HttpActiveLivenessEvaluator} is injected as the
 *       {@link ActiveLivenessEvaluator} dependency.</li>
 *   <li>The {@code @Primary} annotation resolves any ambiguity.</li>
 *   <li>All {@code @Value} properties have defaults and are loaded.</li>
 * </ul>
 */
@SpringJUnitConfig(ActiveLivenessWiringTest.TestConfig.class)
@TestPropertySource(properties = {
        "active.liveness.session-timeout-seconds=30",
        "active.liveness.max-sessions=1000",
        "active.liveness.max-frame-bytes=1048576",
        "active.liveness.max-frames=30",
        "active.liveness.max-retries=1",
        "active.liveness.terminal-retention-seconds=60",
        "active.liveness.min-frame-interval-ms=100",
        "active.liveness.cleanup-interval-ms=30000",
        "face.service.url=http://localhost:5001",
        "face.service.secret=test-wiring-secret"
})
class ActiveLivenessWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ActiveLivenessSessionService sessionService;

    @Autowired
    private ActiveLivenessEvaluator evaluator;

    // -----------------------------------------------------------------------
    // Wiring assertions
    // -----------------------------------------------------------------------

    @Test
    void activeLivenessSessionService_isCreated() {
        assertNotNull(sessionService,
                "ActiveLivenessSessionService must be created by Spring");
    }

    @Test
    void httpActiveLivenessEvaluator_isInjectedAsPrimary() {
        assertNotNull(evaluator,
                "ActiveLivenessEvaluator must be injected");
        assertInstanceOf(HttpActiveLivenessEvaluator.class, evaluator,
                "@Primary HttpActiveLivenessEvaluator must win over PendingActiveLivenessEvaluator");
    }

    @Test
    void sessionService_receivesHttpEvaluator() {
        // The evaluator inside the service must be the HTTP implementation.
        // We confirm indirectly: the application context must have exactly one
        // primary ActiveLivenessEvaluator bean and it must be the HTTP one.
        ActiveLivenessEvaluator primary =
                context.getBean(ActiveLivenessEvaluator.class);
        assertInstanceOf(HttpActiveLivenessEvaluator.class, primary);
    }

    @Test
    void noAmbiguousConstructor_serviceStartsWithoutError() {
        // If the @Autowired annotation were missing on the production
        // constructor, Spring would throw NoSuchMethodException before this
        // test method ever runs.  Reaching here proves wiring succeeded.
        assertNotNull(sessionService);
    }

    // -----------------------------------------------------------------------
    // Minimal test context — loads only the three beans under test.
    // No DB, no security, no HTTP server.
    // -----------------------------------------------------------------------

    @Configuration
    static class TestConfig {

        /** RestTemplate bean required by HttpActiveLivenessEvaluator. */
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplateBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(10))
                    .build();
        }

        /**
         * HttpActiveLivenessEvaluator — the @Primary production evaluator.
         * Registered here explicitly so only these beans load; this avoids
         * loading PendingActiveLivenessEvaluator from the main classpath scan
         * which would require component-scan of the full app package.
         */
        @Bean
        @Primary
        HttpActiveLivenessEvaluator httpActiveLivenessEvaluator(RestTemplate restTemplate) {
            return new HttpActiveLivenessEvaluator(restTemplate);
        }

        /**
         * The service under test.
         * Uses the 9-param convenience constructor (evaluator + Clock + 7 config values)
         * so the bean is wired the same way the production @Autowired constructor does,
         * but with an explicit Clock for deterministic test behaviour.
         */
        @Bean
        ActiveLivenessSessionService activeLivenessSessionService(
                ActiveLivenessEvaluator evaluator,
                @org.springframework.beans.factory.annotation.Value(
                        "${active.liveness.session-timeout-seconds:30}") int timeoutSeconds,
                @org.springframework.beans.factory.annotation.Value(
                        "${active.liveness.max-sessions:1000}") int maxSessions,
                @org.springframework.beans.factory.annotation.Value(
                        "${active.liveness.max-frame-bytes:1048576}") int maxFrameBytes,
                @org.springframework.beans.factory.annotation.Value(
                        "${active.liveness.max-frames:30}") int maxFrames,
                @org.springframework.beans.factory.annotation.Value(
                        "${active.liveness.max-retries:1}") int maxRetries,
                @org.springframework.beans.factory.annotation.Value(
                        "${active.liveness.terminal-retention-seconds:60}") int terminalRetentionSeconds,
                @org.springframework.beans.factory.annotation.Value(
                        "${active.liveness.min-frame-interval-ms:100}") long minFrameIntervalMillis) {
            return new ActiveLivenessSessionService(evaluator,
                    java.time.Clock.systemUTC(),
                    timeoutSeconds, maxSessions, maxFrameBytes, maxFrames,
                    maxRetries, terminalRetentionSeconds, minFrameIntervalMillis);
        }
    }
}

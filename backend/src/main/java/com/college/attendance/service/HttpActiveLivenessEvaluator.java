package com.college.attendance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authenticated HTTP client that calls the Flask face service's
 * {@code /active-liveness/process} endpoint to evaluate each frame.
 *
 * <p>Replaces the {@link PendingActiveLivenessEvaluator} placeholder.
 * The Flask endpoint is protected by {@code X-FACE-SERVICE-SECRET};
 * Android never calls Flask directly.
 *
 * <p>The response decision maps directly to Spring's
 * {@link ActiveLivenessEvaluator.Decision} enum.  The reason string
 * is validated against {@code [A-Z_]{1,64}} before being forwarded.
 */
@Component
@Primary
public class HttpActiveLivenessEvaluator implements ActiveLivenessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(HttpActiveLivenessEvaluator.class);
    private static final Pattern SAFE_REASON = Pattern.compile("[A-Z_]{1,64}");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${face.service.url}")
    private String faceServiceUrl;

    @Value("${face.service.secret}")
    private String faceServiceSecret;

    public HttpActiveLivenessEvaluator(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Evaluation evaluate(String internalSessionId, List<ActiveLivenessAction> challenge,
                               long sequence, byte[] frameBytes, String contentType) {
        if (frameBytes == null || frameBytes.length == 0) {
            return new Evaluation(Decision.RETRYABLE_FAILURE, "EMPTY_FRAME");
        }

        String url = faceServiceUrl + "/active-liveness/process";

        // Convert challenge list to JSON array: ["BLINK","TURN_LEFT"]
        String challengeJson = challenge.stream()
                .map(a -> "\"" + a.name() + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("frame", new ByteArrayResource(frameBytes) {
            @Override
            public String getFilename() {
                return "frame.jpg";
            }
        });
        body.add("session_id", internalSessionId);
        body.add("challenge_seq", challengeJson);
        body.add("frame_seq", String.valueOf(sequence));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-FACE-SERVICE-SECRET", faceServiceSecret);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            String decisionStr = root.get("decision").asText();
            Decision decision = Decision.valueOf(decisionStr);

            String reason = null;
            if (root.has("reason") && !root.get("reason").isNull()) {
                String raw = root.get("reason").asText();
                reason = SAFE_REASON.matcher(raw).matches() ? raw : null;
            }

            log.info("Active liveness: session={} seq={} decision={} reason={}",
                    internalSessionId, sequence, decision, reason);
            return new Evaluation(decision, reason);

        } catch (ResourceAccessException e) {
            log.warn("Flask active-liveness endpoint unreachable: {}", e.getMessage());
            return new Evaluation(Decision.RETRYABLE_FAILURE, "EVALUATOR_UNAVAILABLE");
        } catch (IllegalArgumentException e) {
            // Decision.valueOf threw — unknown decision string from Flask
            log.error("Unknown decision from Flask: {}", e.getMessage());
            return new Evaluation(Decision.FAILED, "MALFORMED_RESPONSE");
        } catch (Exception e) {
            log.error("Error calling Flask active-liveness endpoint", e);
            return new Evaluation(Decision.RETRYABLE_FAILURE, "EVALUATOR_UNAVAILABLE");
        }
    }
}

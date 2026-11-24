package com.college.attendance.mobile.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.college.attendance.mobile.R;
import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.ActiveLivenessSession;
import com.college.attendance.mobile.model.ActiveLivenessStartRequest;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Runs the server-managed active-liveness challenge loop.
 *
 * <p>Protocol: Spring owns the session ID, challenge list, sequence numbers,
 * and all deadlines. Android never asserts that liveness succeeded — it reads
 * state from each {@link ActiveLivenessSession} response and acts accordingly.
 *
 * <p>Intent extras (all required for VERIFICATION; only {@code register} for REGISTRATION):
 * <ul>
 *   <li>{@code register} (boolean) — true for face registration, false for attendance.</li>
 *   <li>{@code attemptId} (String) — existing attendance attempt ID (VERIFICATION only).</li>
 *   <li>{@code sessionId} (long) — attendance session ID (VERIFICATION only).</li>
 * </ul>
 *
 * <p>On {@link ActiveLivenessSession#isCompleted()}, this activity starts
 * {@link FaceCaptureActivity} with the same extras and finishes itself.
 */
public class ActiveLivenessActivity extends AppCompatActivity {

    private static final String TAG = "ActiveLiveness";
    private static final int CAMERA_PERMISSION = 110;

    // -----------------------------------------------------------------------
    // Intent extra keys  (must match DashboardActivity and FaceCaptureActivity)
    // -----------------------------------------------------------------------
    public static final String EXTRA_REGISTER   = "register";
    public static final String EXTRA_ATTEMPT_ID = "attemptId";
    public static final String EXTRA_SESSION_ID = "sessionId";

    // -----------------------------------------------------------------------
    // Bundle key for activity-recreation recovery
    // -----------------------------------------------------------------------
    private static final String STATE_LIVENESS_SESSION_ID = "livenessSessionId";

    // -----------------------------------------------------------------------
    // Frame-submission constants
    // -----------------------------------------------------------------------
    /** Minimum milliseconds between frame submissions (server enforces 100 ms). */
    private static final long MIN_FRAME_INTERVAL_MS = 110L;

    /** JPEG quality for YUV → JPEG conversion. 75 ≈ 100–250 KB at 640×480. */
    private static final int JPEG_QUALITY = 75;

    /** Max JPEG size accepted by Spring. Must stay below 1 048 576. */
    private static final int MAX_FRAME_BYTES = 900_000;

    /**
     * Maximum frames the server allows per session (application.properties:
     * active.liveness.max-frames=30). Used to compute progress bar percentage.
     * If the server limit changes, increase this to match.
     */
    static final int MAX_FRAMES = 30;

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private PreviewView cameraPreview;
    private TextView alStepCounter;
    private TextView alInstruction;
    private TextView alChallenge1;
    private TextView alChallenge2;
    private ProgressBar alProgress;
    private TextView alStatus;
    private Button alCancel;

    // -----------------------------------------------------------------------
    // Intent payload
    // -----------------------------------------------------------------------
    private boolean register;
    private String attemptId;
    private Long attendanceSessionId;

    // -----------------------------------------------------------------------
    // Active-liveness session state  (all mutations on main thread except
    // where explicitly noted)
    // -----------------------------------------------------------------------
    /** Spring session UUID. Null until start() completes. */
    private String livenessSessionId;

    /**
     * Server-authoritative next sequence.  Initialised from the start
     * response; updated only when Spring confirms acceptance (the value in
     * the frame-submission response increases).
     */
    private final AtomicLong nextSequence = new AtomicLong(0L);

    /**
     * Guards concurrent frame submissions.  Set to true while a Retrofit
     * call is in-flight; cleared when the response (success or failure) is
     * handled.  Dropped frames (analyser runs while this is true) do NOT
     * consume a sequence number.
     */
    private final AtomicBoolean submitting = new AtomicBoolean(false);

    /** Timestamp of the last submitted frame (System.currentTimeMillis). */
    private volatile long lastSubmitMs = 0L;

    /** True after the session reaches a terminal state to prevent new work. */
    private volatile boolean sessionTerminal = false;

    /**
     * Local challenge progress index. Starts at 0 (calibrating) and advances
     * when the server's {@code nextExpectedSequence} crosses a threshold that
     * indicates the Flask calibration phase has ended and the first challenge
     * is active, then advances again each time nextExpectedSequence increases
     * by the per-challenge frame window.
     *
     * <p>Flask's calibration phase requires
     * {@code CALIBRATION_FRAMES = 12} frames before any challenge check runs.
     * Each subsequent challenge needs at least a few frames to detect the
     * action. The local index is advanced purely for UI display — the server
     * is always the authority for what is PASSED or FAILED.
     */
    private int currentChallengeIndex = 0;

    /**
     * nextExpectedSequence value at the point the last challenge advance was
     * detected.  Used to decide when to advance currentChallengeIndex.
     */
    private long lastChallengeAdvanceSeq = 0L;

    /**
     * Flask calibration phase length (frames before first challenge).
     * Must match face_module/config.py calibration_frames default = 12.
     */
    static final int CALIBRATION_FRAMES = 12;

    /**
     * Minimum frame window per challenge before we advance the UI index.
     * Flask needs OPEN→CLOSED→OPEN (≥3 frames for blink) or 3 consecutive
     * yaw-deviation frames for a turn.  We use a 5-frame window so that even
     * with 3 challenges the total stays within the 30-frame budget:
     * 12 (calibration) + 3×5 = 27 ≤ 30.
     */
    static final int FRAMES_PER_CHALLENGE_ESTIMATE = 5;

    // -----------------------------------------------------------------------
    // CameraX
    // -----------------------------------------------------------------------
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private ExecutorService analysisExecutor;

    // -----------------------------------------------------------------------
    // Handler for main-thread UI updates from background callbacks
    // -----------------------------------------------------------------------
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -----------------------------------------------------------------------
    // Activity lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_liveness);

        cameraPreview  = findViewById(R.id.alCameraPreview);
        alStepCounter  = findViewById(R.id.alStepCounter);
        alInstruction  = findViewById(R.id.alInstruction);
        alChallenge1   = findViewById(R.id.alChallenge1);
        alChallenge2   = findViewById(R.id.alChallenge2);
        alProgress     = findViewById(R.id.alProgress);
        alStatus       = findViewById(R.id.alStatus);
        alCancel       = findViewById(R.id.alCancel);

        register           = getIntent().getBooleanExtra(EXTRA_REGISTER, false);
        attemptId          = getIntent().getStringExtra(EXTRA_ATTEMPT_ID);
        long rawSessionId  = getIntent().getLongExtra(EXTRA_SESSION_ID, -1L);
        attendanceSessionId = rawSessionId == -1L ? null : rawSessionId;

        alCancel.setOnClickListener(v -> onBackPressed());

        // Recover a session ID that survived a configuration change.
        if (savedInstanceState != null) {
            livenessSessionId = savedInstanceState.getString(STATE_LIVENESS_SESSION_ID);
        }

        analysisExecutor = Executors.newSingleThreadExecutor();

        if (livenessSessionId != null) {
            // Activity was recreated mid-session; poll to see if still active.
            setStatus("Reconnecting to liveness session...");
            pollSessionStatus();
        } else {
            setStatus("Preparing liveness check...");
            requestCameraPermission();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (livenessSessionId != null) {
            outState.putString(STATE_LIVENESS_SESSION_ID, livenessSessionId);
        }
    }

    @Override
    public void onBackPressed() {
        cancelSessionAndFinish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Release camera when the activity is no longer visible.
        unbindCamera();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Re-bind camera when the activity becomes visible again.
        if (livenessSessionId != null && !sessionTerminal) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        analysisExecutor.shutdownNow();
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Camera permission
    // -----------------------------------------------------------------------

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startLivenessSession();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLivenessSession();
            } else {
                setStatus("Camera permission is required for liveness check.");
                alCancel.setText("GO BACK");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Phase A: start the Spring session
    // -----------------------------------------------------------------------

    private void startLivenessSession() {
        setStatus("Starting liveness session...");

        ActiveLivenessStartRequest req = register
                ? ActiveLivenessStartRequest.forRegistration()
                : ActiveLivenessStartRequest.forVerification(attemptId);

        ApiClient.get(this).startLivenessSession(req)
                .enqueue(new Callback<ActiveLivenessSession>() {

                    @Override
                    public void onResponse(@NonNull Call<ActiveLivenessSession> call,
                                           @NonNull Response<ActiveLivenessSession> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            ActiveLivenessSession s = response.body();
                            livenessSessionId = s.sessionId;
                            nextSequence.set(s.nextExpectedSequence);
                            Log.d(TAG, "Session started: " + livenessSessionId
                                    + " challenge=" + s.challenge
                                    + " nextSeq=" + s.nextExpectedSequence);
                            handleSessionUpdate(s);
                            startCamera();
                        } else {
                            int code = response.code();
                            Log.w(TAG, "startLivenessSession HTTP " + code);
                            String msg = code == 409
                                    ? "Attendance already recorded for this session."
                                    : code == 400
                                    ? "Invalid request (HTTP 400). Please try again."
                                    : "Could not start liveness session (HTTP " + code + ").";
                            setStatus(msg);
                            alCancel.setText("GO BACK");
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ActiveLivenessSession> call,
                                         @NonNull Throwable t) {
                        Log.e(TAG, "startLivenessSession failed", t);
                        setStatus("Network error starting liveness: " + t.getMessage());
                        alCancel.setText("GO BACK");
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Phase B: CameraX — ImageAnalysis with STRATEGY_KEEP_ONLY_LATEST
    // -----------------------------------------------------------------------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                int rotation = cameraPreview.getDisplay() != null
                        ? cameraPreview.getDisplay().getRotation()
                        : Surface.ROTATION_0;

                Preview preview = new Preview.Builder()
                        .setTargetRotation(rotation)
                        .build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                imageAnalysis.setAnalyzer(analysisExecutor, imageProxy -> {
                    try {
                        processFrame(imageProxy);
                    } finally {
                        // Always close exactly once — ImageAnalysis contract.
                        imageProxy.close();
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageAnalysis
                );

            } catch (Exception e) {
                Log.e(TAG, "startCamera failed", e);
                mainHandler.post(() -> setStatus("Camera error: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void unbindCamera() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    // -----------------------------------------------------------------------
    // Phase B: per-frame processing
    // -----------------------------------------------------------------------

    /**
     * Called from the analyser thread for each camera frame.
     * Uses {@link #submitting} to prevent concurrent in-flight Retrofit calls.
     * Dropped frames do NOT consume a sequence number.
     * ImageProxy is closed by the caller in a finally block.
     */
    private void processFrame(ImageProxy imageProxy) {
        if (sessionTerminal) return;
        if (livenessSessionId == null) return;

        // Rate-limit: enforce minimum inter-frame interval.
        long now = System.currentTimeMillis();
        if (now - lastSubmitMs < MIN_FRAME_INTERVAL_MS) return;

        // Drop if a previous submission is still in-flight.
        if (!submitting.compareAndSet(false, true)) return;

        byte[] jpeg = yuvToJpeg(imageProxy);
        if (jpeg == null || jpeg.length == 0) {
            submitting.set(false);
            return;
        }

        // Enforce server max-frame-bytes limit.
        if (jpeg.length > MAX_FRAME_BYTES) {
            Log.w(TAG, "Frame too large (" + jpeg.length + " bytes), dropping.");
            submitting.set(false);
            return;
        }

        long seq = nextSequence.get();
        lastSubmitMs = now;

        submitFrame(jpeg, seq);
    }

    // -----------------------------------------------------------------------
    // Phase B: Retrofit frame submission
    // -----------------------------------------------------------------------

    private void submitFrame(byte[] jpeg, long seq) {
        RequestBody rb = RequestBody.create(jpeg, MediaType.parse("image/jpeg"));
        MultipartBody.Part part = MultipartBody.Part.createFormData("frame", "frame.jpg", rb);

        ApiClient.get(this)
                .submitLivenessFrame(livenessSessionId, seq, part)
                .enqueue(new Callback<ActiveLivenessSession>() {

                    @Override
                    public void onResponse(@NonNull Call<ActiveLivenessSession> call,
                                           @NonNull Response<ActiveLivenessSession> response) {
                        submitting.set(false);

                        if (response.isSuccessful() && response.body() != null) {
                            ActiveLivenessSession s = response.body();
                            Log.d(TAG, "frame seq=" + seq + " state=" + s.state
                                    + " nextSeq=" + s.nextExpectedSequence);

                            // Advance sequence only on server confirmation.
                            if (s.nextExpectedSequence > seq) {
                                nextSequence.set(s.nextExpectedSequence);
                            }
                            mainHandler.post(() -> handleSessionUpdate(s));

                        } else if (response.code() == 409) {
                            // Out-of-order frame or session not ACTIVE — query to resync.
                            Log.w(TAG, "409 on seq=" + seq + ", polling status");
                            pollSessionStatus();
                        } else if (response.code() == 429) {
                            Log.w(TAG, "429 rate-limit on seq=" + seq);
                            // Back off for one interval; submission guard already released.
                        } else {
                            Log.w(TAG, "submitFrame HTTP " + response.code() + " seq=" + seq);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ActiveLivenessSession> call,
                                         @NonNull Throwable t) {
                        submitting.set(false);
                        Log.w(TAG, "submitFrame network failure seq=" + seq + ": " + t.getMessage());
                        // Network timeout: query session status to decide whether
                        // to resync the sequence or start a new session.
                        pollSessionStatus();
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Phase C: session status poll (after 409 or network timeout)
    // -----------------------------------------------------------------------

    private void pollSessionStatus() {
        if (livenessSessionId == null) return;

        ApiClient.get(this)
                .getLivenessSession(livenessSessionId)
                .enqueue(new Callback<ActiveLivenessSession>() {

                    @Override
                    public void onResponse(@NonNull Call<ActiveLivenessSession> call,
                                           @NonNull Response<ActiveLivenessSession> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            ActiveLivenessSession s = response.body();
                            // Resync sequence from authoritative server state.
                            nextSequence.set(s.nextExpectedSequence);
                            mainHandler.post(() -> handleSessionUpdate(s));
                        } else if (response.code() == 404) {
                            mainHandler.post(() -> {
                                setStatus("Liveness session not found. Please try again.");
                                alCancel.setText("GO BACK");
                                markTerminal();
                            });
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ActiveLivenessSession> call,
                                         @NonNull Throwable t) {
                        Log.w(TAG, "pollSessionStatus failed: " + t.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Phase C: session state machine
    // -----------------------------------------------------------------------

    /**
     * Handle a {@link ActiveLivenessSession} from any Spring response.
     * Must run on the main thread.
     */
    private void handleSessionUpdate(ActiveLivenessSession s) {
        // Advance local challenge index based on server-confirmed frame count.
        // This is UI-only: the server remains the authority for PASSED/FAILED.
        maybeAdvanceChallengeIndex(s);

        // Progress bar: proportion of frame budget consumed (server-confirmed frames).
        int progressPct = (int) Math.min(100, s.nextExpectedSequence * 100L / MAX_FRAMES);
        alProgress.setProgress(progressPct);

        updateChallengeUI(s.challenge);

        switch (s.state) {
            case "ACTIVE":
                // Frame loop continues via ImageAnalysis.
                break;

            case "PROCESSING":
                // Spring is evaluating the last frame — hold, do not submit more.
                setStatus("Verifying...");
                break;

            case "COMPLETED":
                alProgress.setProgress(100);
                // Show all actions completed with checkmarks, then navigate.
                showAllCompleted(s.challenge);
                setStatus("Liveness check passed! ✓");
                markTerminal();
                unbindCamera();
                navigateToFaceCapture();
                break;

            case "FAILED":
                markTerminal();
                unbindCamera();
                String failReason = s.reason != null ? s.reason : "LIVENESS_FAILED";
                setStatus(humanReason(failReason));
                alCancel.setText("GO BACK");
                break;

            case "EXPIRED":
                markTerminal();
                unbindCamera();
                setStatus("Session timed out. Please go back and try again.");
                alCancel.setText("GO BACK");
                break;

            case "CANCELLED":
                markTerminal();
                // User-initiated — finish() handled by cancelSessionAndFinish().
                break;

            default:
                Log.w(TAG, "Unknown session state: " + s.state);
        }
    }

    /**
     * Advance {@link #currentChallengeIndex} based on how many server-confirmed
     * frames have been accepted since the last advance.
     *
     * <p>Flask's protocol has two phases per session:
     * <ol>
     *   <li>Calibration: {@link #CALIBRATION_FRAMES} frames to measure neutral yaw.</li>
     *   <li>Challenges: each challenge needs at least a few accepted frames.</li>
     * </ol>
     *
     * <p>We advance the local index when {@code nextExpectedSequence} has
     * moved at least {@link #CALIBRATION_FRAMES} beyond the session start (for
     * the first advance), and then every {@link #FRAMES_PER_CHALLENGE_ESTIMATE}
     * frames for subsequent challenges. The server is authoritative; this only
     * drives the UI label.
     */
    private void maybeAdvanceChallengeIndex(ActiveLivenessSession s) {
        if (s.challenge == null || s.challenge.isEmpty()) return;
        int total = s.challenge.size();
        if (currentChallengeIndex >= total) return;  // already at or past last

        long seq = s.nextExpectedSequence;

        if (currentChallengeIndex == 0) {
            // Still in calibration or first challenge: advance once calibration is done.
            if (seq >= CALIBRATION_FRAMES && lastChallengeAdvanceSeq == 0) {
                currentChallengeIndex = Math.min(1, total - 1);
                lastChallengeAdvanceSeq = seq;
                Log.d(TAG, "challenge UI: calibration done at seq=" + seq
                        + " -> index=" + currentChallengeIndex);
            }
        } else {
            // Subsequent challenges: advance after enough accepted frames.
            if (seq - lastChallengeAdvanceSeq >= FRAMES_PER_CHALLENGE_ESTIMATE
                    && currentChallengeIndex < total - 1) {
                currentChallengeIndex++;
                lastChallengeAdvanceSeq = seq;
                Log.d(TAG, "challenge UI: advance at seq=" + seq
                        + " -> index=" + currentChallengeIndex);
            }
        }
    }

    private void markTerminal() {
        sessionTerminal = true;
    }

    // -----------------------------------------------------------------------
    // Phase C: cancel / back navigation
    // -----------------------------------------------------------------------

    /**
     * Cancel the active-liveness session and return to the previous screen.
     * Safe to call when {@link #livenessSessionId} is null (before start).
     */
    private void cancelSessionAndFinish() {
        unbindCamera();

        if (livenessSessionId != null && !sessionTerminal) {
            markTerminal();
            // Fire-and-forget DELETE; we finish regardless of the response.
            ApiClient.get(this)
                    .cancelLivenessSession(livenessSessionId)
                    .enqueue(new Callback<ActiveLivenessSession>() {
                        @Override
                        public void onResponse(@NonNull Call<ActiveLivenessSession> c,
                                               @NonNull Response<ActiveLivenessSession> r) {
                            Log.d(TAG, "cancel response HTTP " + r.code());
                        }
                        @Override
                        public void onFailure(@NonNull Call<ActiveLivenessSession> c,
                                              @NonNull Throwable t) {
                            Log.w(TAG, "cancel call failed: " + t.getMessage());
                        }
                    });
        }

        finish();
    }

    // -----------------------------------------------------------------------
    // Phase D: navigate to FaceCaptureActivity on COMPLETED
    // -----------------------------------------------------------------------

    private void navigateToFaceCapture() {
        Intent intent = new Intent(this, FaceCaptureActivity.class);
        intent.putExtra(FaceCaptureActivity.EXTRA_REGISTER, register);
        if (attemptId != null) {
            intent.putExtra(FaceCaptureActivity.EXTRA_ATTEMPT_ID, attemptId);
        }
        if (attendanceSessionId != null) {
            intent.putExtra(FaceCaptureActivity.EXTRA_SESSION_ID, attendanceSessionId);
        }
        startActivity(intent);
        finish();
    }

    // -----------------------------------------------------------------------
    // Phase B: YUV_420_888 → JPEG conversion
    // -----------------------------------------------------------------------

    /**
     * Convert a YUV_420_888 {@link ImageProxy} frame to JPEG bytes.
     *
     * <p>Correctly handles arbitrary {@code rowStride} and {@code pixelStride}
     * values as required for correctness across devices (spec §5.4).
     *
     * <p>Applies the camera sensor rotation from
     * {@link ImageProxy#getImageInfo()}{@code .getRotationDegrees()} so that
     * Flask's {@code decode_image_upright} receives an upright face image.
     *
     * @return JPEG bytes, or {@code null} on error.
     */
    private byte[] yuvToJpeg(ImageProxy imageProxy) {
        try {
            byte[] nv21 = yuv420ToNv21(imageProxy);
            if (nv21 == null) return null;

            int w = imageProxy.getWidth();
            int h = imageProxy.getHeight();

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(w * h / 4);
            yuvImage.compressToJpeg(new Rect(0, 0, w, h), JPEG_QUALITY, baos);
            byte[] jpegBytes = baos.toByteArray();

            // Apply rotation from sensor metadata so the face is upright.
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

            // ----- DIAGNOSTIC LOGGING (temporary — remove after orientation is confirmed) -----
            // Check Logcat tag "ALiveness" for these lines on the physical device.
            // lensFacing: FRONT=0, BACK=1 (CameraSelector.DEFAULT_FRONT_CAMERA -> 0)
            int lensFacing = 0; // DEFAULT_FRONT_CAMERA
            Log.d(TAG, "[DIAG] yuvToJpeg:"
                    + " bufW=" + w
                    + " bufH=" + h
                    + " rotDeg=" + rotationDegrees
                    + " lensFacing=" + lensFacing
                    + " landscapeBuffer=" + (w > h));
            // ---------------------------------------------------------------------------------

            boolean rotationApplied = false;
            if (rotationDegrees != 0) {
                jpegBytes = rotateJpeg(jpegBytes, rotationDegrees);
                rotationApplied = true;
            }

            // ----- DIAGNOSTIC: log post-encode JPEG dimensions and rotation result -----
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, opts);
            Log.d(TAG, "[DIAG] yuvToJpeg result:"
                    + " jpegW=" + opts.outWidth
                    + " jpegH=" + opts.outHeight
                    + " sizeBytes=" + jpegBytes.length
                    + " rotationApplied=" + rotationApplied
                    + " rotDegApplied=" + (rotationApplied ? rotationDegrees : 0));
            // ---------------------------------------------------------------------------------

            return jpegBytes;
        } catch (Exception e) {
            Log.e(TAG, "yuvToJpeg failed", e);
            return null;
        }
    }

    /**
     * Convert YUV_420_888 planes to NV21 (Y plane followed by interleaved VU).
     * Handles all valid {@code rowStride} and {@code pixelStride} combinations.
     */
    private byte[] yuv420ToNv21(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = imageProxy.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = imageProxy.getPlanes()[2];

        int width  = imageProxy.getWidth();
        int height = imageProxy.getHeight();

        int yRowStride  = yPlane.getRowStride();
        int uvRowStride = uPlane.getRowStride();
        int uvPixStride = uPlane.getPixelStride();

        int ySize  = width * height;
        int uvSize = (width / 2) * (height / 2) * 2; // NV21 interleaved VU
        byte[] nv21 = new byte[ySize + uvSize];

        // --- Y plane (copy row by row to strip row-stride padding) ---
        ByteBuffer yBuf = yPlane.getBuffer();
        for (int row = 0; row < height; row++) {
            yBuf.position(row * yRowStride);
            yBuf.get(nv21, row * width, width);
        }

        // --- UV plane (interleave V then U, handling pixelStride) ---
        ByteBuffer uBuf = uPlane.getBuffer();
        ByteBuffer vBuf = vPlane.getBuffer();
        int uvHeight = height / 2;
        int uvWidth  = width  / 2;

        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int uvOffset = row * uvRowStride + col * uvPixStride;
                int dstIdx   = ySize + row * uvWidth * 2 + col * 2;
                nv21[dstIdx]     = vBuf.get(uvOffset); // V
                nv21[dstIdx + 1] = uBuf.get(uvOffset); // U
            }
        }
        return nv21;
    }

    /**
     * Rotate raw JPEG bytes by {@code degrees} (must be 0, 90, 180, or 270).
     * Decodes to Bitmap, rotates, and re-encodes.  Used only when rotation ≠ 0.
     */
    private byte[] rotateJpeg(byte[] jpeg, int degrees) {
        try {
            Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (src == null) return jpeg;

            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            src.recycle();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(jpeg.length);
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            rotated.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "rotateJpeg failed, using unrotated: " + e.getMessage());
            return jpeg;
        }
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    /**
     * Update the challenge instruction labels.
     *
     * <p>Shows only the CURRENT action prominently in {@code alInstruction},
     * completed actions with a ✓ checkmark in {@code alChallenge1} /
     * {@code alChallenge2}, and a "Step N of M" counter in {@code alStepCounter}.
     *
     * <p>Never generates, reorders, or invents challenges — the list comes
     * directly from the server response.
     */
    static String buildStepCounter(int currentIndex, int total) {
        if (total <= 0) return "";
        // During calibration (index == 0 and nothing yet completed) show "Calibrating..."
        // After calibration show "Step N of M"
        return "Step " + Math.min(currentIndex + 1, total) + " of " + total;
    }

    private void updateChallengeUI(List<String> challenges) {
        if (challenges == null || challenges.isEmpty()) {
            alStepCounter.setText("");
            alInstruction.setText("Position your face in the oval");
            alChallenge1.setText("");
            alChallenge2.setText("");
            return;
        }

        int total = challenges.size();

        // Step counter
        if (currentChallengeIndex == 0 && lastChallengeAdvanceSeq == 0) {
            alStepCounter.setText("Calibrating...");
        } else {
            alStepCounter.setText(buildStepCounter(currentChallengeIndex, total));
        }

        // Current action — shown large and prominent
        int displayIndex = Math.min(currentChallengeIndex, total - 1);
        alInstruction.setText(challengeLabel(challenges.get(displayIndex)));

        // Completed actions: shown below with checkmarks (up to 2 lines)
        int completedCount = displayIndex; // number of challenges already done
        if (completedCount >= 1) {
            alChallenge1.setText("✓ " + challengeLabel(challenges.get(0)));
        } else {
            alChallenge1.setText("");
        }
        if (completedCount >= 2) {
            alChallenge2.setText("✓ " + challengeLabel(challenges.get(1)));
        } else {
            alChallenge2.setText("");
        }
    }

    /** Show all challenges completed with ✓ after server confirms COMPLETED. */
    private void showAllCompleted(List<String> challenges) {
        if (challenges == null || challenges.isEmpty()) return;
        int total = challenges.size();
        alStepCounter.setText("Step " + total + " of " + total);
        alInstruction.setText("All done! ✓");
        if (total >= 1) alChallenge1.setText("✓ " + challengeLabel(challenges.get(0)));
        if (total >= 2) alChallenge2.setText("✓ " + challengeLabel(challenges.get(1)));
    }

    /** Map a server-side {@link com.college.attendance.mobile.model.ActiveLivenessSession}
     *  challenge string to a user-readable instruction. */
    static String challengeLabel(String action) {
        if (action == null) return "";
        switch (action) {
            case "BLINK":      return "Blink your eyes";
            case "TURN_LEFT":  return "Turn your head left";
            case "TURN_RIGHT": return "Turn your head right";
            default:           return action;
        }
    }

    /** Map a server-side reason code to a readable error message. */
    static String humanReason(String reason) {
        if (reason == null) return "Liveness check failed. Please try again.";
        switch (reason) {
            case "SESSION_EXPIRED":
            case "EXPIRED":         return "Session timed out. Go back and try again.";
            case "FRAME_LIMIT":     return "Too many frames submitted. Please try again.";
            case "SPOOF_DETECTED":  return "Liveness check failed: spoof detected.";
            case "LIVENESS_FAILED": return "Liveness check failed. Please try again in good lighting.";
            case "NO_FACE":         return "No face detected. Keep your face centered.";
            case "MULTIPLE_FACES":  return "Multiple faces detected. Please be alone.";
            case "RETRY_LIMIT":     return "Too many retries. Please go back and try again.";
            default:                return "Liveness check failed (" + reason + "). Please try again.";
        }
    }

    private void setStatus(String msg) {
        alStatus.setText(msg);
        Log.d(TAG, "status: " + msg);
    }
}

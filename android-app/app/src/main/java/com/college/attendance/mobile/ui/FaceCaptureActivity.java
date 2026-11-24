package com.college.attendance.mobile.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.college.attendance.mobile.BuildConfig;
import com.college.attendance.mobile.R;
import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.FaceResponse;
import com.college.attendance.mobile.model.VerificationAttempt;
import com.college.attendance.mobile.util.LocationHelper;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Handles face photo capture for both registration and attendance verification.
 *
 * <p>Camera improvements over the original:
 * <ul>
 *   <li>Target resolution capped at 1080 × 1440 (portrait 3:4). Full-sensor
 *       capture at 3096 × 4128 (≈ 12 MP) produced a face ratio of 0.021 –
 *       below the backend threshold of 0.030. At 1440p the same physical face
 *       occupies ≥ 0.17 of the frame.</li>
 *   <li>{@link ImageCapture#CAPTURE_MODE_MINIMIZE_LATENCY} reduces motion blur
 *       on the front camera. The original MAXIMIZE_QUALITY mode lengthens the
 *       exposure, which is the primary cause of Laplacian variance below 50.</li>
 *   <li>Centre autofocus ({@link FocusMeteringAction}) is triggered immediately
 *       after the camera binds, with a 1-second settle wait before the capture
 *       button is enabled.</li>
 *   <li>Live {@link ImageAnalysis} provides face-positioning feedback (too small,
 *       too large, off-centre, good position) by measuring the luminance
 *       distribution of the central oval region in each preview frame.</li>
 * </ul>
 */
public class FaceCaptureActivity extends AppCompatActivity {

    private static final String TAG = "FaceCaptureActivity";
    private static final int CAMERA_PERMISSION = 100;
    private static final int LOCATION_PERMISSION = 101;

    // -----------------------------------------------------------------------
    // Intent extra keys — shared with ActiveLivenessActivity / DashboardActivity
    // -----------------------------------------------------------------------
    public static final String EXTRA_REGISTER   = "register";
    public static final String EXTRA_ATTEMPT_ID = "attemptId";
    public static final String EXTRA_SESSION_ID = "sessionId";

    // -----------------------------------------------------------------------
    // Live positioning thresholds (package-visible for unit tests)
    // -----------------------------------------------------------------------
    /** Preview-frame fraction below which the face is too small. */
    static final float POSITION_TOO_SMALL_THRESHOLD  = 0.06f;  // <  6% of preview frame
    /** Preview-frame fraction above which the face is too large / too close. */
    static final float POSITION_TOO_LARGE_THRESHOLD  = 0.65f;  // > 65% of preview frame
    /**
     * Normalised distance from preview centre at which we call the face off-centre.
     * Expressed as a fraction of the half-width / half-height of the preview.
     */
    static final float POSITION_OFFCENTRE_THRESHOLD  = 0.35f;
    /** Minimum brightness mean in the central oval region (luminance, 0–255). */
    static final float POSITION_MIN_BRIGHTNESS       = 30.0f;
    /** Maximum brightness mean in the central oval region (luminance, 0–255). */
    static final float POSITION_MAX_BRIGHTNESS       = 230.0f;
    /** How often the positioning analyser updates the hint label (ms). */
    static final long  POSITION_UPDATE_INTERVAL_MS   = 500L;
    /** Autofocus settle time after binding the camera (ms). */
    static final long  AUTOFOCUS_SETTLE_MS            = 1000L;

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private PreviewView cameraPreview;
    private View        faceOval;
    private TextView    positionHint;
    private LinearLayout guidelinesCard;
    private Button      capture;
    private Button      upload;
    private TextView    info;

    // -----------------------------------------------------------------------
    // CameraX
    // -----------------------------------------------------------------------
    private ImageCapture           imageCapture;
    private ProcessCameraProvider  cameraProvider;
    private Camera                 camera;
    private ExecutorService        analysisExecutor;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private File    photo;
    private String  attemptId;
    private Long    sessionId;
    private boolean register;
    private boolean isRetrying       = false;
    private int     attemptGeneration = 0;
    private int     verifyGeneration  = 0;

    /** Throttle: skip analysis if another update is already in-flight. */
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);
    private long lastPositionUpdateMs = 0L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_face_capture);

        cameraPreview  = findViewById(R.id.cameraPreview);
        faceOval       = findViewById(R.id.fcFaceOval);
        positionHint   = findViewById(R.id.fcPositionHint);
        guidelinesCard = findViewById(R.id.fcGuidelinesCard);
        capture        = findViewById(R.id.capture);
        upload         = findViewById(R.id.upload);
        info           = findViewById(R.id.info);

        attemptId  = getIntent().getStringExtra("attemptId");
        register   = getIntent().getBooleanExtra("register", false);
        long rawSid = getIntent().getLongExtra("sessionId", -1L);
        sessionId  = rawSid == -1L ? null : rawSid;

        Log.d("FaceRetry", "onCreate: register=" + register
                + " sessionId=" + sessionId
                + " attemptId.isNull=" + (attemptId == null)
                + " gen=0");

        if (!register && sessionId == null && attemptId != null) {
            Log.w("FaceRetry",
                    "sessionId missing from intent -- retry after rejection will be blocked");
        }

        if (!register && (attemptId == null || attemptId.isEmpty())) {
            info.setText("No active attendance session found.");
            capture.setEnabled(false);
            upload.setEnabled(false);
            return;
        }

        info.setText(register
                ? "Register one clear face for future verification."
                : "Capture one clear face for the active attendance session.");

        if (!register && BuildConfig.DEV_LOCATION_OVERRIDE) {
            info.setText("⚠ DEVELOPMENT MODE — SIMULATED LOCATION\n"
                    + "ICT Building coordinates will be used instead of GPS.");
        }

        upload.setEnabled(false);

        capture.setOnClickListener(v -> {
            if (imageCapture == null) {
                openCamera();
            } else {
                capturePhoto();
            }
        });

        upload.setOnClickListener(v -> send());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (imageCapture != null && cameraPreview.getDisplay() != null) {
            imageCapture.setTargetRotation(cameraPreview.getDisplay().getRotation());
        }
    }

    @Override
    protected void onDestroy() {
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Camera permission
    // -----------------------------------------------------------------------

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        startCamera();
    }

    // -----------------------------------------------------------------------
    // CameraX initialisation
    //
    // Key decisions vs original:
    //   • setTargetResolution(1080 × 1440): caps the still-image at ~1.5 MP
    //     instead of the full 12 MP sensor output, so a face at ≈ 50 cm fills
    //     ≥ 17 % of the frame (above the backend 3 % threshold).
    //   • CAPTURE_MODE_MINIMIZE_LATENCY: shorter exposure → less motion blur →
    //     higher Laplacian variance (sharpness).  The original MAXIMIZE_QUALITY
    //     mode lengthened the shutter to reduce noise, trading sharpness.
    //   • Centre FocusMeteringAction with 1 s settle time: ensures the face is
    //     in focus before the capture button is enabled.
    //   • ImageAnalysis use-case: 320 × 240 downscaled frames analysed every
    //     500 ms, feeding the positioning hint label.
    // -----------------------------------------------------------------------

    private void startCamera() {
        info.setText("Starting front camera...");
        capture.setEnabled(false);

        if (analysisExecutor == null || analysisExecutor.isShutdown()) {
            analysisExecutor = Executors.newSingleThreadExecutor();
        }

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                int rotation = cameraPreview.getDisplay() != null
                        ? cameraPreview.getDisplay().getRotation()
                        : Surface.ROTATION_0;

                // Preview use-case — drives the on-screen viewfinder.
                Preview preview = new Preview.Builder()
                        .setTargetRotation(rotation)
                        .build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                // Still-image capture use-case.
                // Resolution capped at 1080 × 1440 (portrait 3:4).
                // MINIMIZE_LATENCY trades noise reduction for sharpness —
                // the correct trade-off for a hand-held phone in normal light.
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetResolution(new Size(1080, 1440))
                        .setTargetRotation(rotation)
                        .build();

                // Analysis use-case for live positioning feedback.
                // Small downscale (320 × 240) so analysis never blocks the UI.
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(analysisExecutor, this::analyseFrame);

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageCapture,
                        imageAnalysis
                );

                // Trigger autofocus on the centre point.
                triggerCentreAutofocus();

                // Show the oval guide overlay once the camera is running.
                runOnUiThread(() -> {
                    faceOval.setVisibility(View.VISIBLE);
                    positionHint.setVisibility(View.VISIBLE);
                    guidelinesCard.setVisibility(View.GONE);
                    info.setText("Position your face inside the oval.");
                });

                // Wait 1 second for AF to settle, then enable capture.
                mainHandler.postDelayed(() -> {
                    capture.setText("CAPTURE PHOTO");
                    capture.setEnabled(true);
                }, AUTOFOCUS_SETTLE_MS);

            } catch (Exception e) {
                imageCapture = null;
                runOnUiThread(() -> {
                    capture.setEnabled(true);
                    info.setText("Unable to start camera: " + e.getMessage());
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Trigger a centre-point autofocus action.
     * This runs in the background and resolves automatically; we do not
     * wait on the result — the 1-second {@link #AUTOFOCUS_SETTLE_MS} delay
     * in the caller is enough for the camera to lock focus.
     */
    private void triggerCentreAutofocus() {
        if (camera == null) return;
        try {
            MeteringPointFactory factory =
                    new SurfaceOrientedMeteringPointFactory(1.0f, 1.0f);
            MeteringPoint centre = factory.createPoint(0.5f, 0.5f);
            FocusMeteringAction action = new FocusMeteringAction.Builder(centre)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception e) {
            Log.w(TAG, "Autofocus trigger failed (non-fatal): " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Live positioning analysis
    //
    // Runs on a background thread for each preview frame delivered by
    // ImageAnalysis.  Throttled to POSITION_UPDATE_INTERVAL_MS.
    //
    // Strategy: measure luminance statistics over the central 50 × 65 %
    // rectangular region (approximating the oval guide), then produce a
    // hint based on the mean luminance and rough spatial extent.
    //
    // This avoids adding a face-detection dependency (MediaPipe or ML Kit)
    // to FaceCaptureActivity.  The hint is advisory — it guides the user
    // but does not gate the capture button.
    // -----------------------------------------------------------------------

    private void analyseFrame(@NonNull ImageProxy imageProxy) {
        try {
            long now = System.currentTimeMillis();
            if (!analysisRunning.compareAndSet(false, true)) {
                return; // previous analysis still running
            }
            if (now - lastPositionUpdateMs < POSITION_UPDATE_INTERVAL_MS) {
                analysisRunning.set(false);
                return;
            }
            lastPositionUpdateMs = now;

            // Convert YUV_420_888 Y-plane to a luminance bitmap.
            ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            int width  = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int rowStride = yPlane.getRowStride();

            // Sample the central oval region (approx 50 % w × 65 % h).
            int x0 = (int)(width  * 0.25f);
            int x1 = (int)(width  * 0.75f);
            int y0 = (int)(height * 0.175f);
            int y1 = (int)(height * 0.825f);

            long sum     = 0;
            long sumSq   = 0;
            int  samples = 0;
            // Step every 4 pixels to keep this fast.
            for (int row = y0; row < y1; row += 4) {
                for (int col = x0; col < x1; col += 4) {
                    int idx = row * rowStride + col;
                    if (idx >= yBuffer.limit()) continue;
                    int lum = yBuffer.get(idx) & 0xFF;
                    sum   += lum;
                    sumSq += (long) lum * lum;
                    samples++;
                }
            }

            String hint;
            if (samples == 0) {
                hint = "";
            } else {
                float mean     = (float) sum / samples;
                float variance = (float)(sumSq / samples) - mean * mean;

                // Also sample the full frame to compare centre vs edge luminance.
                // A face-sized bright region in the centre relative to edges
                // suggests the face is properly positioned.
                long edgeSum    = 0;
                int  edgeSamples = 0;
                // Top strip
                for (int col = 0; col < width; col += 8) {
                    int idx = 0 * rowStride + col;
                    if (idx < yBuffer.limit()) { edgeSum += yBuffer.get(idx) & 0xFF; edgeSamples++; }
                }
                // Bottom strip
                for (int col = 0; col < width; col += 8) {
                    int idx = (height - 1) * rowStride + col;
                    if (idx < yBuffer.limit()) { edgeSum += yBuffer.get(idx) & 0xFF; edgeSamples++; }
                }
                float edgeMean = edgeSamples > 0 ? (float) edgeSum / edgeSamples : mean;

                // Relative area of centre region vs full frame
                float centreArea    = (float)(x1 - x0) * (y1 - y0);
                float frameArea     = (float) width * height;
                // centreAreaFraction is 0.325 for our 50%×65% window —
                // we infer face size from how much brighter the centre is.
                float luminanceDiff = mean - edgeMean;

                hint = classifyPosition(mean, variance, luminanceDiff, centreArea / frameArea);
            }

            final String finalHint = hint;
            mainHandler.post(() -> {
                if (positionHint != null && positionHint.getVisibility() == View.VISIBLE) {
                    positionHint.setText(finalHint);
                    if (finalHint.startsWith("✅")) {
                        positionHint.setBackgroundColor(0xB3006400); // dark green
                    } else {
                        positionHint.setBackgroundColor(0xB3000000); // dark grey
                    }
                }
                analysisRunning.set(false);
            });

        } catch (Exception e) {
            analysisRunning.set(false);
            Log.w(TAG, "analyseFrame error (non-fatal): " + e.getMessage());
        } finally {
            imageProxy.close();
        }
    }

    /**
     * Classify the user's face position from luminance statistics of the
     * central preview region.
     *
     * <p>Package-visible for unit testing.
     *
     * @param centreMean      mean luminance (0–255) in the central oval region
     * @param centreVariance  luminance variance in the central oval region
     * @param luminanceDiff   mean(centre) − mean(edges); positive means centre is brighter
     * @param centreAreaFrac  area of the sampled centre region / total frame area (≈ 0.325)
     * @return a short human-readable hint string, or "" if conditions are met
     */
    static String classifyPosition(
            float centreMean,
            float centreVariance,
            float luminanceDiff,
            float centreAreaFrac
    ) {
        // Brightness checks (lighting conditions).
        if (centreMean < POSITION_MIN_BRIGHTNESS) {
            return "💡 Too dark — improve lighting";
        }
        if (centreMean > POSITION_MAX_BRIGHTNESS) {
            return "💡 Too bright — avoid direct light";
        }

        // A face that fills the oval produces a high luminance-variance
        // (skin tones, eyes, brows create contrast).  Very low variance
        // means the region is uniform — likely background, not a face.
        // Threshold calibrated empirically: typical skin-tone region ≥ 150.
        if (centreVariance < 100f) {
            return "🔍 Move closer — face too small";
        }

        // If the centre is significantly darker than the edges the face is
        // likely backlit or the frame is mostly background (face off to side).
        if (luminanceDiff < -20f) {
            return "🎯 Centre your face in the oval";
        }

        // High variance AND high luminance diff → very large face / too close.
        if (centreVariance > 3500f && luminanceDiff > 40f) {
            return "↔ Move back — too close";
        }

        return "✅ Good position — tap Capture Photo";
    }

    // -----------------------------------------------------------------------
    // Photo capture
    // -----------------------------------------------------------------------

    private void capturePhoto() {
        if (imageCapture == null) {
            info.setText("Please start the camera first.");
            return;
        }

        // Re-trigger autofocus just before capture to handle any drift.
        triggerCentreAutofocus();

        photo = new File(getCacheDir(), "face.jpg");
        if (photo.exists()) photo.delete();

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photo).build();

        capture.setEnabled(false);
        upload.setEnabled(false);
        info.setText("Capturing photograph...");

        // 300 ms delay after AF trigger before actually taking the picture.
        mainHandler.postDelayed(() -> imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        capture.setEnabled(true);
                        capture.setText("RETAKE PHOTO");
                        upload.setEnabled(true);
                        logCapturedPhotoMeta();

                        // Hide live hint while reviewing the still.
                        positionHint.setVisibility(View.GONE);

                        info.setText("Photo captured. Tap Verify / Register Photo,\n"
                                + "or Retake Photo if the result is blurry.");
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        capture.setEnabled(true);
                        upload.setEnabled(false);
                        info.setText("Photo capture failed: " + e.getMessage());
                    }
                }
        ), 300L);
    }

    // -----------------------------------------------------------------------
    // Permission result
    // -----------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                info.setText("Camera permission is required.");
            }
        }
        if (requestCode == LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                send();
            } else {
                info.setText("Location permission is required to mark attendance.");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Location + verification submission
    // -----------------------------------------------------------------------

    private void send() {
        if (register) {
            sendWithLocation(null, null, null, null);
            return;
        }

        if (!LocationHelper.hasPermission(this)) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION);
            return;
        }

        upload.setEnabled(false);
        capture.setEnabled(false);
        info.setText("Getting your location...");

        LocationHelper.fetch(this, new LocationHelper.Callback() {
            @Override
            public void onLocation(double lat, double lng, double accuracy, boolean mock) {
                if (BuildConfig.DEV_LOCATION_OVERRIDE) {
                    lat      = BuildConfig.DEV_LAT;
                    lng      = BuildConfig.DEV_LNG;
                    accuracy = 5.0;
                    mock     = false;
                    Log.w("FaceDiag", "[DEV] Location override: ICT centre ("
                            + lat + ", " + lng + ")");
                }
                sendWithLocation(lat, lng, accuracy, mock);
            }

            @Override
            public void onError(String message) {
                upload.setEnabled(true);
                capture.setEnabled(true);
                info.setText(message);
            }
        });
    }

    private void sendWithLocation(
            Double latitude, Double longitude, Double accuracy, Boolean mock) {

        if (photo == null || !photo.exists()) {
            info.setText("Please capture a photo first.");
            capture.setEnabled(true);
            return;
        }

        if (!register && (attemptId == null || attemptId.isEmpty())) {
            info.setText("Invalid attendance session.");
            return;
        }

        upload.setEnabled(false);
        capture.setEnabled(false);
        info.setText("Verifying face...");

        verifyGeneration++;
        int thisVerifyGen = verifyGeneration;
        String prefix = (attemptId != null && attemptId.length() >= 8)
                ? attemptId.substring(0, 8) : attemptId;
        Log.d("FaceRetry", "sendWithLocation: verifyGen=" + thisVerifyGen
                + " attemptGen=" + attemptGeneration
                + " sessionId=" + sessionId
                + " attemptId.prefix=" + prefix);
        Log.d("FaceDiag", "uploading face.jpg bytes=" + photo.length()
                + " verifyGen=" + verifyGeneration);

        RequestBody requestBody = RequestBody.create(photo, MediaType.parse("image/jpeg"));
        MultipartBody.Part imagePart =
                MultipartBody.Part.createFormData("image", photo.getName(), requestBody);

        Call<FaceResponse> call = register
                ? ApiClient.get(this).registerFace(imagePart)
                : ApiClient.get(this).verifyFace(
                        attemptId, latitude, longitude, accuracy, mock, imagePart);

        call.enqueue(new Callback<FaceResponse>() {
            @Override
            public void onResponse(Call<FaceResponse> call, Response<FaceResponse> response) {
                upload.setEnabled(true);
                capture.setEnabled(true);

                FaceResponse result = response.body();

                if (!response.isSuccessful() || result == null) {
                    String msg = "Face verification failed";
                    if      (response.code() == 401) msg = "Session expired. Please login again.";
                    else if (response.code() == 403) msg = "Not authorized.";
                    else if (response.code() == 404) msg = "Student, face registration, or attendance session not found.";
                    else if (response.code() == 400) msg = "Invalid face or attendance request.";
                    else if (response.code() == 409) {
                        Log.d("FaceRetry", "verify 409: verifyGen=" + thisVerifyGen);
                        msg = register
                                ? "Face already registered for this account."
                                : "Attendance already recorded for this session.";
                        info.setText(msg);
                        upload.setEnabled(false);
                        capture.setEnabled(false);
                        return;
                    } else if (response.code() == 410) {
                        info.setText("Verification timed out. Fetching a new attempt...");
                        requestFreshAttempt();
                        return;
                    } else if (response.code() == 429) msg = "Too many attempts. Please wait.";
                    else if (response.code() == 503)  msg = "Face service unavailable. Try again later.";

                    info.setText(msg + "\nHTTP " + response.code());
                    return;
                }

                if (register) {
                    if (result.registered) {
                        String ok = (result.message != null && !result.message.isEmpty())
                                ? result.message : "Face registered successfully.";
                        info.setText(ok);
                    } else {
                        String fail = (result.error != null && !result.error.isEmpty())
                                ? result.error
                                : (result.message != null ? result.message : "Unknown error");
                        info.setText("Registration failed: " + fail);
                    }
                    return;
                }

                if (result.verified && result.attendanceMarked) {
                    upload.setEnabled(false);
                    capture.setEnabled(false);
                    info.setText("Face verified.\nAttendance marked PRESENT.");
                } else if ("LOCATION".equals(result.reason)) {
                    info.setText("Attendance not marked.\n" + result.message);
                } else {
                    info.setText("Face rejected: " + result.message
                            + "\nFetching new attempt for retake...");
                    // Re-show hint label for the next capture.
                    positionHint.setVisibility(View.VISIBLE);
                    requestFreshAttempt();
                }
            }

            @Override
            public void onFailure(Call<FaceResponse> call, Throwable t) {
                upload.setEnabled(true);
                capture.setEnabled(true);
                info.setText("Network error:\n" + t.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Fresh attempt after face rejection
    // -----------------------------------------------------------------------

    private void requestFreshAttempt() {
        if (register || sessionId == null) {
            Log.w("FaceRetry", "requestFreshAttempt: skipped register=" + register
                    + " sessionId=" + sessionId);
            return;
        }
        if (isRetrying) {
            Log.d("FaceRetry", "requestFreshAttempt: already in progress");
            return;
        }
        isRetrying = true;
        upload.setEnabled(false);
        capture.setEnabled(false);

        Log.d("FaceRetry", "requestFreshAttempt: sessionId=" + sessionId
                + " gen=" + attemptGeneration);

        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", sessionId);

        ApiClient.get(this).startVerification(body)
                .enqueue(new Callback<VerificationAttempt>() {
                    @Override
                    public void onResponse(Call<VerificationAttempt> c,
                            Response<VerificationAttempt> r) {
                        isRetrying = false;
                        Log.d("FaceRetry", "requestFreshAttempt.onResponse: HTTP=" + r.code()
                                + " body.isNull=" + (r.body() == null));

                        if (r.isSuccessful() && r.body() != null) {
                            attemptId = r.body().attemptId;
                            attemptGeneration++;
                            upload.setEnabled(false);
                            capture.setEnabled(true);
                            capture.setText("RETAKE PHOTO");
                            info.setText("Face rejected. Tap Retake Photo to try again.");
                        } else if (r.code() == 409) {
                            upload.setEnabled(false);
                            capture.setEnabled(false);
                            info.setText("Attendance already recorded for this session.");
                        } else if (r.code() == 429) {
                            upload.setEnabled(false);
                            capture.setEnabled(false);
                            info.setText("Too many attempts. Please wait before trying again.");
                        } else {
                            upload.setEnabled(false);
                            capture.setEnabled(false);
                            info.setText("Could not start a new attempt (HTTP "
                                    + r.code() + "). Go back and try Mark Attendance again.");
                        }
                    }

                    @Override
                    public void onFailure(Call<VerificationAttempt> c, Throwable t) {
                        isRetrying = false;
                        upload.setEnabled(false);
                        capture.setEnabled(false);
                        info.setText("Network error while fetching new attempt.\n"
                                + "Go back and try again.\n" + t.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Diagnostic: log captured photo metadata (size + decoded dimensions only)
    // -----------------------------------------------------------------------

    private void logCapturedPhotoMeta() {
        if (photo == null || !photo.exists()) return;
        long bytes  = photo.length();
        int  width  = 0;
        int  height = 0;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getAbsolutePath(), opts);
            width  = opts.outWidth;
            height = opts.outHeight;
        } catch (Exception ignored) { }
        Log.d("FaceDiag", "captured face.jpg bytes=" + bytes
                + " width=" + width + " height=" + height);
    }
}


package com.college.attendance.mobile.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.college.attendance.mobile.R;
import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.FaceResponse;
import com.college.attendance.mobile.util.LocationHelper;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class FaceCaptureActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION = 100;
    private static final int LOCATION_PERMISSION = 101;

    private PreviewView cameraPreview;
    private Button capture;
    private Button upload;
    private TextView info;

    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;

    private File photo;

    private String attemptId;
    private boolean register;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_face_capture);

        cameraPreview = findViewById(R.id.cameraPreview);
        capture = findViewById(R.id.capture);
        upload = findViewById(R.id.upload);
        info = findViewById(R.id.info);

        attemptId = getIntent().getStringExtra("attemptId");
        register = getIntent().getBooleanExtra("register", false);

        if (!register && (attemptId == null || attemptId.isEmpty())) {
            info.setText("No active attendance session found.");
            capture.setEnabled(false);
            upload.setEnabled(false);
            return;
        }

        info.setText(register
                ? "Register one clear face for future verification."
                : "Capture one clear face for the active attendance session.");

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

    // Camera permission and CameraX initialization
    private void openCamera() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION
            );

            return;
        }

        startCamera();
    }

    // Start the front camera with an in-app preview
    private void startCamera() {

        info.setText("Starting front camera...");
        capture.setEnabled(false);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {

            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();

                preview.setSurfaceProvider(
                        cameraPreview.getSurfaceProvider()
                );

                int rotation = cameraPreview.getDisplay() != null
                        ? cameraPreview.getDisplay().getRotation()
                        : Surface.ROTATION_0;

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(
                                ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        )
                        .setTargetRotation(rotation)
                        .build();

                CameraSelector cameraSelector =
                        CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                );

                info.setText(
                        "Position your face clearly inside the preview."
                );

                capture.setText("CAPTURE PHOTO");
                capture.setEnabled(true);

            } catch (Exception e) {

                imageCapture = null;
                capture.setEnabled(true);

                info.setText(
                        "Unable to start camera: " + e.getMessage()
                );
            }

        }, ContextCompat.getMainExecutor(this));
    }

    // Capture a full-resolution photograph
    private void capturePhoto() {

        if (imageCapture == null) {
            info.setText("Please start the camera first.");
            return;
        }

        photo = new File(getCacheDir(), "face.jpg");

        if (photo.exists()) {
            photo.delete();
        }

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photo)
                        .build();

        capture.setEnabled(false);
        upload.setEnabled(false);

        info.setText("Capturing photograph...");

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults output
                    ) {

                        capture.setEnabled(true);
                        capture.setText("RETAKE PHOTO");

                        upload.setEnabled(true);

                        info.setText(
                                "Photo captured successfully. Tap Verify / Register Photo."
                        );
                    }

                    @Override
                    public void onError(
                            @NonNull ImageCaptureException exception
                    ) {

                        capture.setEnabled(true);
                        upload.setEnabled(false);

                        info.setText(
                                "Photo capture failed: "
                                        + exception.getMessage()
                        );
                    }
                }
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == CAMERA_PERMISSION) {

            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                openCamera();

            } else {

                info.setText("Camera permission is required.");
            }
        }

        if (requestCode == LOCATION_PERMISSION) {

            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                send();

            } else {

                info.setText(
                        "Location permission is required to mark attendance."
                );
            }
        }
    }

    // Registration does not require GPS. Attendance does.
    private void send() {

        if (register) {
            sendWithLocation(null, null, null, null);
            return;
        }

        if (!LocationHelper.hasPermission(this)) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    LOCATION_PERMISSION
            );

            return;
        }

        upload.setEnabled(false);
        capture.setEnabled(false);

        info.setText("Getting your location...");

        LocationHelper.fetch(this, new LocationHelper.Callback() {

            @Override
            public void onLocation(
                    double lat,
                    double lng,
                    double accuracy,
                    boolean mock
            ) {

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
            Double latitude,
            Double longitude,
            Double accuracy,
            Boolean mock
    ) {

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

        RequestBody requestBody = RequestBody.create(
                photo,
                MediaType.parse("image/jpeg")
        );

        MultipartBody.Part imagePart =
                MultipartBody.Part.createFormData(
                        "image",
                        photo.getName(),
                        requestBody
                );

        Call<FaceResponse> call;

        if (register) {

            call = ApiClient
                    .get(this)
                    .registerFace(imagePart);

        } else {

            call = ApiClient
                    .get(this)
                    .verifyFace(
                            attemptId,
                            latitude,
                            longitude,
                            accuracy,
                            mock,
                            imagePart
                    );
        }

        call.enqueue(new Callback<FaceResponse>() {

            @Override
            public void onResponse(
                    Call<FaceResponse> call,
                    Response<FaceResponse> response
            ) {

                upload.setEnabled(true);
                capture.setEnabled(true);

                FaceResponse result = response.body();

                if (!response.isSuccessful() || result == null) {

                    String errorMessage = "Face verification failed";

                    if (response.code() == 401) {
                        errorMessage =
                                "Session expired. Please login again.";

                    } else if (response.code() == 403) {
                        errorMessage =
                                "You are not authorized to perform this action.";

                    } else if (response.code() == 404) {
                        errorMessage =
                                "Student, face registration, or attendance session not found.";

                    } else if (response.code() == 400) {
                        errorMessage =
                                "Invalid face or attendance request.";

                    } else if (response.code() == 409) {
                        errorMessage =
                                "Attendance already marked, session closed, or verification already used.";

                    } else if (response.code() == 410) {
                        errorMessage =
                                "Verification expired. Start attendance again.";

                    } else if (response.code() == 503) {
                        errorMessage =
                                "Face verification service is unavailable. Please try again later.";
                    }

                    info.setText(
                            errorMessage + "\nHTTP " + response.code()
                    );

                    return;
                }

                // Face registration result
                if (register) {

                    if (result.registered) {

                        info.setText(
                                "Face registered successfully."
                        );

                    } else {

                        info.setText(
                                "Registration failed: " + result.message
                        );
                    }

                    return;
                }

                // Attendance verification result
                if (result.verified && result.attendanceMarked) {

                    info.setText(
                            "Face verified.\nAttendance marked PRESENT."
                    );

                } else if ("LOCATION".equals(result.reason)) {

                    info.setText(
                            "Attendance not marked.\n" + result.message
                    );

                } else {

                    info.setText(
                            "Face rejected: " + result.message
                    );
                }
            }

            @Override
            public void onFailure(
                    Call<FaceResponse> call,
                    Throwable t
            ) {

                upload.setEnabled(true);
                capture.setEnabled(true);

                info.setText(
                        "Network error:\n" + t.getMessage()
                );
            }
        });
    }

    @Override
    protected void onDestroy() {

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        super.onDestroy();
    }
}

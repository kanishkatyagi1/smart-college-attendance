package com.college.attendance.mobile.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.college.attendance.mobile.R;
import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.FaceResponse;

import java.io.File;
import java.io.FileOutputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public class FaceCaptureActivity extends AppCompatActivity {

 private static final int CAM = 77;
 private static final int CAMERA_PERMISSION = 100;

 ImageView preview;
 Button capture, upload;
 TextView info;

 File photo;

 Long studentId;
 Long sessionId;

 boolean register;


 @Override
 protected void onCreate(Bundle b) {

  super.onCreate(b);

  setContentView(R.layout.activity_face_capture);

  capture = findViewById(R.id.capture);
  upload = findViewById(R.id.upload);
  info = findViewById(R.id.info);

  studentId =
          getIntent().getLongExtra("studentId", 0);

  sessionId =
          getIntent().getLongExtra("sessionId", 0);

  register =
          getIntent().getBooleanExtra("register", false);

  if (studentId == 0) {

   info.setText("Invalid student ID.");
   capture.setEnabled(false);
   upload.setEnabled(false);

   return;
  }

  if (!register && sessionId == 0) {

   info.setText("No active attendance session found.");
   capture.setEnabled(false);
   upload.setEnabled(false);

   return;
  }

  info.setText(
          register
                  ? "Register one clear face for future verification."
                  : "Capture one clear face for the active attendance session."
  );

  upload.setEnabled(false);

  capture.setOnClickListener(v -> openCamera());

  upload.setOnClickListener(v -> send());
 }


 private void openCamera() {

  // Check camera permission
  if (ContextCompat.checkSelfPermission(
          this,
          Manifest.permission.CAMERA
  ) != PackageManager.PERMISSION_GRANTED) {

   ActivityCompat.requestPermissions(
           this,
           new String[]{
                   Manifest.permission.CAMERA
           },
           CAMERA_PERMISSION
   );

   return;
  }


  // Check whether camera application is available
  Intent cameraIntent =
          new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

  if (cameraIntent.resolveActivity(
          getPackageManager()
  ) == null) {

   info.setText(
           "Camera application is not available."
   );

   return;
  }


  try {

   startActivityForResult(
           cameraIntent,
           CAM
   );

  } catch (Exception e) {

   info.setText(
           "Unable to open camera: "
                   + e.getMessage()
   );
  }
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

   if (grantResults.length > 0 &&
           grantResults[0] ==
                   PackageManager.PERMISSION_GRANTED) {

    info.setText(
            "Camera permission granted. Opening camera..."
    );

    openCamera();

   } else {

    info.setText(
            "Camera permission is required."
    );
   }
  }
 }


 @Override
 protected void onActivityResult(
         int requestCode,
         int resultCode,
         Intent data
 ) {

  super.onActivityResult(
          requestCode,
          resultCode,
          data
  );


  if (requestCode != CAM) {
   return;
  }


  if (resultCode != RESULT_OK) {

   info.setText(
           "Camera cancelled."
   );

   return;
  }


  if (data == null ||
          data.getExtras() == null) {

   info.setText(
           "No image received from camera."
   );

   return;
  }


  try {

   Bitmap bitmap =
           (Bitmap) data
                   .getExtras()
                   .get("data");


   if (bitmap == null) {

    info.setText(
            "Unable to capture image."
    );

    return;
   }


   // Show captured image
   preview.setImageBitmap(bitmap);


   // Save image into app cache
   photo =
           new File(
                   getCacheDir(),
                   "face.jpg"
           );


   FileOutputStream outputStream =
           new FileOutputStream(photo);


   bitmap.compress(
           Bitmap.CompressFormat.JPEG,
           90,
           outputStream
   );


   outputStream.flush();
   outputStream.close();


   info.setText(
           "Photo captured. Tap Upload."
   );


   upload.setEnabled(true);

  } catch (Exception e) {

   info.setText(
           "Image processing error: "
                   + e.getMessage()
   );
  }
 }


 private void send() {

  if (photo == null ||
          !photo.exists()) {

   info.setText(
           "Please capture a photo first."
   );

   return;
  }


  if (studentId == null ||
          studentId == 0) {

   info.setText(
           "Invalid student ID."
   );

   return;
  }


  if (!register &&
          (sessionId == null ||
                  sessionId == 0)) {

   info.setText(
           "Invalid attendance session."
   );

   return;
  }


  // Disable button while uploading
  upload.setEnabled(false);
  capture.setEnabled(false);

  info.setText(
          "Verifying face..."
  );


  RequestBody requestBody =
          RequestBody.create(
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

   call =
           ApiClient
                   .get(this)
                   .registerFace(
                           studentId,
                           imagePart
                   );

  } else {

   call =
           ApiClient
                   .get(this)
                   .verifyFace(
                           studentId,
                           sessionId,
                           imagePart
                   );
  }


  call.enqueue(
          new Callback<FaceResponse>() {

           @Override
           public void onResponse(
                   Call<FaceResponse> call,
                   Response<FaceResponse> response
           ) {

            upload.setEnabled(true);
            capture.setEnabled(true);


            FaceResponse result =
                    response.body();


            if (!response.isSuccessful() ||
                    result == null) {

             String errorMessage =
                     "Face verification failed";

             if (response.code() == 401) {

              errorMessage =
                      "Session expired. Please login again.";

             } else if (response.code() == 404) {

              errorMessage =
                      "Student or attendance session not found.";

             } else if (response.code() == 400) {

              errorMessage =
                      "Invalid face or attendance request.";
             }


             info.setText(
                     errorMessage
                             + "\nHTTP "
                             + response.code()
             );

             return;
            }


            // Registration flow
            if (register) {

             if (result.registered) {

              info.setText(
                      "Face registered successfully."
              );

             } else {

              info.setText(
                      "Registration failed: "
                              + result.message
              );
             }

             return;
            }


            // Attendance flow
            if (result.verified) {

             info.setText(
                     "Face verified.\n"
                             + "Attendance marked PRESENT."
             );

            } else {

             info.setText(
                     "Face rejected: "
                             + result.message
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
                    "Network error:\n"
                            + t.getMessage()
            );
           }
          }
  );
 }
}

package com.college.attendance.mobile.api;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {

    private static final String TAG = "ApiClient";

    /** Loopback alias used by Android emulators to reach the host machine. */
    private static final String EMULATOR_BASE_URL = "http://10.0.2.2:8080/";

    /** Laptop's Wi-Fi hotspot IP — used when running on a physical device. */
    private static final String DEVICE_BASE_URL = "http://10.85.85.69:8080/";

    private static ApiService api;

    public static ApiService get(Context context) {

        if (api == null) {

            boolean isEmulator = isRunningOnEmulator();
            String base = isEmulator ? EMULATOR_BASE_URL : DEVICE_BASE_URL;

            Log.i(TAG, "Environment: " + (isEmulator ? "emulator" : "physical device")
                    + "  →  base URL: " + base);

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        Request request = chain.request();

                        String token = context
                                .getSharedPreferences("auth", 0)
                                .getString("token", "");

                        if (!token.isEmpty()) {
                            request = request.newBuilder()
                                    .addHeader("Authorization", "Bearer " + token)
                                    .build();
                        }

                        return chain.proceed(request);
                    })
                    .build();

            api = new Retrofit.Builder()
                    .baseUrl(base)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ApiService.class);
        }

        return api;
    }

    /**
     * Returns {@code true} when the process is running inside a standard
     * Android emulator (AOSP generic image or Android Studio AVD).
     *
     * <p>Checks several {@link Build} fields that are set to well-known
     * emulator values by the AOSP build system:
     * <ul>
     *   <li>{@code FINGERPRINT} starts with {@code "generic"} or {@code "unknown"}.</li>
     *   <li>{@code MODEL} contains {@code "Emulator"} or {@code "Android SDK"}.</li>
     *   <li>{@code MANUFACTURER} is {@code "Genymotion"} (third-party emulator).</li>
     *   <li>{@code BRAND} starts with {@code "generic"}.</li>
     *   <li>{@code DEVICE} starts with {@code "generic"}.</li>
     *   <li>{@code PRODUCT} contains {@code "sdk"} or {@code "vbox"}.</li>
     * </ul>
     * None of these fields is set by real device manufacturers.
     */
    private static boolean isRunningOnEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK")
                || Build.MANUFACTURER.equalsIgnoreCase("Genymotion")
                || Build.BRAND.startsWith("generic")
                || Build.DEVICE.startsWith("generic")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("vbox");
    }
}


package com.example.eventmanager.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.annotation.StringRes;

import com.example.eventmanager.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds the phone's current position and turns it into a readable address -
 * coursework feature (f): "Pick up the location automatically from the user's location".
 * <ol>
 *     <li><b>FusedLocationProviderClient</b> (Google Play services) gets ONE fresh position,
 *         combining GPS, Wi-Fi and mobile network for the best accuracy and battery use.</li>
 *     <li><b>Geocoder</b> converts the latitude/longitude into an address
 *         ("reverse geocoding"). It needs the internet, so it runs on a background thread.
 *         If no address is found, the coordinates are used instead.</li>
 * </ol>
 * The caller must check the location permission first.
 */
public final class LocationHelper {

    /** Result of {@link #fetchCurrentAddress}, always delivered on the main thread. */
    public interface Callback {
        void onAddress(@NonNull String address);

        void onError(@StringRes int message);
    }

    /** Same limit as the Location field of the event form. */
    private static final int MAX_ADDRESS_LENGTH = 100;

    /** Background thread for the (slow, network-based) Geocoder. */
    private static final ExecutorService GEOCODER_EXECUTOR = Executors.newSingleThreadExecutor();

    /** Private constructor: utility class, should not be instantiated. */
    private LocationHelper() {
    }

    /**
     * Gets the current position, then its address.
     * The listeners are tied to the Activity, so they are dropped if the screen closes.
     */
    @RequiresPermission(anyOf = {Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION})
    public static void fetchCurrentAddress(@NonNull Activity activity, @NonNull Callback callback) {
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(activity);
        CancellationTokenSource cancellation = new CancellationTokenSource();

        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.getToken())
                .addOnSuccessListener(activity, location -> {
                    if (location == null) {
                        // Usually: Location is switched off in the phone settings
                        callback.onError(R.string.location_error_unavailable);
                    } else {
                        reverseGeocode(activity.getApplicationContext(), location, callback);
                    }
                })
                .addOnFailureListener(activity,
                        e -> callback.onError(R.string.location_error_unavailable));
    }

    /** Converts coordinates into an address on a background thread. */
    private static void reverseGeocode(@NonNull Context context, @NonNull Location location,
                                       @NonNull Callback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        GEOCODER_EXECUTOR.execute(() -> {
            String result = formatCoordinates(location); // fallback if no address is found
            if (Geocoder.isPresent()) {
                try {
                    Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                    // The blocking version is fine here because we are on a background thread
                    @SuppressWarnings("deprecation")
                    List<Address> addresses = geocoder.getFromLocation(
                            location.getLatitude(), location.getLongitude(), 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        String address = formatAddress(addresses.get(0));
                        if (!address.isEmpty()) {
                            result = address;
                        }
                    }
                } catch (IOException | IllegalArgumentException e) {
                    // No internet or no geocoding service: keep the coordinates
                }
            }
            String finalResult = result;
            mainHandler.post(() -> callback.onAddress(finalResult));
        });
    }

    /** "12 Nguyen Hue, Ben Nghe, District 1, Ho Chi Minh City", cut to fit the form field. */
    @NonNull
    private static String formatAddress(@NonNull Address address) {
        String text = address.getMaxAddressLineIndex() >= 0 ? address.getAddressLine(0) : null;
        if (TextUtils.isEmpty(text)) {
            // Build it from the parts that exist
            List<String> parts = new ArrayList<>();
            addIfPresent(parts, address.getFeatureName());
            addIfPresent(parts, address.getThoroughfare());
            addIfPresent(parts, address.getSubAdminArea());
            addIfPresent(parts, address.getLocality());
            addIfPresent(parts, address.getAdminArea());
            text = TextUtils.join(", ", parts);
        }
        text = text.trim();
        return text.length() > MAX_ADDRESS_LENGTH ? text.substring(0, MAX_ADDRESS_LENGTH).trim() : text;
    }

    private static void addIfPresent(@NonNull List<String> parts, String value) {
        if (!TextUtils.isEmpty(value) && !parts.contains(value)) {
            parts.add(value);
        }
    }

    /** e.g. "10.77689, 106.70081" */
    @NonNull
    private static String formatCoordinates(@NonNull Location location) {
        return String.format(Locale.UK, "%.5f, %.5f", location.getLatitude(), location.getLongitude());
    }
}

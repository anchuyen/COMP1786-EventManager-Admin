package com.example.eventmanager.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

/**
 * Checks the network before uploading - coursework feature (e):
 * "The app should check the status if there is a network and be able to make
 * connection to the internet before uploading data."
 * <p>
 * Needs the ACCESS_NETWORK_STATE permission in AndroidManifest.xml.
 */
public final class NetworkUtil {

    /** Private constructor: utility class, should not be instantiated. */
    private NetworkUtil() {
    }

    /**
     * True only if the phone is connected to a network that really reaches the internet.
     * <ul>
     *     <li>NET_CAPABILITY_INTERNET - the network is meant to give internet access;</li>
     *     <li>NET_CAPABILITY_VALIDATED - Android has checked that the internet is actually
     *         reachable (e.g. false on a Wi-Fi with a login page that is not signed in yet).</li>
     * </ul>
     */
    public static boolean isOnline(@NonNull Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }

        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false; // Aeroplane mode, or Wi-Fi and mobile data both off
        }

        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}

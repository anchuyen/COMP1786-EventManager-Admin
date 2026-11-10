package com.example.eventmanager.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.example.eventmanager.R;
import com.example.eventmanager.data.EventRepository;
import com.example.eventmanager.data.SessionRepository;
import com.example.eventmanager.model.Event;
import com.example.eventmanager.model.Session;
import com.example.eventmanager.util.DateUtils;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Uploads the local SQLite data to Cloud Firestore - coursework features (e) and (f).
 * <p>
 * <b>Cloud structure</b> - one document per event, with its sessions inside it:
 * <pre>
 *   events/{eventId}  { name, date, time, location, organiser, ..., sessions: [ {...}, {...} ] }
 * </pre>
 * Keeping the sessions inside the event document means the hybrid (MAUI) app gets an
 * event and all its sessions in ONE request, and an event is always updated in one
 * atomic write (it can never be half-uploaded).
 * <p>
 * <b>Synchronisation rules</b>
 * <ol>
 *     <li>"Sync changes" uploads only events with is_synced = 0 (added or changed since the
 *         last upload, including when one of their sessions changed).
 *         "Upload all" uploads every event.</li>
 *     <li>The document ID is the local event ID, so uploading again UPDATES the same
 *         document instead of creating a duplicate.</li>
 *     <li>Events deleted on the phone are deleted from the cloud too.</li>
 * </ol>
 * <b>Threading</b> (feature f): the work runs on a background thread (ExecutorService),
 * so the screen never freezes. Results are sent back to the main thread with a Handler,
 * because only the main thread may touch views.
 */
public final class CloudSyncManager {

    /** Results of a sync, delivered on the MAIN thread. */
    public interface SyncCallback {
        /** Called before the first upload (total known) and after each uploaded event. */
        void onProgress(int done, int total);

        void onSuccess(int uploadedCount, int deletedCount);

        void onError(@NonNull String message);
    }

    /** Name of the Firestore collection (the MAUI app reads the same collection). */
    public static final String COLLECTION_EVENTS = "events";

    /** Give up if the server does not answer within this time. */
    private static final long TIMEOUT_SECONDS = 20;

    private static CloudSyncManager instance;

    private final Context appContext;
    private final EventRepository eventRepository;
    private final SessionRepository sessionRepository;
    private final FirebaseFirestore firestore;

    /** One background thread: syncs run one after the other, never at the same time. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Posts results back to the main (UI) thread. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** True while a sync is running (a second tap on Sync is ignored). */
    private volatile boolean running = false;

    private CloudSyncManager(@NonNull Context context) {
        appContext = context.getApplicationContext();
        eventRepository = EventRepository.getInstance(appContext);
        sessionRepository = SessionRepository.getInstance(appContext);
        firestore = FirebaseFirestore.getInstance();
    }

    /** Returns the one shared manager, creating it the first time. */
    @NonNull
    public static synchronized CloudSyncManager getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new CloudSyncManager(context);
        }
        return instance;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Starts a sync on the background thread. Returns immediately.
     *
     * @param uploadAll true = upload every event; false = only the changed ones
     * @param callback  receives progress and the result on the main thread
     */
    public void sync(boolean uploadAll, @NonNull SyncCallback callback) {
        if (running) {
            return;
        }
        running = true;

        executor.execute(() -> {
            try {
                int[] result = doSync(uploadAll, callback);
                mainHandler.post(() -> callback.onSuccess(result[0], result[1]));
            } catch (Exception e) {
                String message = describeError(e);
                mainHandler.post(() -> callback.onError(message));
            } finally {
                running = false;
            }
        });
    }

    // =====================================================================
    // Work done on the background thread
    // =====================================================================

    /**
     * Uploads, then removes deleted events from the cloud.
     * Tasks.await() blocks until Firestore answers - this is only allowed (and safe)
     * because we are NOT on the main thread.
     *
     * @return {number of uploaded events, number of events deleted from the cloud}
     */
    @WorkerThread
    @NonNull
    private int[] doSync(boolean uploadAll, @NonNull SyncCallback callback) throws Exception {
        CollectionReference eventsCollection = firestore.collection(COLLECTION_EVENTS);

        // ----- Step 1: upload new / changed events (or all of them) -----
        List<Event> toUpload = uploadAll
                ? eventRepository.getAllEvents()
                : eventRepository.getUnsyncedEvents();
        int total = toUpload.size();
        mainHandler.post(() -> callback.onProgress(0, total));

        int uploaded = 0;
        for (Event event : toUpload) {
            // Remember when this upload started: if the user edits the event while it is
            // uploading, markEventSynced() will NOT clear the flag, so it is sent again next time
            long uploadStartedAt = System.currentTimeMillis();
            List<Session> sessions = sessionRepository.getSessionsForEvent(event.getId());

            // set() replaces the whole document -> creates it, or updates it without duplicates
            Tasks.await(eventsCollection.document(String.valueOf(event.getId()))
                            .set(toCloudData(event, sessions)),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS);

            eventRepository.markEventSynced(event.getId(), uploadStartedAt);
            uploaded++;
            int done = uploaded;
            mainHandler.post(() -> callback.onProgress(done, total));
        }

        // ----- Step 2: delete from the cloud the events that no longer exist locally -----
        Set<String> localIds = new HashSet<>();
        for (Event event : eventRepository.getAllEvents()) {
            localIds.add(String.valueOf(event.getId()));
        }

        // Source.SERVER: read the real cloud data, not Firestore's local cache
        QuerySnapshot cloudEvents = Tasks.await(eventsCollection.get(Source.SERVER),
                TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int deleted = 0;
        for (DocumentSnapshot document : cloudEvents.getDocuments()) {
            if (!localIds.contains(document.getId())) {
                Tasks.await(document.getReference().delete(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
                deleted++;
            }
        }

        return new int[]{uploaded, deleted};
    }

    // =====================================================================
    // Conversion: local objects -> Firestore data
    // =====================================================================

    /**
     * Builds the Firestore document of one event. Both the stored codes (e.g. "PANEL_DISCUSSION")
     * and readable labels (e.g. "Panel Discussion") are sent, so the MAUI app can show them
     * directly.
     */
    @NonNull
    private Map<String, Object> toCloudData(@NonNull Event event, @NonNull List<Session> sessions) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", event.getId());
        data.put("name", event.getName());
        data.put("date", DateUtils.formatForInput(event.getDateMillis())); // "25/12/2026"
        data.put("dateMillis", event.getDateMillis());                       // for sorting
        data.put("time", event.getTime());
        data.put("location", event.getLocation());
        data.put("organiser", event.getOrganiser());
        data.put("type", event.getType().name());
        data.put("typeLabel", appContext.getString(event.getType().getLabelRes()));
        data.put("status", event.getStatus().name());
        data.put("statusLabel", appContext.getString(event.getStatus().getLabelRes()));
        data.put("attendees", event.getAttendees());
        data.put("description", event.getDescription());   // null when not entered
        data.put("contactEmail", event.getContactEmail());
        data.put("contactPhone", event.getContactPhone());
        data.put("sessionCount", sessions.size());
        data.put("updatedAt", FieldValue.serverTimestamp()); // time set by the server

        List<Map<String, Object>> sessionList = new ArrayList<>();
        for (Session session : sessions) {
            Map<String, Object> s = new HashMap<>();
            s.put("id", session.getId());
            s.put("name", session.getName());
            s.put("time", session.getTime());
            s.put("speaker", session.getSpeaker());
            s.put("room", session.getRoom());
            s.put("type", session.getType().name());
            s.put("typeLabel", appContext.getString(session.getType().getLabelRes()));
            s.put("description", session.getDescription());
            sessionList.add(s);
        }
        data.put("sessions", sessionList);
        return data;
    }

    // =====================================================================
    // Error messages
    // =====================================================================

    /** Turns a technical exception into a short message the user can understand. */
    @NonNull
    private String describeError(@NonNull Exception e) {
        if (e instanceof TimeoutException) {
            return appContext.getString(R.string.sync_error_timeout);
        }
        Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
        if (cause instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) cause).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                return appContext.getString(R.string.sync_error_permission);
            }
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                return appContext.getString(R.string.sync_error_timeout);
            }
        }
        return appContext.getString(R.string.sync_error_generic,
                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }
}

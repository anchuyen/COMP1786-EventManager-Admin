package com.example.eventmanager;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.eventmanager.data.EventRepository;
import com.example.eventmanager.data.SessionRepository;
import com.example.eventmanager.databinding.ActivityEventDetailBinding;
import com.example.eventmanager.databinding.DialogSessionFormBinding;
import com.example.eventmanager.databinding.DialogSessionQrBinding;
import com.example.eventmanager.model.Event;
import com.example.eventmanager.model.Session;
import com.example.eventmanager.model.SessionType;
import com.example.eventmanager.util.DateUtils;
import com.example.eventmanager.util.QrCodeHelper;
import com.example.eventmanager.util.ValidationUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.zxing.WriterException;

import java.util.ArrayList;
import java.util.List;

/**
 * Details of ONE event and management of its sessions - coursework features (b) and (c).
 * <ul>
 *     <li>Header card: all information of the event (feature c: "select an event and
 *         display all sessions").</li>
 *     <li>"Sessions (N)": the total number of sessions of this event.</li>
 *     <li>"+" button: add a session. Tap a session: edit it. Bin icon: delete it (with Undo).</li>
 *     <li>Toolbar: edit the event (opens EventFormActivity) or delete it. Deleting the event
 *         also deletes its sessions, thanks to ON DELETE CASCADE in the database.</li>
 * </ul>
 */
public class EventDetailActivity extends AppCompatActivity {

    private static final String EXTRA_EVENT_ID = "extra_event_id";
    private static final String TAG_SESSION_TIME_PICKER = "session_time_picker";

    /** Maximum length of a session name. */
    private static final int MAX_SESSION_NAME_LENGTH = 100;

    /** Time shown in the clock picker when the time field is still empty. */
    private static final int DEFAULT_PICKER_HOUR = 9;

    private ActivityEventDetailBinding binding;
    private EventRepository eventRepository;
    private SessionRepository sessionRepository;
    private SessionAdapter adapter;

    /** ID of the event shown on this screen. */
    private long eventId = Event.NO_ID;

    /** The event as last loaded from the database (null until loaded). */
    @Nullable
    private Event event;

    /** Text typed in the session search box ("" means show all sessions). */
    @NonNull
    private String sessionQuery = "";

    // =====================================================================
    // Creating the Intent (the only way other screens should open this screen)
    // =====================================================================

    /** Returns an Intent that opens the details of the given event. */
    @NonNull
    public static Intent newIntent(@NonNull Context context, long eventId) {
        return new Intent(context, EventDetailActivity.class)
                .putExtra(EXTRA_EVENT_ID, eventId);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityEventDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        eventRepository = EventRepository.getInstance(this);
        sessionRepository = SessionRepository.getInstance(this);
        eventId = getIntent().getLongExtra(EXTRA_EVENT_ID, Event.NO_ID);

        applyWindowInsets();
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        setupSessionSearch();

        adapter = new SessionAdapter(new SessionAdapter.OnSessionActionListener() {
            @Override
            public void onSessionClick(@NonNull Session session) {
                showSessionDialog(session); // Edit
            }

            @Override
            public void onSessionDelete(@NonNull Session session) {
                confirmDeleteSession(session);
            }

            @Override
            public void onSessionQr(@NonNull Session session) {
                showSessionQr(session);
            }
        });
        binding.recyclerSessions.setAdapter(adapter);

        binding.fabAddSession.setOnClickListener(v -> showSessionDialog(null)); // Add
    }

    /**
     * Reloads the event every time the screen is shown, so changes made in the
     * Edit Event form appear immediately when the user comes back.
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadEvent();
    }

    /** Keeps the content clear of the status bar and navigation bar. */
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    // =====================================================================
    // Event header
    // =====================================================================

    /** Loads the event; closes the screen if it no longer exists (e.g. deleted in the form). */
    private void loadEvent() {
        event = eventRepository.getEventById(eventId);
        if (event == null) {
            finish();
            return;
        }
        bindEventHeader(event);
        refreshSessions();
    }

    private void bindEventHeader(@NonNull Event event) {
        binding.tvDetailName.setText(event.getName());
        binding.tvDetailDateTime.setText(
                DateUtils.formatForDisplay(event.getDateMillis()) + " · " + event.getTime());
        binding.tvDetailLocation.setText(event.getLocation());
        binding.tvDetailOrganiser.setText(event.getOrganiser());

        // Contact line: only the parts that were entered
        List<String> contact = new ArrayList<>();
        if (!TextUtils.isEmpty(event.getContactEmail())) {
            contact.add(event.getContactEmail());
        }
        if (!TextUtils.isEmpty(event.getContactPhone())) {
            contact.add(event.getContactPhone());
        }
        setTextOrHide(binding.tvDetailContact, TextUtils.join(" · ", contact));
        setTextOrHide(binding.tvDetailDescription, event.getDescription());
    }

    /** Toolbar actions: Edit event / Delete event. */
    private boolean onMenuItemClick(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_edit_event) {
            startActivity(EventFormActivity.newEditIntent(this, eventId));
            return true;
        } else if (item.getItemId() == R.id.action_delete_event) {
            confirmDeleteEvent();
            return true;
        }
        return false;
    }

    /**
     * Feature (c): deleting an event also deletes its sessions.
     * The dialog tells the user how many sessions will be lost.
     */
    private void confirmDeleteEvent() {
        if (event == null) {
            return;
        }
        // Count from the database, not the adapter: the list may be filtered by a search
        int sessionCount = sessionRepository.getSessionCount(eventId);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(getResources().getQuantityString(
                        R.plurals.dialog_delete_event_with_sessions,
                        sessionCount, event.getName(), sessionCount))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> deleteEvent())
                .show();
    }

    private void deleteEvent() {
        if (event == null) {
            return;
        }
        // One DELETE on "events"; SQLite removes the sessions itself (ON DELETE CASCADE)
        if (eventRepository.deleteEvent(eventId)) {
            Toast.makeText(this, getString(R.string.snackbar_event_deleted, event.getName()),
                    Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
        }
    }

    // =====================================================================
    // Session list
    // =====================================================================

    /** Feature (d): search box in the toolbar filters this event's sessions as the user types. */
    private void setupSessionSearch() {
        MenuItem searchItem = binding.toolbar.getMenu().findItem(R.id.action_search_sessions);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null) {
            return;
        }
        searchView.setQueryHint(getString(R.string.search_sessions_hint));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus(); // Hide the keyboard
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                sessionQuery = (newText == null) ? "" : newText;
                refreshSessions();
                return true;
            }
        });
    }

    /**
     * Reloads the sessions from SQLite (filtered by the search text, if any).
     * The header always shows the TOTAL number of sessions of the event (feature c).
     */
    private void refreshSessions() {
        List<Session> sessions = sessionRepository.searchSessions(eventId, sessionQuery);
        int total = sessionRepository.getSessionCount(eventId);
        adapter.submitList(sessions);

        boolean isSearching = !sessionQuery.trim().isEmpty();
        binding.tvSessionsHeader.setText(isSearching
                ? getString(R.string.label_sessions_filtered, sessions.size(), total)
                : getString(R.string.label_sessions_count, total));

        boolean isEmpty = sessions.isEmpty();
        binding.layoutSessionsEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerSessions.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        if (isEmpty) {
            boolean noSessionsAtAll = (total == 0);
            binding.tvSessionsEmptyTitle.setText(noSessionsAtAll
                    ? R.string.sessions_empty_title : R.string.sessions_no_match_title);
            binding.tvSessionsEmptyMessage.setText(noSessionsAtAll
                    ? R.string.sessions_empty_message : R.string.sessions_no_match_message);
        }
    }

    // =====================================================================
    // Add / Edit session dialog
    // =====================================================================

    /**
     * Opens the session form in a dialog.
     *
     * @param existing the session to edit, or null to add a new one
     */
    private void showSessionDialog(@Nullable Session existing) {
        DialogSessionFormBinding form = DialogSessionFormBinding.inflate(getLayoutInflater());

        // ----- Type drop-down: the six types from SessionType -----
        SessionType[] types = SessionType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = getString(types[i].getLabelRes());
        }
        form.actvSessionType.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));

        // One-element array so the lambda below can change it
        SessionType[] selectedType = {SessionType.OTHERS};
        form.actvSessionType.setOnItemClickListener(
                (parent, view, position, id) -> selectedType[0] = types[position]);

        // ----- Edit mode: fill the fields -----
        if (existing != null) {
            form.edtSessionName.setText(existing.getName());
            form.edtSessionTime.setText(existing.getTime());
            form.edtSpeaker.setText(existing.getSpeaker());
            form.edtRoom.setText(existing.getRoom());
            form.edtSessionDescription.setText(existing.getDescription());
            selectedType[0] = existing.getType();
        }
        // "false" = do not filter the drop-down list
        form.actvSessionType.setText(getString(selectedType[0].getLabelRes()), false);

        // ----- Clock button and "remove the error while typing" -----
        form.tilSessionTime.setEndIconOnClickListener(v -> showTimePicker(form.edtSessionTime));
        clearErrorWhileTyping(form.edtSessionName, form.tilSessionName);
        clearErrorWhileTyping(form.edtSessionTime, form.tilSessionTime);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.title_add_session : R.string.title_edit_session)
                .setView(form.getRoot())
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_save, null) // real listener set below
                .create();

        // By default a dialog closes on ANY button. We replace the Save listener so the
        // dialog stays open (showing the errors) when the input is invalid.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Session session = validateSession(form, existing, selectedType[0]);
                    if (session != null && saveSession(session, existing == null)) {
                        dialog.dismiss();
                    }
                }));
        dialog.show();
    }

    /**
     * Checks the required fields (name and time).
     *
     * @return the session built from the form, or null if a field is invalid
     */
    @Nullable
    private Session validateSession(@NonNull DialogSessionFormBinding form,
                                    @Nullable Session existing,
                                    @NonNull SessionType type) {
        String name = readText(form.edtSessionName);
        String time = readText(form.edtSessionTime);
        boolean isValid = true;

        if (name.isEmpty()) {
            form.tilSessionName.setError(getString(R.string.error_session_name_required));
            isValid = false;
        } else if (name.length() > MAX_SESSION_NAME_LENGTH) {
            form.tilSessionName.setError(getString(R.string.error_too_long));
            isValid = false;
        }

        if (time.isEmpty()) {
            form.tilSessionTime.setError(getString(R.string.error_session_time_required));
            isValid = false;
        } else if (!ValidationUtils.isValidTime(time)) {
            form.tilSessionTime.setError(getString(R.string.error_time_format));
            isValid = false;
        }

        if (!isValid) {
            return null;
        }

        long sessionId = (existing == null) ? Session.NO_ID : existing.getId();
        return new Session(sessionId, eventId, name, time,
                readText(form.edtSpeaker),
                readText(form.edtRoom),
                type,
                readText(form.edtSessionDescription));
    }

    /**
     * Inserts or updates the session, then refreshes the list.
     *
     * @return true if the database write succeeded
     */
    private boolean saveSession(@NonNull Session session, boolean isNew) {
        boolean success = isNew
                ? sessionRepository.addSession(session) != null
                : sessionRepository.updateSession(session);

        if (!success) {
            Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
            return false;
        }

        int messageRes = isNew ? R.string.toast_session_added : R.string.toast_session_updated;
        Toast.makeText(this, getString(messageRes, session.getName()), Toast.LENGTH_SHORT).show();
        refreshSessions();
        return true;
    }

    /** Material 24-hour clock; the chosen time is written into the given field. */
    private void showTimePicker(@NonNull TextInputEditText target) {
        int hour = DEFAULT_PICKER_HOUR;
        int minute = 0;
        String typed = readText(target);
        if (ValidationUtils.isValidTime(typed)) {
            hour = Integer.parseInt(typed.substring(0, 2));
            minute = Integer.parseInt(typed.substring(3, 5));
        }

        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText(R.string.time_picker_title)
                .build();
        picker.addOnPositiveButtonClickListener(v ->
                target.setText(ValidationUtils.formatTime(picker.getHour(), picker.getMinute())));
        picker.show(getSupportFragmentManager(), TAG_SESSION_TIME_PICKER);
    }

    // =====================================================================
    // Session check-in QR code - feature (f)
    // =====================================================================

    /**
     * Generates the QR code of a session (with ZXing) and shows it in a dialog.
     * It is generated again every time, so it always contains the latest details.
     */
    private void showSessionQr(@NonNull Session session) {
        if (event == null) {
            return;
        }

        Bitmap qrBitmap;
        try {
            int sizePx = getResources().getDimensionPixelSize(R.dimen.qr_code_size);
            qrBitmap = QrCodeHelper.generate(QrCodeHelper.buildSessionPayload(event, session), sizePx);
        } catch (WriterException e) {
            Toast.makeText(this, R.string.qr_error, Toast.LENGTH_SHORT).show();
            return;
        }

        DialogSessionQrBinding qr = DialogSessionQrBinding.inflate(getLayoutInflater());
        qr.ivQrCode.setImageBitmap(qrBitmap);
        qr.tvQrSessionName.setText(session.getName());

        // "Tech Summit · Fri, 25 Dec 2026 · 09:30 · Hall 1"
        List<String> details = new ArrayList<>();
        details.add(event.getName());
        details.add(DateUtils.formatForDisplay(event.getDateMillis()));
        details.add(session.getTime());
        if (!TextUtils.isEmpty(session.getRoom())) {
            details.add(session.getRoom());
        }
        qr.tvQrDetails.setText(TextUtils.join(" · ", details));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.qr_dialog_title)
                .setView(qr.getRoot())
                .setPositiveButton(R.string.btn_close, null)
                .show();
    }

    // =====================================================================
    // Delete session (with Undo)
    // =====================================================================

    private void confirmDeleteSession(@NonNull Session session) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_session_title)
                .setMessage(getString(R.string.dialog_delete_message, session.getName()))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> deleteSession(session))
                .show();
    }

    private void deleteSession(@NonNull Session session) {
        if (!sessionRepository.deleteSession(session)) {
            Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
            return;
        }
        refreshSessions();

        Snackbar.make(binding.main,
                        getString(R.string.snackbar_session_deleted, session.getName()),
                        Snackbar.LENGTH_LONG)
                .setAnchorView(binding.fabAddSession)
                .setAction(R.string.btn_undo, v -> {
                    if (sessionRepository.restoreSession(session)) {
                        refreshSessions();
                    } else {
                        Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Returns the trimmed text of a field (never null). */
    @NonNull
    private static String readText(@NonNull TextInputEditText field) {
        Editable text = field.getText();
        return (text == null) ? "" : text.toString().trim();
    }

    /** Shows the text, or hides the view when there is nothing to show. */
    private static void setTextOrHide(@NonNull android.widget.TextView view, @Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }

    /** Removes the error message of a field as soon as the user types in it. */
    private static void clearErrorWhileTyping(@NonNull TextInputEditText field,
                                              @NonNull TextInputLayout layout) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }
}
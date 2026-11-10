package com.example.eventmanager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;

import com.example.eventmanager.data.EventRepository;
import com.example.eventmanager.databinding.ActivityEventFormBinding;
import com.example.eventmanager.model.Event;
import com.example.eventmanager.model.EventStatus;
import com.example.eventmanager.model.EventType;
import com.example.eventmanager.util.DateUtils;
import com.example.eventmanager.util.LocationHelper;
import com.example.eventmanager.util.ValidationUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Calendar;

/**
 * Event form used in two modes:
 * <ul>
 *     <li><b>Add</b> – opened from the "+" button, all fields start empty;</li>
 *     <li><b>Edit</b> – opened by tapping an event, fields are filled from the database
 *         and the event can be updated or deleted.</li>
 * </ul>
 * Coursework feature (a) - the "Save" button now follows four steps:
 * <ol>
 *     <li><b>Validate</b> every field (required fields, date DD/MM/YYYY, time HH:mm,
 *         optional email and phone formats). All errors are shown at once.</li>
 *     <li><b>Check for duplicates</b>: no other event may have the same name on the same date.</li>
 *     <li><b>Confirm</b>: a dialog shows every value back to the user. "Edit" closes the
 *         dialog and keeps the form as it is; "Confirm &amp; save" continues.</li>
 *     <li><b>Save</b> to SQLite and show a Toast.</li>
 * </ol>
 */
public class EventFormActivity extends AppCompatActivity {

    private static final String EXTRA_EVENT_ID = "extra_event_id";
    private static final String KEY_SELECTED_TYPE = "key_selected_type";
    private static final String KEY_INITIAL_STATE = "key_initial_state";
    private static final String TAG_TIME_PICKER = "time_picker";

    /** Allowed length for name, location and organiser. */
    private static final int MIN_TEXT_LENGTH = 2;
    private static final int MAX_TEXT_LENGTH = 100;

    /** Allowed range for the number of expected attendees. */
    private static final int MIN_ATTENDEES = 1;
    private static final int MAX_ATTENDEES = 100_000;

    /** Time shown in the clock picker when the time field is still empty. */
    private static final int DEFAULT_PICKER_HOUR = 9;

    /** Transparency (0-255) of the light background of the preview icon. */
    private static final int PREVIEW_TINT_ALPHA = 0x33;

    private ActivityEventFormBinding binding;
    private EventRepository repository;

    /** ID of the event being edited, or Event.NO_ID when adding a new event. */
    private long eventId = Event.NO_ID;

    /** Event type chosen in the drop-down, or null if nothing is selected yet. */
    @Nullable
    private EventType selectedType;

    /** Snapshot of the form when it was opened, used to detect unsaved changes. */
    private String initialFormState = "";

    /** Feature (f): true while the GPS position is being looked up (ignores repeated taps). */
    private boolean isLocating = false;

    /**
     * Feature (f): asks Android for the location permission and receives the answer.
     * It must be registered before the screen starts, so it is created here (as a field).
     */
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = Boolean.TRUE.equals(
                                result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                                || Boolean.TRUE.equals(
                                result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                        if (granted) {
                            fetchCurrentLocation();
                        } else {
                            Toast.makeText(this, R.string.location_permission_denied,
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    // =====================================================================
    // Creating the Intent (the only way other screens should open this form)
    // =====================================================================

    /** Returns an Intent that opens the form to ADD a new event. */
    @NonNull
    public static Intent newAddIntent(@NonNull Context context) {
        return new Intent(context, EventFormActivity.class);
    }

    /** Returns an Intent that opens the form to EDIT an existing event. */
    @NonNull
    public static Intent newEditIntent(@NonNull Context context, long eventId) {
        return new Intent(context, EventFormActivity.class)
                .putExtra(EXTRA_EVENT_ID, eventId);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityEventFormBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = EventRepository.getInstance(this);
        eventId = getIntent().getLongExtra(EXTRA_EVENT_ID, Event.NO_ID);

        applyWindowInsets();
        setupToolbar();
        setupTypeDropdown();
        setupDatePicker();
        setupTimePicker();
        setupLocationButton();
        setupFieldListeners();
        setupBackHandling();
        binding.rgStatus.setOnCheckedChangeListener((group, checkedId) -> updatePreview());
        binding.btnSave.setOnClickListener(v -> onSaveClicked());
        binding.btnDelete.setOnClickListener(v -> confirmDelete());

        if (isEditMode()) {
            binding.toolbar.setTitle(R.string.title_edit_event);
            binding.tvPreviewMode.setText(R.string.preview_mode_edit);
            binding.btnSave.setText(R.string.btn_update_event);
            binding.btnDelete.setVisibility(View.VISIBLE);
        }

        if (savedInstanceState == null) {
            // First time the screen opens: load the event (Edit mode only)
            if (isEditMode() && !loadEvent()) {
                return;
            }
            initialFormState = currentFormState();
        } else {
            // Screen was recreated (e.g. rotation): restore what Android cannot restore itself
            String typeName = savedInstanceState.getString(KEY_SELECTED_TYPE);
            if (typeName != null) {
                selectType(EventType.valueOf(typeName));
            }
            initialFormState = savedInstanceState.getString(KEY_INITIAL_STATE, "");
            reattachTimePicker();
        }
        updatePreview();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedType != null) {
            outState.putString(KEY_SELECTED_TYPE, selectedType.name());
        }
        outState.putString(KEY_INITIAL_STATE, initialFormState);
    }

    private boolean isEditMode() {
        return eventId != Event.NO_ID;
    }

    /**
     * Fills the form with the event being edited.
     *
     * @return false if the event no longer exists (the screen is then closed)
     */
    private boolean loadEvent() {
        Event event = repository.getEventById(eventId);
        if (event == null) {
            Toast.makeText(this, R.string.error_event_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return false;
        }

        binding.edtName.setText(event.getName());
        binding.edtDate.setText(DateUtils.formatForInput(event.getDateMillis()));
        binding.edtTime.setText(event.getTime());
        binding.edtLocation.setText(event.getLocation());
        binding.edtOrganiser.setText(event.getOrganiser());
        binding.edtAttendees.setText(String.valueOf(event.getAttendees()));
        binding.edtDescription.setText(event.getDescription());
        binding.edtEmail.setText(event.getContactEmail());
        binding.edtPhone.setText(event.getContactPhone());
        selectType(event.getType());
        setSelectedStatus(event.getStatus());
        return true;
    }

    // =====================================================================
    // Setup
    // =====================================================================

    /** Keeps the form clear of the system bars and the keyboard. */
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    /** The back arrow behaves exactly like the system Back button. */
    private void setupToolbar() {
        binding.toolbar.setNavigationOnClickListener(v ->
                getOnBackPressedDispatcher().onBackPressed());
    }

    /** Fills the drop-down with the five event types (coloured dot + name + description). */
    private void setupTypeDropdown() {
        binding.actvType.setAdapter(new TypeDropdownAdapter(this));
        binding.actvType.setOnItemClickListener((parent, view, position, id) ->
                selectType(EventType.values()[position]));
    }

    /** Selects a type: shows its name, colours the dot in the field and updates the preview. */
    private void selectType(@NonNull EventType type) {
        selectedType = type;
        // "false" = do not filter the list
        binding.actvType.setText(getString(type.getLabelRes()), false);
        binding.tilType.setError(null);
        binding.tilType.setStartIconTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, type.getColorRes())));
        updatePreview();
    }

    /** Tapping the calendar button opens a date picker. */
    private void setupDatePicker() {
        binding.tilDate.setEndIconOnClickListener(v -> showDatePicker());
    }

    private void showDatePicker() {
        // Start from the date already typed, or from today
        Calendar calendar = Calendar.getInstance();
        Long typedDate = DateUtils.parseInputDate(readText(binding.edtDate));
        if (typedDate != null) {
            calendar.setTimeInMillis(typedDate);
        }

        DatePickerDialog dialog = new DatePickerDialog(this,
                (picker, year, month, day) -> {
                    binding.edtDate.setText(DateUtils.formatForInput(year, month, day));
                    binding.edtDate.setSelection(binding.edtDate.length());
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH));

        // Only allow dates in the valid range (1 Jan 2000 - 31 Dec 2100)
        Calendar limit = Calendar.getInstance();
        limit.clear();
        limit.set(DateUtils.MIN_YEAR, Calendar.JANUARY, 1);
        dialog.getDatePicker().setMinDate(limit.getTimeInMillis());
        limit.set(DateUtils.MAX_YEAR, Calendar.DECEMBER, 31);
        dialog.getDatePicker().setMaxDate(limit.getTimeInMillis());

        dialog.show();
    }

    /** Tapping the clock button opens a Material time picker (24-hour). */
    private void setupTimePicker() {
        binding.tilTime.setEndIconOnClickListener(v -> showTimePicker());
    }

    private void showTimePicker() {
        // Start from the time already typed, or from 09:00
        int hour = DEFAULT_PICKER_HOUR;
        int minute = 0;
        String typed = readText(binding.edtTime);
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
        attachTimePickerListener(picker);
        picker.show(getSupportFragmentManager(), TAG_TIME_PICKER);
    }

    /** Writes the picked time into the field when the user taps OK. */
    private void attachTimePickerListener(@NonNull MaterialTimePicker picker) {
        picker.addOnPositiveButtonClickListener(v -> {
            binding.edtTime.setText(ValidationUtils.formatTime(picker.getHour(), picker.getMinute()));
            binding.edtTime.setSelection(binding.edtTime.length());
        });
    }

    /** After rotation the picker is recreated by Android, but its listener is lost: add it again. */
    private void reattachTimePicker() {
        MaterialTimePicker picker = (MaterialTimePicker)
                getSupportFragmentManager().findFragmentByTag(TAG_TIME_PICKER);
        if (picker != null) {
            attachTimePickerListener(picker);
        }
    }

    // ----- Feature (f): "use my current location" button in the Location field -----

    private void setupLocationButton() {
        binding.tilLocation.setEndIconOnClickListener(v -> onUseMyLocationClicked());
    }

    /**
     * Checks the permission first. If the user refused once before, Android suggests
     * explaining why the app needs it, so a short dialog is shown before asking again.
     */
    private void onUseMyLocationClicked() {
        if (isLocating) {
            return;
        }
        if (hasLocationPermission()) {
            fetchCurrentLocation();
            return;
        }

        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.location_permission_title)
                    .setMessage(R.string.location_permission_message)
                    .setNegativeButton(R.string.btn_cancel, null)
                    .setPositiveButton(R.string.btn_continue,
                            (dialog, which) -> locationPermissionLauncher.launch(permissions))
                    .show();
        } else {
            locationPermissionLauncher.launch(permissions);
        }
    }

    /** Precise (fine) OR approximate (coarse) location is enough. */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Gets the position + address and writes it into the Location field. */
    @SuppressLint("MissingPermission") // checked in onUseMyLocationClicked()
    private void fetchCurrentLocation() {
        isLocating = true;
        binding.tilLocation.setHelperText(getString(R.string.location_finding));

        LocationHelper.fetchCurrentAddress(this, new LocationHelper.Callback() {
            @Override
            public void onAddress(@NonNull String address) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                isLocating = false;
                binding.tilLocation.setHelperText(getString(R.string.helper_location));
                binding.edtLocation.setText(address);
                binding.edtLocation.setSelection(binding.edtLocation.length());
                Toast.makeText(EventFormActivity.this, R.string.location_filled,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                isLocating = false;
                binding.tilLocation.setHelperText(getString(R.string.helper_location));
                Toast.makeText(EventFormActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * While the user types: remove the error message of that field
     * and refresh the preview card.
     */
    private void setupFieldListeners() {
        watchField(binding.edtName, binding.tilName);
        watchField(binding.edtDate, binding.tilDate);
        watchField(binding.edtTime, binding.tilTime);
        watchField(binding.edtLocation, binding.tilLocation);
        watchField(binding.edtOrganiser, binding.tilOrganiser);
        watchField(binding.edtAttendees, binding.tilAttendees);
        watchField(binding.edtDescription, binding.tilDescription);
        watchField(binding.edtEmail, binding.tilEmail);
        watchField(binding.edtPhone, binding.tilPhone);
    }

    private void watchField(TextInputEditText field, TextInputLayout layout) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
                updatePreview();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    /** Asks for confirmation before leaving the screen if there are unsaved changes. */
    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (hasUnsavedChanges()) {
                    showDiscardDialog();
                } else {
                    finish();
                }
            }
        });
    }

    // =====================================================================
    // Live preview card
    // =====================================================================

    /** Shows the current name, date/time, type colour and status colour in the preview card. */
    private void updatePreview() {
        String name = readText(binding.edtName);
        binding.tvPreviewName.setText(name.isEmpty() ? getString(R.string.preview_untitled) : name);

        Long dateMillis = DateUtils.parseInputDate(readText(binding.edtDate));
        String time = readText(binding.edtTime);
        if (dateMillis == null) {
            binding.tvPreviewDate.setText(R.string.preview_no_date);
        } else if (ValidationUtils.isValidTime(time)) {
            binding.tvPreviewDate.setText(DateUtils.formatForDisplay(dateMillis) + " · " + time);
        } else {
            binding.tvPreviewDate.setText(DateUtils.formatForDisplay(dateMillis));
        }

        int typeColour = ContextCompat.getColor(this,
                selectedType != null ? selectedType.getColorRes() : R.color.brand_primary);
        ViewCompat.setBackgroundTintList(binding.ivPreviewIcon,
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(typeColour, PREVIEW_TINT_ALPHA)));
        ImageViewCompat.setImageTintList(binding.ivPreviewIcon, ColorStateList.valueOf(typeColour));

        int statusColour = ContextCompat.getColor(this, getSelectedStatus().getColorRes());
        ViewCompat.setBackgroundTintList(binding.viewPreviewStatusDot,
                ColorStateList.valueOf(statusColour));
    }

    // =====================================================================
    // Event status (radio buttons)
    // =====================================================================

    @NonNull
    private EventStatus getSelectedStatus() {
        int checkedId = binding.rgStatus.getCheckedRadioButtonId();
        if (checkedId == binding.rbCompleted.getId()) {
            return EventStatus.COMPLETED;
        } else if (checkedId == binding.rbCancelled.getId()) {
            return EventStatus.CANCELLED;
        }
        return EventStatus.UPCOMING;
    }

    private void setSelectedStatus(@NonNull EventStatus status) {
        switch (status) {
            case COMPLETED:
                binding.rgStatus.check(binding.rbCompleted.getId());
                break;
            case CANCELLED:
                binding.rgStatus.check(binding.rbCancelled.getId());
                break;
            default:
                binding.rgStatus.check(binding.rbUpcoming.getId());
                break;
        }
    }

    // =====================================================================
    // STEP 1 + 2: validate and check for duplicates
    // =====================================================================

    /**
     * Called by the Save / Update button. Does NOT save yet: it validates the form,
     * checks for a duplicate, then opens the confirmation dialog.
     */
    private void onSaveClicked() {
        Event event = validateForm();
        if (event == null) {
            Toast.makeText(this, R.string.toast_fix_errors, Toast.LENGTH_SHORT).show();
            return;
        }

        // Feature (a): same name AND same date as another event -> refuse
        if (repository.checkEventExists(event.getName(), event.getDateMillis(), eventId)) {
            binding.tilName.setError(getString(R.string.error_duplicate_event));
            binding.tilDate.setError(getString(R.string.error_duplicate_event));
            binding.tilName.requestFocus();
            Toast.makeText(this, getString(R.string.toast_duplicate_event, event.getName(),
                    DateUtils.formatForInput(event.getDateMillis())), Toast.LENGTH_LONG).show();
            return;
        }

        showConfirmDialog(event);
    }

    /**
     * Validates EVERY field first, so all errors are shown at the same time.
     *
     * @return the event built from the form, or null if at least one field is invalid
     */
    @Nullable
    private Event validateForm() {
        // Required fields
        String name = validateText(binding.tilName, binding.edtName, R.string.error_name_required);
        Long dateMillis = validateDate();
        String time = validateTime();
        String location = validateText(binding.tilLocation, binding.edtLocation,
                R.string.error_location_required);
        String organiser = validateText(binding.tilOrganiser, binding.edtOrganiser,
                R.string.error_organiser_required);
        Integer attendees = validateAttendees();
        boolean isTypeValid = validateType();

        // Optional fields: only checked when something was typed
        boolean isEmailValid = validateEmail();
        boolean isPhoneValid = validatePhone();

        if (name == null || dateMillis == null || time == null || location == null
                || organiser == null || attendees == null || !isTypeValid
                || !isEmailValid || !isPhoneValid) {
            return null;
        }

        String phone = readText(binding.edtPhone);
        return new Event(eventId, name, dateMillis, time, location, organiser,
                selectedType, getSelectedStatus(), attendees,
                readText(binding.edtDescription),
                readText(binding.edtEmail),
                phone.isEmpty() ? null : ValidationUtils.normalisePhone(phone));
    }

    /**
     * Checks that a required text field is filled in and has a sensible length.
     *
     * @param emptyMessage error shown when the field is empty, e.g. "Please enter the event name"
     * @return the trimmed text, or null if it is invalid
     */
    @Nullable
    private String validateText(TextInputLayout layout, TextInputEditText field,
                                @StringRes int emptyMessage) {
        String text = readText(field);

        if (text.isEmpty()) {
            layout.setError(getString(emptyMessage));
            return null;
        }
        if (text.length() < MIN_TEXT_LENGTH) {
            layout.setError(getString(R.string.error_too_short));
            return null;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            layout.setError(getString(R.string.error_too_long));
            return null;
        }

        layout.setError(null);
        return text;
    }

    /**
     * Checks that the date is a real date in DD/MM/YYYY format with a year in range.
     *
     * @return the date in milliseconds, or null if it is invalid
     */
    @Nullable
    private Long validateDate() {
        String text = readText(binding.edtDate);

        if (text.isEmpty()) {
            binding.tilDate.setError(getString(R.string.error_date_required));
            return null;
        }

        Long dateMillis = DateUtils.parseInputDate(text);
        if (dateMillis == null) {
            binding.tilDate.setError(getString(R.string.error_date_format));
            return null;
        }
        if (!DateUtils.isYearInRange(dateMillis)) {
            binding.tilDate.setError(getString(R.string.error_date_range));
            return null;
        }

        binding.tilDate.setError(null);
        return dateMillis;
    }

    /**
     * Checks that the start time is filled in and is a valid HH:mm time.
     *
     * @return the time text, or null if it is invalid
     */
    @Nullable
    private String validateTime() {
        String text = readText(binding.edtTime);

        if (text.isEmpty()) {
            binding.tilTime.setError(getString(R.string.error_time_required));
            return null;
        }
        if (!ValidationUtils.isValidTime(text)) {
            binding.tilTime.setError(getString(R.string.error_time_format));
            return null;
        }

        binding.tilTime.setError(null);
        return text;
    }

    /**
     * Checks that the number of attendees is a valid whole number within range.
     *
     * @return the number, or null if it is invalid
     */
    @Nullable
    private Integer validateAttendees() {
        String text = readText(binding.edtAttendees);

        if (text.isEmpty()) {
            binding.tilAttendees.setError(getString(R.string.error_attendees_required));
            return null;
        }

        int attendees;
        try {
            attendees = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            binding.tilAttendees.setError(getString(R.string.error_attendees_not_number));
            return null;
        }

        if (attendees < MIN_ATTENDEES || attendees > MAX_ATTENDEES) {
            binding.tilAttendees.setError(getString(R.string.error_attendees_range));
            return null;
        }

        binding.tilAttendees.setError(null);
        return attendees;
    }

    /** Checks that an event type has been selected. */
    private boolean validateType() {
        if (selectedType == null) {
            binding.tilType.setError(getString(R.string.error_type_required));
            return false;
        }
        binding.tilType.setError(null);
        return true;
    }

    /** Optional: empty is fine; otherwise it must be a valid email address. */
    private boolean validateEmail() {
        String text = readText(binding.edtEmail);
        if (!text.isEmpty() && !ValidationUtils.isValidEmail(text)) {
            binding.tilEmail.setError(getString(R.string.error_email_format));
            return false;
        }
        binding.tilEmail.setError(null);
        return true;
    }

    /** Optional: empty is fine; otherwise it must be a valid Vietnamese mobile number. */
    private boolean validatePhone() {
        String text = readText(binding.edtPhone);
        if (!text.isEmpty() && !ValidationUtils.isValidPhone(text)) {
            binding.tilPhone.setError(getString(R.string.error_phone_format));
            return false;
        }
        binding.tilPhone.setError(null);
        return true;
    }

    // =====================================================================
    // STEP 3: confirmation dialog
    // =====================================================================

    /**
     * Shows all entered details back to the user.
     * "Edit" just closes the dialog - the form keeps everything that was typed.
     * "Confirm & save" writes the event to the database.
     */
    private void showConfirmDialog(@NonNull Event event) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_event, null);
        LinearLayout rows = content.findViewById(R.id.layoutConfirmRows);

        addConfirmRow(rows, R.string.hint_event_name, event.getName());
        addConfirmRow(rows, R.string.hint_event_date, DateUtils.formatForDisplay(event.getDateMillis())
                + " (" + DateUtils.formatForInput(event.getDateMillis()) + ")");
        addConfirmRow(rows, R.string.hint_event_time, event.getTime());
        addConfirmRow(rows, R.string.hint_event_location, event.getLocation());
        addConfirmRow(rows, R.string.hint_event_organiser, event.getOrganiser());
        addConfirmRow(rows, R.string.hint_attendees, String.valueOf(event.getAttendees()));
        addConfirmRow(rows, R.string.label_type, getString(event.getType().getLabelRes()));
        addConfirmRow(rows, R.string.label_status, getString(event.getStatus().getLabelRes()));
        addConfirmRow(rows, R.string.hint_event_description, event.getDescription());
        addConfirmRow(rows, R.string.hint_contact_email, event.getContactEmail());
        addConfirmRow(rows, R.string.hint_contact_phone, event.getContactPhone());

        new MaterialAlertDialogBuilder(this)
                .setTitle(isEditMode() ? R.string.confirm_title_edit : R.string.confirm_title_add)
                .setView(content)
                .setNegativeButton(R.string.btn_edit, null) // back to the form, nothing lost
                .setPositiveButton(R.string.btn_confirm_save, (dialog, which) -> persistEvent(event))
                .show();
    }

    /** Adds one "label / value" row; empty optional values are shown as "Not provided". */
    private void addConfirmRow(@NonNull LinearLayout parent, @StringRes int label,
                               @Nullable String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_confirm_row, parent, false);
        TextView tvLabel = row.findViewById(R.id.tvConfirmLabel);
        TextView tvValue = row.findViewById(R.id.tvConfirmValue);

        // Remove "(optional)" from the hint text when it is used as a label
        tvLabel.setText(getString(label).replace(" (optional)", ""));
        if (TextUtils.isEmpty(value)) {
            tvValue.setText(R.string.confirm_not_provided);
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.outline));
        } else {
            tvValue.setText(value);
        }
        parent.addView(row);
    }

    // =====================================================================
    // STEP 4: save, update and delete
    // =====================================================================

    /** Inserts or updates the confirmed event, then returns to the list. */
    private void persistEvent(@NonNull Event event) {
        boolean success;
        if (isEditMode()) {
            success = repository.updateEvent(event);
        } else {
            success = repository.addEvent(event) != null;
        }

        if (!success) {
            Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
            return;
        }

        int messageRes = isEditMode() ? R.string.toast_event_updated : R.string.toast_event_added;
        Toast.makeText(this, getString(messageRes, event.getName()), Toast.LENGTH_SHORT).show();
        finish(); // Back to the list, which reloads from the database in onResume()
    }

    /** Asks the user to confirm before deleting the event. */
    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(getString(R.string.dialog_delete_message, readText(binding.edtName)))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> deleteEvent())
                .show();
    }

    private void deleteEvent() {
        String name = readText(binding.edtName);
        if (repository.deleteEvent(eventId)) {
            Toast.makeText(this, getString(R.string.snackbar_event_deleted, name),
                    Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Returns the trimmed text of a field (never null). */
    @NonNull
    private String readText(TextInputEditText field) {
        Editable text = field.getText();
        return (text == null) ? "" : text.toString().trim();
    }

    /** Builds one string describing everything currently entered in the form. */
    @NonNull
    private String currentFormState() {
        return TextUtils.join("|", new Object[]{
                readText(binding.edtName),
                readText(binding.edtDate),
                readText(binding.edtTime),
                readText(binding.edtLocation),
                readText(binding.edtOrganiser),
                readText(binding.edtAttendees),
                readText(binding.edtDescription),
                readText(binding.edtEmail),
                readText(binding.edtPhone),
                (selectedType == null) ? "" : selectedType.name(),
                getSelectedStatus().name()
        });
    }

    /** True if anything was changed since the form was opened. */
    private boolean hasUnsavedChanges() {
        return !currentFormState().equals(initialFormState);
    }

    private void showDiscardDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_discard_title)
                .setMessage(R.string.dialog_discard_message)
                .setNegativeButton(R.string.btn_keep_editing, null)
                .setPositiveButton(R.string.btn_discard, (dialog, which) -> finish())
                .show();
    }
}

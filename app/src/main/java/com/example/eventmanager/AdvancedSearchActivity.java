package com.example.eventmanager;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.eventmanager.data.EventRepository;
import com.example.eventmanager.databinding.ActivityAdvancedSearchBinding;
import com.example.eventmanager.model.Event;
import com.example.eventmanager.util.DateUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Calendar;
import java.util.List;

/**
 * Advanced search - coursework feature (d).
 * <p>
 * The user can search all events by <b>date</b> (exact day, DD/MM/YYYY) and/or by
 * <b>organiser</b> (part of the name is enough). When both are filled, an event must
 * match BOTH. Tapping a result opens its full details and sessions.
 * <p>
 * The SQL lives in {@link EventRepository#advancedSearch(Long, String)}; values are
 * passed as "?" arguments, so the user's text can never change the query.
 */
public class AdvancedSearchActivity extends AppCompatActivity {

    /** Remembers across rotation whether a search was already run (to show its results again). */
    private static final String KEY_HAS_SEARCHED = "key_has_searched";

    private ActivityAdvancedSearchBinding binding;
    private EventRepository repository;
    private EventAdapter adapter;

    /** True once the user has run a search; the results are then refreshed in onResume(). */
    private boolean hasSearched = false;

    /** Returns an Intent that opens this screen. */
    @NonNull
    public static Intent newIntent(@NonNull Context context) {
        return new Intent(context, AdvancedSearchActivity.class);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityAdvancedSearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = EventRepository.getInstance(this);
        if (savedInstanceState != null) {
            hasSearched = savedInstanceState.getBoolean(KEY_HAS_SEARCHED, false);
        }

        applyWindowInsets();
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // Same card as the main list; tapping a result opens its full details
        adapter = new EventAdapter(event ->
                startActivity(EventDetailActivity.newIntent(this, event.getId())));
        binding.recyclerResults.setAdapter(adapter);

        binding.tilSearchDate.setEndIconOnClickListener(v -> showDatePicker());
        clearErrorWhileTyping(binding.edtSearchDate, binding.tilSearchDate);
        clearErrorWhileTyping(binding.edtSearchOrganiser, binding.tilSearchOrganiser);

        // "Search" key on the keyboard works like the Search button
        binding.edtSearchOrganiser.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(true);
                return true;
            }
            return false;
        });

        binding.btnRunSearch.setOnClickListener(v -> runSearch(true));
        binding.btnClearSearch.setOnClickListener(v -> clearSearch());
    }

    /**
     * Runs the last search again when the user comes back (e.g. after editing or deleting
     * an event from the details screen), so the results are never out of date.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (hasSearched) {
            runSearch(false);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_HAS_SEARCHED, hasSearched);
    }

    /** Keeps the content clear of the system bars and the keyboard. */
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    // =====================================================================
    // Search
    // =====================================================================

    /**
     * Validates the criteria and shows the matching events.
     *
     * @param showErrors true when the user pressed Search (show messages and hide the
     *                   keyboard); false when the search is silently repeated in onResume()
     */
    private void runSearch(boolean showErrors) {
        String dateText = readText(binding.edtSearchDate);
        String organiser = readText(binding.edtSearchOrganiser);

        // At least one criterion is needed
        if (dateText.isEmpty() && organiser.isEmpty()) {
            if (showErrors) {
                Toast.makeText(this, R.string.error_search_criteria_required,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // The date is optional, but if it is typed it must be a real DD/MM/YYYY date
        Long dateMillis = null;
        if (!dateText.isEmpty()) {
            dateMillis = DateUtils.parseInputDate(dateText);
            if (dateMillis == null) {
                binding.tilSearchDate.setError(getString(R.string.error_date_format));
                return;
            }
        }

        List<Event> results = repository.advancedSearch(dateMillis, organiser);
        hasSearched = true;
        showResults(results);

        if (showErrors) {
            hideKeyboard();
        }
    }

    private void showResults(@NonNull List<Event> results) {
        adapter.submitList(results);

        binding.tvResultsHeader.setVisibility(View.VISIBLE);
        binding.tvResultsHeader.setText(getResources().getQuantityString(
                R.plurals.search_result_count, results.size(), results.size()));

        boolean isEmpty = results.isEmpty();
        binding.tvNoResults.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerResults.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /** Empties both criteria and the result list. */
    private void clearSearch() {
        binding.edtSearchDate.setText(null);
        binding.edtSearchOrganiser.setText(null);
        binding.tilSearchDate.setError(null);
        hasSearched = false;

        adapter.submitList(null);
        binding.tvResultsHeader.setVisibility(View.GONE);
        binding.tvNoResults.setVisibility(View.GONE);
        binding.recyclerResults.setVisibility(View.VISIBLE);
    }

    // =====================================================================
    // Date picker (same rules as the event form)
    // =====================================================================

    private void showDatePicker() {
        // Start from the date already typed, or from today
        Calendar calendar = Calendar.getInstance();
        Long typedDate = DateUtils.parseInputDate(readText(binding.edtSearchDate));
        if (typedDate != null) {
            calendar.setTimeInMillis(typedDate);
        }

        new DatePickerDialog(this,
                (picker, year, month, day) ->
                        binding.edtSearchDate.setText(DateUtils.formatForInput(year, month, day)),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH))
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

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focused = getCurrentFocus();
        if (imm != null && focused != null) {
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
    }

    /** Removes the error message of a field as soon as the user types in it. */
    private static void clearErrorWhileTyping(@NonNull TextInputEditText field,
                                              @Nullable TextInputLayout layout) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (layout != null) {
                    layout.setError(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }
}

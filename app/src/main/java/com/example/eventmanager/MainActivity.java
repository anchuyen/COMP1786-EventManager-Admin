package com.example.eventmanager;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
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
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.example.eventmanager.data.EventRepository;
import com.example.eventmanager.data.SessionRepository;
import com.example.eventmanager.databinding.ActivityMainBinding;
import com.example.eventmanager.databinding.DialogSyncProgressBinding;
import com.example.eventmanager.model.Event;
import com.example.eventmanager.model.Session;
import com.example.eventmanager.model.EventStatus;
import com.example.eventmanager.sync.CloudSyncManager;
import com.example.eventmanager.util.NetworkUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

/**
 * Event list screen.
 * <ul>
 *     <li>Loads events from the SQLite database every time the screen is shown.</li>
 *     <li>Search (name, location, organiser) and status filter chips narrow the list.</li>
 *     <li>"+" or "Create event" opens the form to add an event; tapping an event edits it.</li>
 *     <li>Swiping an event left or right deletes it, with an "Undo" option.</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private EventAdapter adapter;
    private EventRepository repository;

    /** Status selected in the filter chips, or null for "All". */
    @Nullable
    private EventStatus statusFilter;

    /** Text typed in the search box ("" means no search). */
    @NonNull
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = EventRepository.getInstance(this);

        applyWindowInsets();
        setupRecyclerView();
        setupSwipeToDelete();
        setupFilterChips();
        setupSearch();

        // Toolbar buttons: Sync (feature e) and Advanced search (feature d)
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_sync) {
                onSyncClicked();
                return true;
            } else if (item.getItemId() == R.id.action_advanced_search) {
                startActivity(AdvancedSearchActivity.newIntent(this));
                return true;
            }
            return false; // let the search item expand normally
        });

        binding.fabAddEvent.setOnClickListener(v -> openAddForm());
        binding.btnCreateEvent.setOnClickListener(v -> openAddForm());
    }

    /** Reloads the list every time the screen becomes visible (after add, edit or delete). */
    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    // =====================================================================
    // Setup
    // =====================================================================

    /** Keeps the content clear of the status bar and navigation bar. */
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void setupRecyclerView() {
        // Tapping an event opens its details and sessions (features b and c)
        adapter = new EventAdapter(event ->
                startActivity(EventDetailActivity.newIntent(this, event.getId())));
        binding.recyclerEvents.setAdapter(adapter);
    }

    /** Status filter chips: All / Upcoming / Completed / Cancelled. */
    private void setupFilterChips() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            int checkedId = checkedIds.isEmpty() ? View.NO_ID : checkedIds.get(0);

            if (checkedId == binding.chipUpcoming.getId()) {
                statusFilter = EventStatus.UPCOMING;
            } else if (checkedId == binding.chipCompleted.getId()) {
                statusFilter = EventStatus.COMPLETED;
            } else if (checkedId == binding.chipCancelled.getId()) {
                statusFilter = EventStatus.CANCELLED;
            } else {
                statusFilter = null; // "All"
            }
            refreshList();
        });
    }

    /** Search box in the toolbar: the list updates as the user types. */
    private void setupSearch() {
        MenuItem searchItem = binding.toolbar.getMenu().findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null) {
            return;
        }
        searchView.setQueryHint(getString(R.string.search_hint));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus(); // Hide the keyboard
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                searchQuery = (newText == null) ? "" : newText;
                refreshList();
                return true;
            }
        });
    }

    /** Lets the user swipe an event left or right to delete it. */
    private void setupSwipeToDelete() {
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false; // Drag-and-drop is not used
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                deleteWithUndo(adapter.getCurrentList().get(position), position);
            }
        };
        new ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerEvents);
    }

    // =====================================================================
    // Actions
    // =====================================================================

    private void openAddForm() {
        startActivity(EventFormActivity.newAddIntent(this));
    }

    /** Deletes the event from the database and shows a Snackbar that can undo it. */
    private void deleteWithUndo(@NonNull Event event, int position) {
        // Read the sessions BEFORE deleting: ON DELETE CASCADE removes them with the event,
        // and "Undo" must be able to put them back.
        List<Session> deletedSessions =
                SessionRepository.getInstance(this).getSessionsForEvent(event.getId());

        if (!repository.deleteEvent(event.getId())) {
            adapter.notifyItemChanged(position); // Put the swiped row back
            Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
            return;
        }
        refreshList();

        Snackbar.make(binding.main,
                        getString(R.string.snackbar_event_deleted, event.getName()),
                        Snackbar.LENGTH_LONG)
                .setAnchorView(binding.fabAddEvent)
                .setAction(R.string.btn_undo, v -> {
                    if (repository.restoreEvent(event, deletedSessions)) {
                        refreshList();
                    } else {
                        Toast.makeText(this, R.string.error_database, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    /**
     * Loads the events that match the current search and filter from the database,
     * then shows the list or the right empty-state message.
     */
    private void refreshList() {
        List<Event> events = repository.searchEvents(searchQuery, statusFilter);
        int totalCount = repository.getEventCount();
        adapter.submitList(events);

        boolean isEmpty = events.isEmpty();
        boolean nothingSaved = (totalCount == 0);
        binding.layoutEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerEvents.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

        if (isEmpty) {
            // Two different empty states: no events at all, or no events match the search/filter
            binding.tvEmptyTitle.setText(nothingSaved ? R.string.empty_title : R.string.no_results_title);
            binding.tvEmptyMessage.setText(nothingSaved ? R.string.empty_message : R.string.no_results_message);
            binding.btnCreateEvent.setVisibility(nothingSaved ? View.VISIBLE : View.GONE);
        }

        // Subtitle: "5 events" or, when filtered, "2 of 5 events"
        if (events.size() == totalCount) {
            binding.toolbar.setSubtitle(getResources()
                    .getQuantityString(R.plurals.event_count, totalCount, totalCount));
        } else {
            binding.toolbar.setSubtitle(getString(R.string.filtered_count, events.size(), totalCount));
        }
    }

    // =====================================================================
    // Cloud sync - feature (e)
    // =====================================================================

    /** Toolbar "Sync" button: check the network first, then ask what to upload. */
    private void onSyncClicked() {
        if (CloudSyncManager.getInstance(this).isRunning()) {
            Toast.makeText(this, R.string.sync_already_running, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!NetworkUtil.isOnline(this)) {
            Snackbar.make(binding.main, R.string.sync_no_internet, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabAddEvent)
                    .setAction(R.string.btn_retry, v -> onSyncClicked())
                    .show();
            return;
        }

        int pending = repository.getUnsyncedEvents().size();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_dialog_title)
                .setMessage(pending == 0
                        ? getString(R.string.sync_dialog_nothing_pending)
                        : getResources().getQuantityString(R.plurals.sync_dialog_pending, pending, pending))
                .setPositiveButton(R.string.btn_sync_changes, (dialog, which) -> startSync(false))
                .setNeutralButton(R.string.btn_upload_all, (dialog, which) -> startSync(true))
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /**
     * Shows a progress dialog and runs the upload on a background thread.
     * The callbacks arrive on the main thread, so they can update the views.
     */
    private void startSync(boolean uploadAll) {
        DialogSyncProgressBinding progress = DialogSyncProgressBinding.inflate(getLayoutInflater());
        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_progress_title)
                .setView(progress.getRoot())
                .setCancelable(false) // the user must wait for the result
                .show();

        CloudSyncManager.getInstance(this).sync(uploadAll, new CloudSyncManager.SyncCallback() {
            @Override
            public void onProgress(int done, int total) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (total == 0) {
                    progress.tvSyncStatus.setText(R.string.sync_checking_cloud);
                    return;
                }
                progress.progressSync.setMax(total);
                progress.progressSync.setProgressCompat(done, true);
                progress.tvSyncStatus.setText(getString(R.string.sync_uploading, done, total));
            }

            @Override
            public void onSuccess(int uploadedCount, int deletedCount) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                progressDialog.dismiss();
                refreshList();
                Snackbar.make(binding.main,
                                getString(R.string.sync_success, uploadedCount, deletedCount),
                                Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.fabAddEvent)
                        .show();
            }

            @Override
            public void onError(@NonNull String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                progressDialog.dismiss();
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(R.string.sync_error_title)
                        .setMessage(message)
                        .setNegativeButton(R.string.btn_cancel, null)
                        .setPositiveButton(R.string.btn_retry, (dialog, which) -> onSyncClicked())
                        .show();
            }
        });
    }
}

package com.example.eventmanager.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.eventmanager.model.Event;
import com.example.eventmanager.model.EventStatus;
import com.example.eventmanager.model.EventType;
import com.example.eventmanager.model.Session;

import java.util.ArrayList;
import java.util.List;

import static com.example.eventmanager.data.EventDbHelper.COL_ATTENDEES;
import static com.example.eventmanager.data.EventDbHelper.COL_CONTACT_EMAIL;
import static com.example.eventmanager.data.EventDbHelper.COL_CONTACT_PHONE;
import static com.example.eventmanager.data.EventDbHelper.COL_DATE;
import static com.example.eventmanager.data.EventDbHelper.COL_DESCRIPTION;
import static com.example.eventmanager.data.EventDbHelper.COL_ID;
import static com.example.eventmanager.data.EventDbHelper.COL_IS_SYNCED;
import static com.example.eventmanager.data.EventDbHelper.COL_LOCATION;
import static com.example.eventmanager.data.EventDbHelper.COL_NAME;
import static com.example.eventmanager.data.EventDbHelper.COL_ORGANISER;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_EVENT_ID;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_ID;
import static com.example.eventmanager.data.EventDbHelper.COL_STATUS;
import static com.example.eventmanager.data.EventDbHelper.COL_TIME;
import static com.example.eventmanager.data.EventDbHelper.COL_TYPE;
import static com.example.eventmanager.data.EventDbHelper.COL_UPDATED_AT;
import static com.example.eventmanager.data.EventDbHelper.TABLE_EVENTS;
import static com.example.eventmanager.data.EventDbHelper.TABLE_SESSIONS;

/**
 * Single place where the app reads and writes events.
 * <p>
 * The screens only call the methods below and never write SQL themselves (Repository
 * pattern). Every value typed by the user is passed as a "?" argument, so user input
 * can never change the SQL (protection against SQL injection).
 * <p>
 * Coursework additions:
 * <ul>
 *     <li>(a) optional fields + {@link #checkEventExists} to block duplicates;</li>
 *     <li>(c) every list of events comes with its number of sessions (LEFT JOIN + COUNT);</li>
 *     <li>(d) search in the description, and {@link #advancedSearch} by date / organiser;</li>
 *     <li>(e) helpers that tell the sync code which events changed since the last upload.</li>
 * </ul>
 */
public final class EventRepository {

    /** Name of the calculated column that holds the number of sessions. */
    private static final String COL_SESSION_COUNT = "session_count";

    /**
     * Base query: every event plus the number of its sessions.
     * LEFT JOIN keeps events that have no session yet (COUNT then returns 0).
     * "e" and "s" are short aliases for the two tables.
     */
    private static final String SELECT_EVENTS_WITH_COUNT =
            "SELECT e.*, COUNT(s." + COL_SESSION_ID + ") AS " + COL_SESSION_COUNT
                    + " FROM " + TABLE_EVENTS + " e"
                    + " LEFT JOIN " + TABLE_SESSIONS + " s"
                    + " ON s." + COL_SESSION_EVENT_ID + " = e." + COL_ID;

    /** One result row per event (needed because of COUNT). */
    private static final String GROUP_BY_EVENT = " GROUP BY e." + COL_ID;

    /** Events are sorted by date (soonest first), then start time, then name. */
    private static final String ORDER_BY_DATE =
            " ORDER BY e." + COL_DATE + " ASC, e." + COL_TIME + " ASC, e."
                    + COL_NAME + " COLLATE NOCASE ASC";

    private static EventRepository instance;

    private final EventDbHelper dbHelper;

    private EventRepository(@NonNull Context context) {
        dbHelper = EventDbHelper.getInstance(context);
    }

    /** Returns the one shared repository, creating it the first time. */
    @NonNull
    public static synchronized EventRepository getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new EventRepository(context);
        }
        return instance;
    }

    // =====================================================================
    // READ
    // =====================================================================

    /** Returns all saved events with their session count, sorted by date. */
    @NonNull
    public List<Event> getAllEvents() {
        return queryEvents(null, null);
    }

    /** Same as {@link #getAllEvents()}; name used in the coursework plan. */
    @NonNull
    public List<Event> getEventsWithSessionCount() {
        return getAllEvents();
    }

    /** Returns one event (with its session count), or null if no event has this ID. */
    @Nullable
    public Event getEventById(long id) {
        List<Event> result = queryEvents("e." + COL_ID + " = ?",
                new String[]{String.valueOf(id)});
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Quick search - coursework feature (d), used by the search box of the main list.
     * <p>
     * An event matches when:
     * <ul>
     *     <li>its NAME starts with the text ("tech" finds "Tech Summit"), or</li>
     *     <li>a WORD inside its name starts with the text ("sum" finds "Tech Summit"), or</li>
     *     <li>its DESCRIPTION contains the text anywhere.</li>
     * </ul>
     * Events whose name starts with the text are listed first. SQLite's LIKE ignores
     * upper/lower case, and all values are "?" arguments (no SQL injection).
     *
     * @param query  text typed by the user, or null/empty for no search
     * @param status status chosen in the filter chips, or null for all statuses
     */
    @NonNull
    public List<Event> searchEvents(@Nullable String query, @Nullable EventStatus status) {
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        String orderBy = ORDER_BY_DATE;

        if (status != null) {
            where.append("e.").append(COL_STATUS).append(" = ?");
            args.add(status.name());
        }

        if (query != null && !query.trim().isEmpty()) {
            String text = escapeLike(query.trim());
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("(e.").append(COL_NAME).append(" LIKE ? ESCAPE '\\'")
                    .append(" OR e.").append(COL_NAME).append(" LIKE ? ESCAPE '\\'")
                    .append(" OR e.").append(COL_DESCRIPTION).append(" LIKE ? ESCAPE '\\')");
            args.add(text + "%");        // name starts with the text
            args.add("% " + text + "%"); // a word inside the name starts with the text
            args.add("%" + text + "%");  // description contains the text

            // Name-prefix matches first; the "?" in ORDER BY comes after the WHERE values
            orderBy = " ORDER BY CASE WHEN e." + COL_NAME + " LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, "
                    + ORDER_BY_DATE.replace(" ORDER BY ", "");
            args.add(text + "%");
        }

        return queryEvents(where.length() == 0 ? null : where.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]),
                orderBy);
    }

    /**
     * Advanced search (feature d): events on a given date and/or by a given organiser.
     * A null/empty criterion is ignored; with no criterion every event is returned.
     *
     * @param dateMillis exact date (as returned by DateUtils.parseInputDate), or null
     * @param organiser  part of the organiser name, or null
     */
    @NonNull
    public List<Event> advancedSearch(@Nullable Long dateMillis, @Nullable String organiser) {
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();

        if (dateMillis != null) {
            where.append("e.").append(COL_DATE).append(" = ?");
            args.add(String.valueOf(dateMillis));
        }
        if (organiser != null && !organiser.trim().isEmpty()) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("e.").append(COL_ORGANISER).append(" LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(organiser.trim()) + "%");
        }

        return queryEvents(where.length() == 0 ? null : where.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]));
    }

    /** Returns the total number of saved events. */
    public int getEventCount() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        return (int) DatabaseUtils.queryNumEntries(db, TABLE_EVENTS);
    }

    /**
     * Feature (a): true if ANOTHER event already has the same name (ignoring upper/lower
     * case and spaces at both ends) on the same date.
     *
     * @param name           event name typed by the user
     * @param dateMillis     event date (as returned by DateUtils.parseInputDate)
     * @param excludeEventId ID of the event being edited (so it does not clash with
     *                       itself), or Event.NO_ID when adding a new event
     */
    public boolean checkEventExists(@NonNull String name, long dateMillis, long excludeEventId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT 1 FROM " + TABLE_EVENTS
                + " WHERE TRIM(" + COL_NAME + ") = ? COLLATE NOCASE"
                + " AND " + COL_DATE + " = ?"
                + " AND " + COL_ID + " != ?"
                + " LIMIT 1";
        String[] args = {name.trim(), String.valueOf(dateMillis), String.valueOf(excludeEventId)};

        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst();
        }
    }

    // =====================================================================
    // CREATE
    // =====================================================================

    /**
     * Inserts a new event. The database generates the ID.
     *
     * @return the saved event with its new ID, or null if saving failed
     */
    @Nullable
    public Event addEvent(@NonNull Event event) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long newId = db.insert(TABLE_EVENTS, null, toContentValues(event));
        return (newId == -1) ? null : event.withId(newId);
    }

    /**
     * Inserts an event again with its original ID (used by "Undo" after a delete).
     *
     * @return true if the event was restored
     */
    public boolean restoreEvent(@NonNull Event event) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = toContentValues(event);
        values.put(COL_ID, event.getId());
        return db.insert(TABLE_EVENTS, null, values) != -1;
    }

    /**
     * "Undo" after a delete: restores the event AND the sessions that the cascade removed.
     * Runs in one transaction: either everything is restored, or nothing is.
     *
     * @param sessions the sessions read with SessionRepository BEFORE the event was deleted
     * @return true if the event and all its sessions were restored
     */
    public boolean restoreEvent(@NonNull Event event, @NonNull List<Session> sessions) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues eventValues = toContentValues(event);
            eventValues.put(COL_ID, event.getId());
            if (db.insert(TABLE_EVENTS, null, eventValues) == -1) {
                return false;
            }
            for (Session session : sessions) {
                ContentValues sessionValues = SessionRepository.toContentValues(session);
                sessionValues.put(COL_SESSION_ID, session.getId());
                if (db.insert(TABLE_SESSIONS, null, sessionValues) == -1) {
                    return false; // endTransaction() below rolls everything back
                }
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    // =====================================================================
    // UPDATE
    // =====================================================================

    /**
     * Saves the changes made to an existing event.
     *
     * @return true if exactly one row was updated
     */
    public boolean updateEvent(@NonNull Event event) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String whereClause = COL_ID + " = ?";
        String[] whereArgs = {String.valueOf(event.getId())};

        try {
            int rowsUpdated = db.update(TABLE_EVENTS, toContentValues(event), whereClause, whereArgs);
            return rowsUpdated == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    // =====================================================================
    // DELETE
    // =====================================================================

    /**
     * Deletes one event. Its sessions are deleted automatically by SQLite
     * (FOREIGN KEY ... ON DELETE CASCADE) - feature (c).
     *
     * @return true if exactly one row was deleted
     */
    public boolean deleteEvent(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String whereClause = COL_ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};

        try {
            return db.delete(TABLE_EVENTS, whereClause, whereArgs) == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    // =====================================================================
    // CLOUD SYNC HELPERS - feature (e)
    // =====================================================================

    /** Returns the events added or changed since the last successful upload. */
    @NonNull
    public List<Event> getUnsyncedEvents() {
        return queryEvents("e." + COL_IS_SYNCED + " = 0", null);
    }

    /**
     * Marks an event as uploaded. Only clears the flag if the event was not changed
     * again after {@code uploadStartedAt} (e.g. edited while the upload was running).
     */
    public void markEventSynced(long eventId, long uploadStartedAt) {
        ContentValues values = new ContentValues();
        values.put(COL_IS_SYNCED, 1);
        dbHelper.getWritableDatabase().update(TABLE_EVENTS, values,
                COL_ID + " = ? AND " + COL_UPDATED_AT + " <= ?",
                new String[]{String.valueOf(eventId), String.valueOf(uploadStartedAt)});
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Runs the base JOIN query with an optional WHERE clause and turns every row into
     * an Event.
     */
    @NonNull
    private List<Event> queryEvents(@Nullable String where, @Nullable String[] args) {
        return queryEvents(where, args, ORDER_BY_DATE);
    }

    /** Same as above, with a custom ORDER BY clause (used by the quick search). */
    @NonNull
    private List<Event> queryEvents(@Nullable String where, @Nullable String[] args,
                                    @NonNull String orderBy) {
        String sql = SELECT_EVENTS_WITH_COUNT
                + (where == null ? "" : " WHERE " + where)
                + GROUP_BY_EVENT
                + orderBy;

        List<Event> events = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        // try-with-resources closes the cursor automatically, even if an error occurs
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                events.add(fromCursor(cursor));
            }
        }
        return events;
    }

    /** Escapes the LIKE wildcards % and _ so they are searched as normal characters. */
    @NonNull
    static String escapeLike(@NonNull String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Converts an Event into column values (the ID is not included: the database
     * generates it). Every save marks the event as "changed, needs uploading".
     */
    @NonNull
    private ContentValues toContentValues(@NonNull Event event) {
        ContentValues values = new ContentValues();
        values.put(COL_NAME, event.getName());
        values.put(COL_DATE, event.getDateMillis());
        values.put(COL_TIME, event.getTime());
        values.put(COL_LOCATION, event.getLocation());
        values.put(COL_ORGANISER, event.getOrganiser());
        values.put(COL_TYPE, event.getType().name());
        values.put(COL_STATUS, event.getStatus().name());
        values.put(COL_ATTENDEES, event.getAttendees());
        values.put(COL_DESCRIPTION, event.getDescription());
        values.put(COL_CONTACT_EMAIL, event.getContactEmail());
        values.put(COL_CONTACT_PHONE, event.getContactPhone());
        values.put(COL_IS_SYNCED, 0);
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    /** Reads the current row of the cursor and turns it into an Event. */
    @NonNull
    private Event fromCursor(@NonNull Cursor cursor) {
        int countIndex = cursor.getColumnIndex(COL_SESSION_COUNT);
        return new Event(
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TIME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_LOCATION)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_ORGANISER)),
                parseType(cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE))),
                parseStatus(cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS))),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_ATTENDEES)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_DESCRIPTION)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_EMAIL)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_PHONE)),
                countIndex == -1 ? 0 : cursor.getInt(countIndex));
    }

    /** Converts stored text back into an EventType; unknown values fall back to OTHER. */
    @NonNull
    private EventType parseType(@Nullable String value) {
        try {
            return EventType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return EventType.OTHER;
        }
    }

    /** Converts stored text back into an EventStatus; unknown values fall back to UPCOMING. */
    @NonNull
    private EventStatus parseStatus(@Nullable String value) {
        try {
            return EventStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return EventStatus.UPCOMING;
        }
    }
}

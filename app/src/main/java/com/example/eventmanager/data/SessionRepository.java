package com.example.eventmanager.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.eventmanager.model.Session;
import com.example.eventmanager.model.SessionType;

import java.util.ArrayList;
import java.util.List;

import static com.example.eventmanager.data.EventDbHelper.COL_ID;
import static com.example.eventmanager.data.EventDbHelper.COL_IS_SYNCED;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_DESCRIPTION;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_EVENT_ID;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_ID;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_NAME;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_ROOM;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_SPEAKER;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_TIME;
import static com.example.eventmanager.data.EventDbHelper.COL_SESSION_TYPE;
import static com.example.eventmanager.data.EventDbHelper.COL_UPDATED_AT;
import static com.example.eventmanager.data.EventDbHelper.TABLE_EVENTS;
import static com.example.eventmanager.data.EventDbHelper.TABLE_SESSIONS;

/**
 * Single place where the app reads and writes sessions - coursework features (b), (c), (d).
 * <p>
 * A session always belongs to one event. Adding, editing or deleting a session also
 * marks its event as "changed", so the next cloud sync uploads the event again
 * with its up-to-date list of sessions (feature e). Both writes run in one transaction.
 */
public final class SessionRepository {

    /** Sessions are listed in time order ("HH:mm" text sorts correctly), then by name. */
    private static final String ORDER_BY_TIME =
            COL_SESSION_TIME + " ASC, " + COL_SESSION_NAME + " COLLATE NOCASE ASC";

    private static SessionRepository instance;

    private final EventDbHelper dbHelper;

    private SessionRepository(@NonNull Context context) {
        dbHelper = EventDbHelper.getInstance(context);
    }

    /** Returns the one shared repository, creating it the first time. */
    @NonNull
    public static synchronized SessionRepository getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new SessionRepository(context);
        }
        return instance;
    }

    // =====================================================================
    // READ
    // =====================================================================

    /** Returns all sessions of one event, sorted by time. */
    @NonNull
    public List<Session> getSessionsForEvent(long eventId) {
        return querySessions(COL_SESSION_EVENT_ID + " = ?",
                new String[]{String.valueOf(eventId)});
    }

    /** Returns one session, or null if no session has this ID. */
    @Nullable
    public Session getSessionById(long sessionId) {
        List<Session> result = querySessions(COL_SESSION_ID + " = ?",
                new String[]{String.valueOf(sessionId)});
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Feature (d): searches the sessions of ONE event by name or description.
     * An empty query returns all sessions of the event.
     */
    @NonNull
    public List<Session> searchSessions(long eventId, @Nullable String query) {
        if (query == null || query.trim().isEmpty()) {
            return getSessionsForEvent(eventId);
        }
        String pattern = "%" + EventRepository.escapeLike(query.trim()) + "%";
        String where = COL_SESSION_EVENT_ID + " = ? AND ("
                + COL_SESSION_NAME + " LIKE ? ESCAPE '\\' OR "
                + COL_SESSION_DESCRIPTION + " LIKE ? ESCAPE '\\')";
        return querySessions(where, new String[]{String.valueOf(eventId), pattern, pattern});
    }

    /** Returns the number of sessions of one event. */
    public int getSessionCount(long eventId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT COUNT(*) FROM " + TABLE_SESSIONS
                + " WHERE " + COL_SESSION_EVENT_ID + " = ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(eventId)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    // =====================================================================
    // CREATE / UPDATE / DELETE
    // =====================================================================

    /**
     * Inserts a new session. The database generates the ID.
     * Fails (returns null) if the event does not exist - the foreign key refuses it.
     *
     * @return the saved session with its new ID, or null if saving failed
     */
    @Nullable
    public Session addSession(@NonNull Session session) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long newId = db.insert(TABLE_SESSIONS, null, toContentValues(session));
            if (newId == -1) {
                return null;
            }
            markEventChanged(db, session.getEventId());
            db.setTransactionSuccessful();
            return session.withId(newId);
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Saves the changes made to an existing session.
     *
     * @return true if exactly one row was updated
     */
    public boolean updateSession(@NonNull Session session) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.update(TABLE_SESSIONS, toContentValues(session),
                    COL_SESSION_ID + " = ?", new String[]{String.valueOf(session.getId())});
            if (rows != 1) {
                return false;
            }
            markEventChanged(db, session.getEventId());
            db.setTransactionSuccessful();
            return true;
        } catch (SQLException e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Deletes one session.
     *
     * @return true if exactly one row was deleted
     */
    public boolean deleteSession(@NonNull Session session) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.delete(TABLE_SESSIONS,
                    COL_SESSION_ID + " = ?", new String[]{String.valueOf(session.getId())});
            if (rows != 1) {
                return false;
            }
            markEventChanged(db, session.getEventId());
            db.setTransactionSuccessful();
            return true;
        } catch (SQLException e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Inserts a session again with its original ID (used by "Undo" after a delete).
     *
     * @return true if the session was restored
     */
    public boolean restoreSession(@NonNull Session session) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = toContentValues(session);
            values.put(COL_SESSION_ID, session.getId());
            if (db.insert(TABLE_SESSIONS, null, values) == -1) {
                return false;
            }
            markEventChanged(db, session.getEventId());
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    @NonNull
    private List<Session> querySessions(@NonNull String where, @NonNull String[] args) {
        List<Session> sessions = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_SESSIONS, null, where, args,
                null, null, ORDER_BY_TIME)) {
            while (cursor.moveToNext()) {
                sessions.add(fromCursor(cursor));
            }
        }
        return sessions;
    }

    /** Marks the parent event as "changed, needs uploading" (feature e). */
    private void markEventChanged(@NonNull SQLiteDatabase db, long eventId) {
        ContentValues values = new ContentValues();
        values.put(COL_IS_SYNCED, 0);
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_EVENTS, values, COL_ID + " = ?", new String[]{String.valueOf(eventId)});
    }

    /**
     * Converts a Session into column values (the ID is not included).
     * Package-private so EventRepository can reuse it when restoring sessions after Undo.
     */
    @NonNull
    static ContentValues toContentValues(@NonNull Session session) {
        ContentValues values = new ContentValues();
        values.put(COL_SESSION_EVENT_ID, session.getEventId());
        values.put(COL_SESSION_NAME, session.getName());
        values.put(COL_SESSION_TIME, session.getTime());
        values.put(COL_SESSION_SPEAKER, session.getSpeaker());
        values.put(COL_SESSION_ROOM, session.getRoom());
        values.put(COL_SESSION_TYPE, session.getType().name());
        values.put(COL_SESSION_DESCRIPTION, session.getDescription());
        return values;
    }

    /** Reads the current row of the cursor and turns it into a Session. */
    @NonNull
    private Session fromCursor(@NonNull Cursor cursor) {
        return new Session(
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_ID)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_EVENT_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_TIME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_SPEAKER)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_ROOM)),
                SessionType.fromName(cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_TYPE))),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_DESCRIPTION)));
    }
}

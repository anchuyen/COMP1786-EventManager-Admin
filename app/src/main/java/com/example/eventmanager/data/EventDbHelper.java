package com.example.eventmanager.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

/**
 * Creates, opens and upgrades the SQLite database of the admin app.
 * <p>
 * Schema (version 3 - coursework):
 * <pre>
 *   events (1) ----&lt; (N) sessions
 *   sessions.event_id  REFERENCES events(_id)  ON DELETE CASCADE
 * </pre>
 * Version 1 was the logbook (events table only). Version 2 adds the optional event
 * fields, two sync columns and the sessions table. Version 3 adds the event start time.
 * {@link #onUpgrade} migrates an older database WITHOUT losing the events already saved.
 */
public class EventDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "events.db";
    /** 1 = logbook, 2 = optional fields + sessions, 3 = event start time. */
    private static final int DATABASE_VERSION = 3;

    // =====================================================================
    // Table "events" (column names as constants avoid typing mistakes in SQL)
    // =====================================================================
    public static final String TABLE_EVENTS = "events";
    public static final String COL_ID = "_id";
    public static final String COL_NAME = "name";
    public static final String COL_DATE = "date_millis";
    /** Start time "HH:mm" - added in version 3. */
    public static final String COL_TIME = "time";
    public static final String COL_LOCATION = "location";
    public static final String COL_ORGANISER = "organiser";
    public static final String COL_TYPE = "type";
    public static final String COL_STATUS = "status";
    public static final String COL_ATTENDEES = "attendees";
    // --- Coursework (a): optional fields ---
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_CONTACT_EMAIL = "contact_email";
    public static final String COL_CONTACT_PHONE = "contact_phone";
    // --- Coursework (e): synchronisation with the cloud ---
    /** 0 = changed locally since the last upload, 1 = same as the cloud copy. */
    public static final String COL_IS_SYNCED = "is_synced";
    /** Time of the last local change, in milliseconds. */
    public static final String COL_UPDATED_AT = "updated_at";

    // =====================================================================
    // Table "sessions" - coursework (b)
    // =====================================================================
    public static final String TABLE_SESSIONS = "sessions";
    public static final String COL_SESSION_ID = "_id";
    public static final String COL_SESSION_EVENT_ID = "event_id";
    public static final String COL_SESSION_NAME = "name";
    public static final String COL_SESSION_TIME = "time";
    public static final String COL_SESSION_SPEAKER = "speaker";
    public static final String COL_SESSION_ROOM = "room";
    public static final String COL_SESSION_TYPE = "type";
    public static final String COL_SESSION_DESCRIPTION = "description";

    /** Time given to events saved before version 3 (they had no time). */
    private static final String DEFAULT_TIME = "09:00";

    // =====================================================================
    // SQL statements
    // =====================================================================

    /** Complete events table (used for a fresh install). */
    private static final String SQL_CREATE_EVENTS_TABLE =
            "CREATE TABLE " + TABLE_EVENTS + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_NAME + " TEXT NOT NULL, "
                    + COL_DATE + " INTEGER NOT NULL, "
                    + COL_TIME + " TEXT NOT NULL DEFAULT '" + DEFAULT_TIME + "', "
                    + COL_LOCATION + " TEXT NOT NULL, "
                    + COL_ORGANISER + " TEXT NOT NULL, "
                    + COL_TYPE + " TEXT NOT NULL, "
                    + COL_STATUS + " TEXT NOT NULL DEFAULT 'UPCOMING', "
                    + COL_ATTENDEES + " INTEGER NOT NULL CHECK (" + COL_ATTENDEES + " > 0), "
                    + COL_DESCRIPTION + " TEXT, "
                    + COL_CONTACT_EMAIL + " TEXT, "
                    + COL_CONTACT_PHONE + " TEXT, "
                    + COL_IS_SYNCED + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0"
                    + ")";

    /**
     * Sessions table. The FOREIGN KEY with ON DELETE CASCADE makes SQLite delete
     * all sessions of an event automatically when that event is deleted.
     */
    private static final String SQL_CREATE_SESSIONS_TABLE =
            "CREATE TABLE " + TABLE_SESSIONS + " ("
                    + COL_SESSION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_SESSION_EVENT_ID + " INTEGER NOT NULL, "
                    + COL_SESSION_NAME + " TEXT NOT NULL, "
                    + COL_SESSION_TIME + " TEXT NOT NULL, "
                    + COL_SESSION_SPEAKER + " TEXT, "
                    + COL_SESSION_ROOM + " TEXT, "
                    + COL_SESSION_TYPE + " TEXT NOT NULL DEFAULT 'OTHERS', "
                    + COL_SESSION_DESCRIPTION + " TEXT, "
                    + "FOREIGN KEY (" + COL_SESSION_EVENT_ID + ") REFERENCES "
                    + TABLE_EVENTS + " (" + COL_ID + ") ON DELETE CASCADE"
                    + ")";

    /** Index on the foreign key: makes the JOIN, the session list and the cascade faster. */
    private static final String SQL_CREATE_SESSIONS_INDEX =
            "CREATE INDEX idx_sessions_event_id ON "
                    + TABLE_SESSIONS + " (" + COL_SESSION_EVENT_ID + ")";

    private static EventDbHelper instance;

    /**
     * Returns the single shared helper.
     * The application context is used so that no Activity is kept in memory (avoids leaks).
     */
    @NonNull
    public static synchronized EventDbHelper getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new EventDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private EventDbHelper(@NonNull Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    /**
     * Called every time the database is opened, before onCreate/onUpgrade.
     * SQLite switches foreign keys OFF by default; without this line ON DELETE CASCADE
     * would silently do nothing. Same effect as "PRAGMA foreign_keys = ON;".
     */
    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    /** Called only once, the first time the database is created (fresh install). */
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_EVENTS_TABLE);
        db.execSQL(SQL_CREATE_SESSIONS_TABLE);
        db.execSQL(SQL_CREATE_SESSIONS_INDEX);
    }

    /**
     * Called when DATABASE_VERSION is increased on a phone that already has the app.
     * Each "if" upgrades one version, so a phone can jump several versions at once.
     * Existing events are kept (ALTER TABLE adds the new columns with NULL/default values).
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN " + COL_DESCRIPTION + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN " + COL_CONTACT_EMAIL + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN " + COL_CONTACT_PHONE + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN " + COL_IS_SYNCED
                    + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN " + COL_UPDATED_AT
                    + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL(SQL_CREATE_SESSIONS_TABLE);
            db.execSQL(SQL_CREATE_SESSIONS_INDEX);
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN " + COL_TIME
                    + " TEXT NOT NULL DEFAULT '" + DEFAULT_TIME + "'");
        }
    }
}

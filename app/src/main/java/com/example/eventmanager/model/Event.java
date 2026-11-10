package com.example.eventmanager.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Represents one event entered by the admin.
 * <p>
 * The object is immutable: all fields are final and set once in the constructor.
 * To edit an event, a new Event object with the same ID is created and saved.
 * <p>
 * Coursework feature (a): a required start time and three optional fields were added to
 * the logbook version: description, contact email and contact phone (null when not entered).
 * <p>
 * Coursework feature (c): {@link #getSessionCount()} holds the number of sessions of the
 * event. It is NOT a table column: the repository calculates it with
 * LEFT JOIN + COUNT every time events are loaded.
 */
public class Event {

    /** ID used for a new event that has not been saved to the database yet. */
    public static final long NO_ID = 0;

    private final long id;
    private final String name;
    /** Event date stored as milliseconds since 1 Jan 1970 (easy to sort and to store). */
    private final long dateMillis;
    /** Start time as "HH:mm" (24-hour), e.g. "09:30". */
    private final String time;
    private final String location;
    private final String organiser;
    private final EventType type;
    /** Exercise 3: Upcoming, Completed or Cancelled. */
    private final EventStatus status;
    /** Exercise 3: expected number of people attending (numeric field). */
    private final int attendees;

    // ===== Coursework (a): optional fields =====
    @Nullable
    private final String description;
    @Nullable
    private final String contactEmail;
    @Nullable
    private final String contactPhone;

    // ===== Coursework (c): calculated value =====
    private final int sessionCount;

    /**
     * Full constructor, used by the repository when it reads a row from the database.
     */
    public Event(long id,
                 @NonNull String name,
                 long dateMillis,
                 @NonNull String time,
                 @NonNull String location,
                 @NonNull String organiser,
                 @NonNull EventType type,
                 @NonNull EventStatus status,
                 int attendees,
                 @Nullable String description,
                 @Nullable String contactEmail,
                 @Nullable String contactPhone,
                 int sessionCount) {
        this.id = id;
        this.name = name;
        this.dateMillis = dateMillis;
        this.time = time;
        this.location = location;
        this.organiser = organiser;
        this.type = type;
        this.status = status;
        this.attendees = attendees;
        this.description = emptyToNull(description);
        this.contactEmail = emptyToNull(contactEmail);
        this.contactPhone = emptyToNull(contactPhone);
        this.sessionCount = sessionCount;
    }

    /**
     * Constructor used by the form: the session count is not known (and not needed) there.
     */
    public Event(long id,
                 @NonNull String name,
                 long dateMillis,
                 @NonNull String time,
                 @NonNull String location,
                 @NonNull String organiser,
                 @NonNull EventType type,
                 @NonNull EventStatus status,
                 int attendees,
                 @Nullable String description,
                 @Nullable String contactEmail,
                 @Nullable String contactPhone) {
        this(id, name, dateMillis, time, location, organiser, type, status, attendees,
                description, contactEmail, contactPhone, 0);
    }

    /**
     * Returns a copy of this event with a different ID.
     * Used after a new event is inserted and the database generates its ID.
     */
    @NonNull
    public Event withId(long newId) {
        return new Event(newId, name, dateMillis, time, location, organiser, type, status, attendees,
                description, contactEmail, contactPhone, sessionCount);
    }

    /** Treats blank optional text as "not entered" so it is stored as NULL. */
    @Nullable
    private static String emptyToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // =====================================================================
    // Getters
    // =====================================================================

    public long getId() {
        return id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public long getDateMillis() {
        return dateMillis;
    }

    @NonNull
    public String getTime() {
        return time;
    }

    @NonNull
    public String getLocation() {
        return location;
    }

    @NonNull
    public String getOrganiser() {
        return organiser;
    }

    @NonNull
    public EventType getType() {
        return type;
    }

    @NonNull
    public EventStatus getStatus() {
        return status;
    }

    public int getAttendees() {
        return attendees;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    @Nullable
    public String getContactEmail() {
        return contactEmail;
    }

    @Nullable
    public String getContactPhone() {
        return contactPhone;
    }

    public int getSessionCount() {
        return sessionCount;
    }
}

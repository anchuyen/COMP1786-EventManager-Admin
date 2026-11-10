package com.example.eventmanager.model;

import androidx.annotation.StringRes;

import com.example.eventmanager.R;

/**
 * The six session types required by the coursework specification (feature b).
 * <p>
 * Like {@link EventType}, the user picks one value from a drop-down, so an invalid
 * type can never be saved. The enum name (e.g. "PANEL_DISCUSSION") is what is stored
 * in the database; the label resource is what the user sees.
 */
public enum SessionType {

    WORKSHOP(R.string.session_type_workshop),
    PRESENTATION(R.string.session_type_presentation),
    PANEL_DISCUSSION(R.string.session_type_panel),
    NETWORKING(R.string.session_type_networking),
    KEYNOTE(R.string.session_type_keynote),
    OTHERS(R.string.session_type_others);

    @StringRes
    private final int labelRes;

    SessionType(@StringRes int labelRes) {
        this.labelRes = labelRes;
    }

    @StringRes
    public int getLabelRes() {
        return labelRes;
    }

    /** Converts text read from the database back into a type; unknown values become OTHERS. */
    public static SessionType fromName(String value) {
        try {
            return SessionType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return OTHERS;
        }
    }
}

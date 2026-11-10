package com.example.eventmanager.model;

import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;

import com.example.eventmanager.R;

/**
 * The five event types required by the specification.
 * <p>
 * Each type stores its display name, a short description (shown in the drop-down)
 * and its colour as resource IDs, so the UI needs no if/else logic.
 */
public enum EventType {

    CONFERENCE(R.string.type_conference, R.string.type_hint_conference, R.color.type_conference),
    WORKSHOP(R.string.type_workshop, R.string.type_hint_workshop, R.color.type_workshop),
    SEMINAR(R.string.type_seminar, R.string.type_hint_seminar, R.color.type_seminar),
    SOCIAL(R.string.type_social, R.string.type_hint_social, R.color.type_social),
    OTHER(R.string.type_other, R.string.type_hint_other, R.color.type_other);

    @StringRes
    private final int labelRes;
    @StringRes
    private final int hintRes;
    @ColorRes
    private final int colorRes;

    EventType(@StringRes int labelRes, @StringRes int hintRes, @ColorRes int colorRes) {
        this.labelRes = labelRes;
        this.hintRes = hintRes;
        this.colorRes = colorRes;
    }

    @StringRes
    public int getLabelRes() {
        return labelRes;
    }

    @StringRes
    public int getHintRes() {
        return hintRes;
    }

    @ColorRes
    public int getColorRes() {
        return colorRes;
    }
}
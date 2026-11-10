package com.example.eventmanager.model;

import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;

import com.example.eventmanager.R;

/**
 * The status of an event (Exercise 3).
 * <p>
 * The user picks one value from this predefined list using radio buttons,
 * so an invalid status can never be entered. Each status has a text colour
 * and a lighter container colour used for its label in the list.
 */
public enum EventStatus {

    UPCOMING(R.string.status_upcoming,
            R.color.status_upcoming, R.color.status_upcoming_container),
    COMPLETED(R.string.status_completed,
            R.color.status_completed, R.color.status_completed_container),
    CANCELLED(R.string.status_cancelled,
            R.color.status_cancelled, R.color.status_cancelled_container);

    @StringRes
    private final int labelRes;
    @ColorRes
    private final int colorRes;
    @ColorRes
    private final int containerColorRes;

    EventStatus(@StringRes int labelRes, @ColorRes int colorRes, @ColorRes int containerColorRes) {
        this.labelRes = labelRes;
        this.colorRes = colorRes;
        this.containerColorRes = containerColorRes;
    }

    @StringRes
    public int getLabelRes() {
        return labelRes;
    }

    @ColorRes
    public int getColorRes() {
        return colorRes;
    }

    @ColorRes
    public int getContainerColorRes() {
        return containerColorRes;
    }
}
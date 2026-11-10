package com.example.eventmanager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.eventmanager.databinding.ItemEventBinding;
import com.example.eventmanager.model.Event;
import com.example.eventmanager.model.EventStatus;
import com.example.eventmanager.util.DateUtils;

/**
 * Displays a list of events in a RecyclerView using Material 3 "tonal" labels.
 * <p>
 * Extends ListAdapter, which uses DiffUtil to work out exactly which rows changed,
 * so only those rows are redrawn. Tapping a row notifies the OnEventClickListener.
 */
public class EventAdapter extends ListAdapter<Event, EventAdapter.EventViewHolder> {

    /** Opacity used for completed events. */
    private static final float COMPLETED_ALPHA = 0.75f;

    /** Transparency (0-255) of the light background behind type labels and avatars. */
    private static final int TINT_ALPHA = 0x29;

    /** Callback used to tell the screen which event was tapped. */
    public interface OnEventClickListener {
        void onEventClick(@NonNull Event event);
    }

    private final OnEventClickListener clickListener;

    public EventAdapter(@NonNull OnEventClickListener clickListener) {
        super(DIFF_CALLBACK);
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemEventBinding binding = ItemEventBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new EventViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
        holder.bind(getItem(position), clickListener);
    }

    /**
     * Returns up to two initials from a name, e.g. "Dr Tuan Nguyen" -> "DT".
     * Returns "?" if the name contains no letters.
     */
    @NonNull
    static String getInitials(@NonNull String name) {
        StringBuilder initials = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
            if (initials.length() == 2) {
                break;
            }
        }
        return (initials.length() == 0) ? "?" : initials.toString();
    }

    // =====================================================================
    // ViewHolder: holds the views of ONE card
    // =====================================================================

    static class EventViewHolder extends RecyclerView.ViewHolder {

        private final ItemEventBinding binding;

        EventViewHolder(@NonNull ItemEventBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Event event, @NonNull OnEventClickListener listener) {
            Context context = binding.getRoot().getContext();
            int typeColour = ContextCompat.getColor(context, event.getType().getColorRes());
            int typeTint = ColorUtils.setAlphaComponent(typeColour, TINT_ALPHA);
            int statusColour = ContextCompat.getColor(context, event.getStatus().getColorRes());
            int statusContainer = ContextCompat.getColor(context, event.getStatus().getContainerColorRes());
            String formattedDate = DateUtils.formatForDisplay(event.getDateMillis())
                    + " · " + event.getTime();
            String attendeesText = context.getResources().getQuantityString(
                    R.plurals.attendee_count, event.getAttendees(), event.getAttendees());

            // Type label: coloured text on a light tint of the same colour
            binding.tvEventType.setText(event.getType().getLabelRes());
            binding.tvEventType.setTextColor(typeColour);
            ViewCompat.setBackgroundTintList(binding.tvEventType, ColorStateList.valueOf(typeTint));

            // Status label: coloured dot + text on its container colour
            binding.tvEventStatus.setText(event.getStatus().getLabelRes());
            binding.tvEventStatus.setTextColor(statusColour);
            ViewCompat.setBackgroundTintList(binding.tvEventStatus, ColorStateList.valueOf(statusContainer));
            TextViewCompat.setCompoundDrawableTintList(binding.tvEventStatus,
                    ColorStateList.valueOf(statusColour));

            binding.tvEventName.setText(event.getName());
            binding.tvEventDate.setText(formattedDate);
            binding.tvEventLocation.setText(event.getLocation());

            // Organiser initials in a tinted circle, then the full name
            binding.tvOrganiserAvatar.setText(getInitials(event.getOrganiser()));
            binding.tvOrganiserAvatar.setTextColor(typeColour);
            ViewCompat.setBackgroundTintList(binding.tvOrganiserAvatar, ColorStateList.valueOf(typeTint));
            binding.tvEventOrganiser.setText(event.getOrganiser());
            binding.tvEventAttendees.setText(attendeesText);
            // Coursework (c): number of sessions, calculated by the LEFT JOIN + COUNT query
            int sessions = event.getSessionCount();
            binding.tvSessionCount.setText(context.getResources()
                    .getQuantityString(R.plurals.session_count, sessions, sessions));

            applyStatusStyle(context, event.getStatus());

            // Descriptions read aloud by screen readers (TalkBack)
            binding.tvEventDate.setContentDescription(
                    context.getString(R.string.cd_date) + ": " + formattedDate);
            binding.tvEventLocation.setContentDescription(
                    context.getString(R.string.cd_location) + ": " + event.getLocation());
            binding.tvEventOrganiser.setContentDescription(
                    context.getString(R.string.cd_organiser) + ": " + event.getOrganiser());
            binding.tvEventAttendees.setContentDescription(
                    context.getString(R.string.cd_attendees) + ": " + event.getAttendees());

            // Tapping the card opens the event for editing
            binding.getRoot().setOnClickListener(v -> listener.onEventClick(event));
        }

        /**
         * Cancelled events: title struck through and greyed out.
         * Completed events: whole card slightly faded.
         * Rows are recycled, so BOTH cases (set and unset) must always be applied.
         */
        private void applyStatusStyle(@NonNull Context context, @NonNull EventStatus status) {
            int flags = binding.tvEventName.getPaintFlags();
            if (status == EventStatus.CANCELLED) {
                binding.tvEventName.setPaintFlags(flags | Paint.STRIKE_THRU_TEXT_FLAG);
                binding.tvEventName.setTextColor(ContextCompat.getColor(context, R.color.outline));
            } else {
                binding.tvEventName.setPaintFlags(flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
                binding.tvEventName.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            }
            binding.getRoot().setAlpha(status == EventStatus.COMPLETED ? COMPLETED_ALPHA : 1f);
        }
    }

    // =====================================================================
    // DiffUtil: tells the adapter how to compare two events
    // =====================================================================

    private static final DiffUtil.ItemCallback<Event> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Event>() {

                @Override
                public boolean areItemsTheSame(@NonNull Event oldItem, @NonNull Event newItem) {
                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull Event oldItem, @NonNull Event newItem) {
                    return oldItem.getName().equals(newItem.getName())
                            && oldItem.getDateMillis() == newItem.getDateMillis()
                            && oldItem.getTime().equals(newItem.getTime())
                            && oldItem.getSessionCount() == newItem.getSessionCount()
                            && oldItem.getLocation().equals(newItem.getLocation())
                            && oldItem.getOrganiser().equals(newItem.getOrganiser())
                            && oldItem.getType() == newItem.getType()
                            && oldItem.getStatus() == newItem.getStatus()
                            && oldItem.getAttendees() == newItem.getAttendees();
                }
            };
}
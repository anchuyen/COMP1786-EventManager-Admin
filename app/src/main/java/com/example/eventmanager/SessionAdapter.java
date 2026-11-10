package com.example.eventmanager;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.eventmanager.databinding.ItemSessionBinding;
import com.example.eventmanager.model.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Displays the sessions of ONE event in a RecyclerView (coursework feature b).
 * <p>
 * Same pattern as {@link EventAdapter}: a ListAdapter with DiffUtil, so only the rows
 * that really changed are redrawn. Tapping a card edits the session; the bin icon
 * deletes it.
 */
public class SessionAdapter extends ListAdapter<Session, SessionAdapter.SessionViewHolder> {

    /** Callbacks used to tell the screen what the user did with a session. */
    public interface OnSessionActionListener {
        void onSessionClick(@NonNull Session session);

        void onSessionDelete(@NonNull Session session);

        /** Feature (f): the QR code button was tapped. */
        void onSessionQr(@NonNull Session session);
    }

    private final OnSessionActionListener listener;

    public SessionAdapter(@NonNull OnSessionActionListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSessionBinding binding = ItemSessionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new SessionViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    // =====================================================================
    // ViewHolder: holds the views of ONE session card
    // =====================================================================

    static class SessionViewHolder extends RecyclerView.ViewHolder {

        private final ItemSessionBinding binding;

        SessionViewHolder(@NonNull ItemSessionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Session session, @NonNull OnSessionActionListener listener) {
            Context context = binding.getRoot().getContext();

            binding.tvSessionTime.setText(session.getTime());
            binding.tvSessionName.setText(session.getName());
            binding.tvSessionType.setText(session.getType().getLabelRes());

            // "Speaker · Room": only the parts that were entered
            List<String> meta = new ArrayList<>();
            if (!TextUtils.isEmpty(session.getSpeaker())) {
                meta.add(session.getSpeaker());
            }
            if (!TextUtils.isEmpty(session.getRoom())) {
                meta.add(session.getRoom());
            }
            setTextOrHide(binding.tvSessionMeta, TextUtils.join(" · ", meta));
            setTextOrHide(binding.tvSessionDescription, session.getDescription());

            // Screen reader (TalkBack) reads the whole card in a sensible order
            binding.getRoot().setContentDescription(session.getTime() + ", " + session.getName()
                    + ", " + context.getString(session.getType().getLabelRes()));

            binding.getRoot().setOnClickListener(v -> listener.onSessionClick(session));
            binding.btnDeleteSession.setOnClickListener(v -> listener.onSessionDelete(session));
            binding.btnSessionQr.setOnClickListener(v -> listener.onSessionQr(session));
        }

        /** Rows are recycled, so a view must be shown AND hidden explicitly every time. */
        private static void setTextOrHide(@NonNull android.widget.TextView view, String text) {
            if (TextUtils.isEmpty(text)) {
                view.setVisibility(View.GONE);
            } else {
                view.setText(text);
                view.setVisibility(View.VISIBLE);
            }
        }
    }

    // =====================================================================
    // DiffUtil: tells the adapter how to compare two sessions
    // =====================================================================

    private static final DiffUtil.ItemCallback<Session> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Session>() {

                @Override
                public boolean areItemsTheSame(@NonNull Session oldItem, @NonNull Session newItem) {
                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull Session oldItem, @NonNull Session newItem) {
                    return oldItem.getName().equals(newItem.getName())
                            && oldItem.getTime().equals(newItem.getTime())
                            && oldItem.getType() == newItem.getType()
                            && Objects.equals(oldItem.getSpeaker(), newItem.getSpeaker())
                            && Objects.equals(oldItem.getRoom(), newItem.getRoom())
                            && Objects.equals(oldItem.getDescription(), newItem.getDescription());
                }
            };
}
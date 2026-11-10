package com.example.eventmanager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.eventmanager.databinding.ItemTypeOptionBinding;
import com.example.eventmanager.model.EventType;

import java.util.Arrays;
import java.util.List;

/**
 * Adapter for the event type drop-down.
 * <p>
 * Each row shows a coloured dot, the type name and a short description.
 * Filtering is disabled so all five types are always listed, even after one
 * has been selected or the screen has been rotated.
 */
public class TypeDropdownAdapter extends ArrayAdapter<EventType> {

    private final LayoutInflater inflater;

    /** A filter that always returns every type and shows the readable label in the field. */
    private final Filter noFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<EventType> allTypes = Arrays.asList(EventType.values());
            FilterResults results = new FilterResults();
            results.values = allTypes;
            results.count = allTypes.size();
            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            notifyDataSetChanged();
        }

        @Override
        public CharSequence convertResultToString(Object resultValue) {
            return getContext().getString(((EventType) resultValue).getLabelRes());
        }
    };

    public TypeDropdownAdapter(@NonNull Context context) {
        super(context, 0, EventType.values());
        inflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        // Reuse the row view when possible, otherwise create a new one
        ItemTypeOptionBinding binding = (convertView == null)
                ? ItemTypeOptionBinding.inflate(inflater, parent, false)
                : ItemTypeOptionBinding.bind(convertView);

        EventType type = EventType.values()[position];
        int colour = ContextCompat.getColor(getContext(), type.getColorRes());

        binding.tvTypeName.setText(type.getLabelRes());
        binding.tvTypeHint.setText(type.getHintRes());
        ViewCompat.setBackgroundTintList(binding.viewTypeDot, ColorStateList.valueOf(colour));
        return binding.getRoot();
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return noFilter;
    }
}
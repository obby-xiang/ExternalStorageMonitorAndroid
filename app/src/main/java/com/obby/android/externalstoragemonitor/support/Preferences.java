package com.obby.android.externalstoragemonitor.support;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.externalstoragemonitor.App;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressLint("ApplySharedPref")
public final class Preferences {
    public static final String KEY_MONITOR_ENABLED = "monitor_enabled";

    private static final String PREF_FILE_NAME = "esm-preferences";

    @NonNull
    private final SharedPreferences mPreferences;

    @NonNull
    private final List<Observer> mObservers = new CopyOnWriteArrayList<>();

    @NonNull
    private final SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener =
        (sharedPreferences, key) -> mObservers.forEach(observer -> observer.onChanged(key));

    private Preferences() {
        mPreferences = App.get().getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public static Preferences get() {
        return InstanceHolder.INSTANCE;
    }

    public void addObserver(@NonNull final Observer observer) {
        if (mObservers.contains(observer)) {
            return;
        }

        if (mObservers.isEmpty()) {
            mPreferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }

        mObservers.add(observer);
    }

    public void removeObserver(@NonNull final Observer observer) {
        mObservers.remove(observer);

        if (mObservers.isEmpty()) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }
    }

    public boolean isMonitorEnabled() {
        return mPreferences.getBoolean(KEY_MONITOR_ENABLED, false);
    }

    public void setMonitorEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_MONITOR_ENABLED, isEnabled).commit();
    }

    @FunctionalInterface
    public interface Observer {
        void onChanged(@Nullable String key);
    }

    private static class InstanceHolder {
        private static final Preferences INSTANCE = new Preferences();
    }
}

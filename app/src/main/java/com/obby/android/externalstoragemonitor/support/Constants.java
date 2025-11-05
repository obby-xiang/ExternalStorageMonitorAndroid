package com.obby.android.externalstoragemonitor.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Constants {
    public static final String EXTERNAL_STORAGE_STATE_UNKNOWN = "unknown";

    public static final String EXTERNAL_STORAGE_STATE_MOUNTED = "mounted";

    public static final String EXTERNAL_STORAGE_STATE_UNMOUNTED = "unmounted";

    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_STOP_SERVICE = "com.obby.android.externalstoragemonitor.ACTION_STOP_SERVICE";

    public static final int MSG_REGISTER_SERVICE_CLIENT = 1;

    public static final int MSG_UNREGISTER_SERVICE_CLIENT = 2;

    public static final int MSG_MONITOR_STARTED = 3;

    public static final int MSG_MONITOR_STOPPED = 4;

    public static final int MSG_EXTERNAL_STORAGE_STATE_CHANGED = 5;
}

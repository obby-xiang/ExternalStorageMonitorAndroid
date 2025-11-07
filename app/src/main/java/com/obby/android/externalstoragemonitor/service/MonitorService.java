package com.obby.android.externalstoragemonitor.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.obby.android.externalstoragemonitor.MainActivity;
import com.obby.android.externalstoragemonitor.R;
import com.obby.android.externalstoragemonitor.support.Constants;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

public class MonitorService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "monitor-service";

    private static final int NOTIFICATION_ID = 1;

    private static final long ALERT_INTERVAL_MS = Duration.ofMinutes(1L).toMillis();

    @Nullable
    private Monitor mMonitor;

    @NonNull
    private String mExternalStorageState = Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;

    @Nullable
    private AlertDialog mExternalStorageUnmountedDialog;

    private final String mTag = "MonitorService@" + hashCode();

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private final List<Messenger> mClientMessengers = new CopyOnWriteArrayList<>();

    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        switch (msg.what) {
            case Constants.MSG_REGISTER_SERVICE_CLIENT:
                registerServiceClient(msg.replyTo);
                return true;
            case Constants.MSG_UNREGISTER_SERVICE_CLIENT:
                unregisterServiceClient(msg.replyTo);
                return true;
            default:
                return false;
        }
    }));

    @NonNull
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_STOP_SERVICE.equals(intent.getAction())) {
                stopService();
            }
        }
    };

    @NonNull
    private final Runnable mShowExternalStorageUnmountedDialogRunnable = this::showExternalStorageUnmountedDialog;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: service created");

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_STOP_SERVICE);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(mTag, "onDestroy: service destroyed");

        unregisterReceiver(mBroadcastReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(mTag, "onBind: service bound");
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(mTag, String.format("onStartCommand: startId = %d", startId));

        if (mMonitor != null) {
            Log.w(mTag, "onStartCommand: monitor already running");
            return START_NOT_STICKY;
        }

        if (intent == null) {
            Log.w(mTag, "onStartCommand: intent is null");
            return START_NOT_STICKY;
        }

        createNotificationChannel();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e) {
            Log.e(mTag, "onStartCommand: start foreground failed", e);
            stopService();
            return START_NOT_STICKY;
        }

        mMonitor = new Monitor(this);
        mMonitor.setExternalStorageStateListener(this::onExternalStorageStateChanged);
        mMonitor.start();

        mClientMessengers.forEach(this::notifyMonitorStarted);

        return START_STICKY;
    }

    private void stopService() {
        Log.i(mTag, "stopService: stop service");

        mExternalStorageState = Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;
        mMainHandler.removeCallbacksAndMessages(null);
        dismissExternalStorageUnmountedDialog();

        if (mMonitor != null) {
            mMonitor.stop();
            mMonitor = null;
        }

        mClientMessengers.forEach(this::notifyMonitorStopped);

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @SuppressLint("MissingPermission")
    private void onExternalStorageStateChanged(@NonNull final String state) {
        mExternalStorageState = state;
        mClientMessengers.forEach(this::notifyExternalStorageStateChanged);
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());

        mMainHandler.removeCallbacks(mShowExternalStorageUnmountedDialogRunnable);

        if (Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED.equals(mExternalStorageState)) {
            showExternalStorageUnmountedDialog();
        } else {
            dismissExternalStorageUnmountedDialog();
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void showExternalStorageUnmountedDialog() {
        if (mExternalStorageUnmountedDialog == null) {
            final Context themedContext = new ContextThemeWrapper(this, R.style.AppTheme);
            mExternalStorageUnmountedDialog = new MaterialAlertDialogBuilder(themedContext)
                .setMessage(R.string.external_storage_unmounted_alert)
                .setPositiveButton(R.string.got_it, null)
                .setCancelable(false)
                .setOnDismissListener(dialog -> {
                    mExternalStorageUnmountedDialog = null;
                    if (Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED.equals(mExternalStorageState)) {
                        mMainHandler.postDelayed(mShowExternalStorageUnmountedDialogRunnable, ALERT_INTERVAL_MS);
                    }
                })
                .create();
            mExternalStorageUnmountedDialog.getWindow().setType(Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_SYSTEM_ALERT : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            mExternalStorageUnmountedDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        if (!mExternalStorageUnmountedDialog.isShowing()) {
            mExternalStorageUnmountedDialog.show();
        }
    }

    private void dismissExternalStorageUnmountedDialog() {
        if (mExternalStorageUnmountedDialog != null) {
            mExternalStorageUnmountedDialog.dismiss();
            mExternalStorageUnmountedDialog = null;
        }
    }

    private void registerServiceClient(@NonNull final Messenger messenger) {
        Log.i(mTag, "registerServiceClient: register service client");

        if (mClientMessengers.contains(messenger)) {
            Log.w(mTag, "registerServiceClient: client already exists");
        } else {
            mClientMessengers.add(messenger);

            if (mMonitor == null) {
                notifyMonitorStopped(messenger);
            } else {
                notifyMonitorStarted(messenger);
                notifyExternalStorageStateChanged(messenger);
            }
        }
    }

    private void unregisterServiceClient(@NonNull final Messenger messenger) {
        Log.i(mTag, "unregisterServiceClient: unregister service client");

        if (!mClientMessengers.remove(messenger)) {
            Log.w(mTag, "unregisterServiceClient: client does not exist");
        }
    }

    private void notifyMonitorStarted(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, Constants.MSG_MONITOR_STARTED));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyMonitorStopped(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, Constants.MSG_MONITOR_STOPPED));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyExternalStorageStateChanged(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, Constants.MSG_EXTERNAL_STORAGE_STATE_CHANGED, mExternalStorageState));
        } catch (RemoteException e) {
            // ignored
        }
    }

    @NonNull
    private Notification buildNotification() {
        final String contentText;
        switch (mExternalStorageState) {
            case Constants.EXTERNAL_STORAGE_STATE_MOUNTED:
                contentText = getString(R.string.external_storage_mounted);
                break;
            case Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED:
                contentText = getString(R.string.external_storage_unmounted);
                break;
            default:
                contentText = null;
                break;
        }

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(contentText)
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void createNotificationChannel() {
        final NotificationChannelCompat notificationChannel =
            new NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.service_notification_channel_name))
                .build();
        NotificationManagerCompat.from(this).createNotificationChannel(notificationChannel);
    }

    @Accessors(prefix = "m")
    private static class Monitor {
        private static final long TICK_INTERVAL_MS = Duration.ofMinutes(1L).toMillis();

        private long mStartTime;

        @Getter
        private boolean mIsRunning;

        @NonNull
        private String mExternalStorageState = Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;

        @Setter
        @Nullable
        private ExternalStorageStateListener mExternalStorageStateListener;

        @NonNull
        private final Context mContext;

        @NonNull
        private final StorageManager mStorageManager;

        @NonNull
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());

        @NonNull
        private final Runnable mTickRunnable = new Runnable() {
            @Override
            public void run() {
                mMainHandler.removeCallbacks(mTickRunnable);
                tick();
                mMainHandler.postDelayed(mTickRunnable,
                    TICK_INTERVAL_MS - (SystemClock.elapsedRealtime() - mStartTime) % TICK_INTERVAL_MS);
            }
        };

        @NonNull
        private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mMainHandler.post(mTickRunnable);
            }
        };

        public Monitor(@NonNull final Context context) {
            mContext = context;
            mStorageManager = mContext.getSystemService(StorageManager.class);
        }

        public void start() {
            if (mIsRunning) {
                return;
            }

            mStartTime = SystemClock.elapsedRealtime();
            mIsRunning = true;

            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_MEDIA_REMOVED);
            intentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            intentFilter.addAction(Intent.ACTION_MEDIA_CHECKING);
            intentFilter.addAction(Intent.ACTION_MEDIA_NOFS);
            intentFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            intentFilter.addAction(Intent.ACTION_MEDIA_SHARED);
            intentFilter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
            intentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
            intentFilter.addDataScheme(ContentResolver.SCHEME_FILE);
            ContextCompat.registerReceiver(mContext, mBroadcastReceiver, intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

            mMainHandler.post(mTickRunnable);
        }

        public void stop() {
            if (!mIsRunning) {
                return;
            }

            mIsRunning = false;
            mContext.unregisterReceiver(mBroadcastReceiver);
            mMainHandler.removeCallbacksAndMessages(null);
        }

        private void tick() {
            final List<String> states = mStorageManager.getStorageVolumes().stream().map(storageVolume -> {
                if (storageVolume.isPrimary()) {
                    return Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED;
                }

                if (storageVolume.isRemovable()) {
                    switch (storageVolume.getState()) {
                        case Environment.MEDIA_CHECKING:
                            return Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;
                        case Environment.MEDIA_MOUNTED:
                            return Constants.EXTERNAL_STORAGE_STATE_MOUNTED;
                        default:
                            return Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED;
                    }
                } else {
                    return Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED;
                }
            }).collect(Collectors.toList());

            final String state;
            if (states.stream().anyMatch(Constants.EXTERNAL_STORAGE_STATE_MOUNTED::equals)) {
                state = Constants.EXTERNAL_STORAGE_STATE_MOUNTED;
            } else if (states.stream().anyMatch(Constants.EXTERNAL_STORAGE_STATE_UNKNOWN::equals)) {
                state = Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;
            } else {
                state = Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED;
            }

            if (Objects.equals(mExternalStorageState, state)) {
                return;
            }

            mExternalStorageState = state;
            if (mExternalStorageStateListener != null) {
                mExternalStorageStateListener.onExternalStorageStateChanged(mExternalStorageState);
            }
        }
    }

    @FunctionalInterface
    private interface ExternalStorageStateListener {
        void onExternalStorageStateChanged(@NonNull String state);
    }
}

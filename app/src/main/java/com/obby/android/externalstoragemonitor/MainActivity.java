package com.obby.android.externalstoragemonitor;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.text.HtmlCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.obby.android.externalstoragemonitor.service.MonitorService;
import com.obby.android.externalstoragemonitor.support.Constants;
import com.obby.android.externalstoragemonitor.support.Preferences;
import com.obby.android.externalstoragemonitor.utils.IntentUtils;

public class MainActivity extends AppCompatActivity {
    private boolean mIsServiceBound;

    @Nullable
    private Messenger mServiceMessenger;

    private boolean mIsMonitorRunning;

    @NonNull
    private String mExternalStorageState = Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;

    @Nullable
    private AlertDialog mDialog;

    private MaterialToolbar mAppToolbar;

    private final String mTag = "MainActivity@" + hashCode();

    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        switch (msg.what) {
            case Constants.MSG_MONITOR_STARTED:
                onMonitorStarted();
                return true;
            case Constants.MSG_MONITOR_STOPPED:
                onMonitorStopped();
                return true;
            case Constants.MSG_EXTERNAL_STORAGE_STATE_CHANGED:
                onExternalStorageStateChanged((String) msg.obj);
                return true;
            default:
                return false;
        }
    }));

    @NonNull
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!mIsServiceBound) {
                return;
            }

            Log.i(mTag, String.format("mServiceConnection.onServiceConnected: service connected, name = %s", name));
            mServiceMessenger = new Messenger(service);
            registerServiceClient();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(mTag, String.format("mServiceConnection.onServiceDisconnected: service disconnected, name = %s",
                name));
            mServiceMessenger = null;
            mIsMonitorRunning = false;
            mExternalStorageState = Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;
            updateMonitorView();
        }
    };

    @NonNull
    private final ActivityResultLauncher<String> mNotificationPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (!isGranted
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                if (mDialog != null) {
                    mDialog.dismiss();
                }
                mDialog = new MaterialAlertDialogBuilder(this)
                    .setMessage(HtmlCompat.fromHtml(getString(R.string.request_post_notification, App.getLabel()),
                        HtmlCompat.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> startActivity(IntentUtils.createAppNotificationSettingsIntent(this)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(false)
                    .show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        mAppToolbar = findViewById(R.id.app_tool_bar);

        final MaterialSwitch enableMonitorSettingView = findViewById(R.id.enable_monitor_setting);
        enableMonitorSettingView.setChecked(Preferences.get().isMonitorEnabled());
        enableMonitorSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isMonitorEnabled() == isChecked) {
                return;
            }

            if (isChecked && requestMonitor()) {
                Preferences.get().setMonitorEnabled(true);
                ContextCompat.startForegroundService(this, new Intent(this, MonitorService.class));
            } else {
                Preferences.get().setMonitorEnabled(false);
                sendBroadcast(new Intent(Constants.ACTION_STOP_SERVICE).setPackage(getPackageName()));
            }

            if (Preferences.get().isMonitorEnabled() != isChecked) {
                enableMonitorSettingView.setChecked(!isChecked);
            }
        });

        updateMonitorView();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(mTag, "onStart: activity started");

        mIsServiceBound = true;
        bindService(new Intent(this, MonitorService.class), mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(mTag, "onStop: activity stopped");

        unregisterServiceClient();
        mIsServiceBound = false;
        mServiceMessenger = null;
        unbindService(mServiceConnection);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private void onMonitorStarted() {
        mIsMonitorRunning = true;
        updateMonitorView();
    }

    private void onMonitorStopped() {
        mIsMonitorRunning = false;
        mExternalStorageState = Constants.EXTERNAL_STORAGE_STATE_UNKNOWN;
        updateMonitorView();
    }

    private void onExternalStorageStateChanged(@NonNull final String state) {
        mExternalStorageState = state;
        updateMonitorView();
    }

    private void updateMonitorView() {
        final int color;
        if (mIsMonitorRunning) {
            switch (mExternalStorageState) {
                case Constants.EXTERNAL_STORAGE_STATE_MOUNTED:
                    mAppToolbar.setSubtitle(R.string.external_storage_mounted);
                    color = MaterialColors.getColor(mAppToolbar, androidx.appcompat.R.attr.colorPrimary);
                    break;
                case Constants.EXTERNAL_STORAGE_STATE_UNMOUNTED:
                    mAppToolbar.setSubtitle(R.string.external_storage_unmounted);
                    color = MaterialColors.getColor(mAppToolbar, androidx.appcompat.R.attr.colorError);
                    break;
                default:
                    mAppToolbar.setSubtitle(null);
                    color = MaterialColors.getColor(mAppToolbar, com.google.android.material.R.attr.colorSecondary);
                    break;
            }
        } else {
            mAppToolbar.setSubtitle(null);
            color = MaterialColors.getColor(mAppToolbar, com.google.android.material.R.attr.colorOutline);
        }

        mAppToolbar.setNavigationIconTint(color);
        mAppToolbar.setSubtitleTextColor(color);
    }

    private boolean requestMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            mNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return false;
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            mDialog = new MaterialAlertDialogBuilder(this)
                .setMessage(HtmlCompat.fromHtml(getString(R.string.request_post_notification, App.getLabel()),
                    HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok,
                    (dialog, which) -> startActivity(IntentUtils.createAppNotificationSettingsIntent(this)))
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(false)
                .show();
            return false;
        }

        if (!Settings.canDrawOverlays(this)) {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            mDialog = new MaterialAlertDialogBuilder(this)
                .setMessage(HtmlCompat.fromHtml(getString(R.string.request_system_alert_window, App.getLabel()),
                    HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok,
                    (dialog, which) -> startActivity(IntentUtils.createManageOverlayPermissionIntent(this)))
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(false)
                .show();
            return false;
        }

        return true;
    }

    private void registerServiceClient() {
        if (mServiceMessenger == null) {
            return;
        }

        try {
            final Message message = Message.obtain(null, Constants.MSG_REGISTER_SERVICE_CLIENT);
            message.replyTo = mMessenger;
            mServiceMessenger.send(message);
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void unregisterServiceClient() {
        if (mServiceMessenger == null) {
            return;
        }

        try {
            final Message message = Message.obtain(null, Constants.MSG_UNREGISTER_SERVICE_CLIENT);
            message.replyTo = mMessenger;
            mServiceMessenger.send(message);
        } catch (RemoteException e) {
            // ignored
        }
    }
}
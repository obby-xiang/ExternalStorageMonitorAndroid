package com.obby.android.externalstoragemonitor;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.obby.android.externalstoragemonitor.support.Preferences;
import com.obby.android.externalstoragemonitor.utils.IntentUtils;

public class MainActivity extends AppCompatActivity {
    @Nullable
    private AlertDialog mDialog;

    private final String mTag = "MainActivity@" + hashCode();

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

        final MaterialSwitch enableMonitorSettingView = findViewById(R.id.enable_monitor_setting);
        enableMonitorSettingView.setChecked(Preferences.get().isMonitorEnabled());
        enableMonitorSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isMonitorEnabled() == isChecked) {
                return;
            }

            if (isChecked) {
                if (requestMonitor()) {
                    Preferences.get().setMonitorEnabled(true);
                } else {
                    enableMonitorSettingView.setChecked(false);
                }
            } else {
                Preferences.get().setMonitorEnabled(false);
            }
        });
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

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(mTag, "onStart: activity started");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(mTag, "onStop: activity stopped");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }
}
package com.ceru.music.mobile;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1001;

    @SuppressWarnings("deprecation")
    @Override
    public void finish() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.app_exit);
            super.finish();
            return;
        }
        super.finish();
        overridePendingTransition(0, R.anim.app_exit);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BackgroundAudioPlugin.class);
        registerPlugin(SecureHttpPlugin.class);
        registerPlugin(SystemBarsPlugin.class);
        registerPlugin(DownloaderPlugin.class);
        registerPlugin(ExternalPlugin.class);
        super.onCreate(savedInstanceState);

        // Downloads stored in a user-picked SAF folder are streamed back to the
        // audio element over loopback HTTP, which the WebView treats as mixed
        // content because the app is served from https://localhost.
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().getSettings()
                    .setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        NOTIFICATION_PERMISSION_REQUEST
                );
            }
        }
    }
}

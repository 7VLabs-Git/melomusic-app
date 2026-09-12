package com.melomusic.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static MainActivity instance;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Switches window immediately from splash drawable to runtime theme
        setTheme(R.style.AppTheme_NoActionBar);
        super.onCreate(savedInstanceState);
        instance = this;

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
    }

    @Override
    public void onStart() {
        super.onStart();

        if (getBridge() != null) {
            WebView webView = getBridge().getWebView();
            if (webView != null) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

                WebSettings settings = webView.getSettings();
                settings.setMediaPlaybackRequiresUserGesture(false);

                webView.addJavascriptInterface(new MeloNativeBridge(this), "MeloNative");
            }
        }
    }

    public static void sendJSEvent(String action) {
        if (instance != null && instance.getBridge() != null) {
            instance.runOnUiThread(() -> {
                WebView webView = instance.getBridge().getWebView();
                if (webView != null) {
                    if ("togglePlayPause".equals(action)) {
                        webView.evaluateJavascript("if (typeof togglePlay === 'function') togglePlay();", null);
                    } else if ("nextTrack".equals(action)) {
                        webView.evaluateJavascript("if (typeof nextTrack === 'function') nextTrack();", null);
                    } else if ("prevTrack".equals(action)) {
                        webView.evaluateJavascript("if (typeof prevTrack === 'function') prevTrack();", null);
                    }
                }
            });
        }
    }

    public static class MeloNativeBridge {
        private final Context context;

        public MeloNativeBridge(Context context) {
            this.context = context.getApplicationContext();
        }

        @JavascriptInterface
        public void startBackgroundPlayback(String title, String artist, String thumbnail, boolean isPlaying) {
            Intent intent = new Intent(context, MeloAudioService.class);
            intent.setAction(MeloAudioService.ACTION_START);
            intent.putExtra("title", title);
            intent.putExtra("artist", artist);
            intent.putExtra("thumbnail", thumbnail);
            intent.putExtra("isPlaying", isPlaying);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }

        @JavascriptInterface
        public void stopBackgroundPlayback() {
            Intent intent = new Intent(context, MeloAudioService.class);
            intent.setAction(MeloAudioService.ACTION_STOP);
            context.startService(intent);
        }
    }
}
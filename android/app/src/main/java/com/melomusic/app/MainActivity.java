package com.melomusic.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BridgeActivity {
    private static MainActivity instance;
    private static final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme_NoActionBar);
        super.onCreate(savedInstanceState);
        instance = this;

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);

        // Delete any leftover update APK so storage remains 100% clean
        cleanOldApkCache();
    }

    private void cleanOldApkCache() {
        try {
            File cacheDir = getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                File apkFile = new File(cacheDir, "melo-update.apk");
                if (apkFile.exists()) {
                    apkFile.delete();
                }
            }
        } catch (Throwable ignored) {}
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
                    if (action.startsWith("seekToPosition:")) {
                        String seconds = action.substring("seekToPosition:".length());
                        webView.evaluateJavascript(
                            "if (typeof window.handleNativeSeek === 'function') { window.handleNativeSeek(" + seconds + "); }", 
                            null
                        );
                    } else if ("togglePlayPause".equals(action)) {
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

        // ==========================================
        // REAL IN-APP OTA DOWNLOADER & INSTALLER
        // ==========================================
        @JavascriptInterface
        public void downloadAppUpdate(String apkUrl) {
            downloadExecutor.execute(() -> {
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream out = null;

                try {
                    File cacheDir = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
                    File apkFile = new File(cacheDir, "melo-update.apk");
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }

                    // Follow GitHub redirects (301/302/307 to AWS S3 storage)
                    String currentUrl = apkUrl;
                    int redirects = 0;
                    while (redirects < 7) {
                        URL url = new URL(currentUrl);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestProperty("User-Agent", "MELO-Music-Android-Updater");
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(30000);
                        conn.setInstanceFollowRedirects(true);

                        int responseCode = conn.getResponseCode();
                        if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                            responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                            responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                            responseCode == 307 || responseCode == 308) {
                            currentUrl = conn.getHeaderField("Location");
                            conn.disconnect();
                            redirects++;
                            continue;
                        }
                        break;
                    }

                    if (conn == null || conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        throw new Exception("HTTP " + (conn != null ? conn.getResponseCode() : "Failed"));
                    }

                    long totalBytes = conn.getContentLengthLong();
                    in = conn.getInputStream();
                    out = new FileOutputStream(apkFile);

                    byte[] buffer = new byte[16 * 1024];
                    long downloadedBytes = 0;
                    int bytesRead;
                    long lastPostTime = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;

                        long now = System.currentTimeMillis();
                        if (now - lastPostTime > 120 || downloadedBytes == totalBytes) {
                            lastPostTime = now;
                            int percent = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
                            double curMB = downloadedBytes / (1024.0 * 1024.0);
                            double totMB = totalBytes > 0 ? totalBytes / (1024.0 * 1024.0) : curMB;

                            if (instance != null && instance.getBridge() != null) {
                                instance.runOnUiThread(() -> {
                                    WebView webView = instance.getBridge().getWebView();
                                    if (webView != null) {
                                        webView.evaluateJavascript(
                                            String.format("if (typeof window.onOtaDownloadProgress === 'function') window.onOtaDownloadProgress(%d, %.1f, %.1f);", percent, curMB, totMB),
                                            null
                                        );
                                    }
                                });
                            }
                        }
                    }
                    out.flush();

                    // Notify web app that download is ready
                    if (instance != null && instance.getBridge() != null) {
                        instance.runOnUiThread(() -> {
                            WebView webView = instance.getBridge().getWebView();
                            if (webView != null) {
                                webView.evaluateJavascript("if (typeof window.onOtaDownloadComplete === 'function') window.onOtaDownloadComplete();", null);
                            }
                        });
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    if (instance != null && instance.getBridge() != null) {
                        instance.runOnUiThread(() -> {
                            WebView webView = instance.getBridge().getWebView();
                            if (webView != null) {
                                webView.evaluateJavascript("if (typeof window.onOtaDownloadError === 'function') window.onOtaDownloadError('" + e.getMessage() + "');", null);
                            }
                        });
                    }
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void installAppUpdate() {
            if (instance == null) return;
            instance.runOnUiThread(() -> {
                try {
                    File cacheDir = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
                    File apkFile = new File(cacheDir, "melo-update.apk");

                    if (!apkFile.exists() || apkFile.length() == 0) {
                        return;
                    }

                    Uri apkUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        apkFile
                    );

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    context.startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        // Screen orientation controller for Cinematic View
        @JavascriptInterface
        public void setScreenOrientation(String mode) {
            if (instance != null) {
                instance.runOnUiThread(() -> {
                    if ("landscape".equalsIgnoreCase(mode)) {
                        instance.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    } else if ("portrait".equalsIgnoreCase(mode)) {
                        instance.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    } else {
                        instance.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    }
                });
            }
        }

        @JavascriptInterface
        public void startBackgroundPlayback(String title, String artist, String thumbnail, boolean isPlaying, double durationMs, double positionMs) {
            try {
                Intent intent = new Intent(context, MeloAudioService.class);
                intent.setAction(MeloAudioService.ACTION_START);
                intent.putExtra("title", title);
                intent.putExtra("artist", artist);
                intent.putExtra("thumbnail", thumbnail);
                intent.putExtra("isPlaying", isPlaying);

                long pos = Double.isNaN(positionMs) || positionMs < 0 ? -1L : (long) positionMs;
                long dur = Double.isNaN(durationMs) || durationMs < 0 ? -1L : (long) durationMs;

                intent.putExtra("duration", dur);
                intent.putExtra("position", pos);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @JavascriptInterface
        public void startBackgroundPlayback(String title, String artist, String thumbnail, boolean isPlaying) {
            startBackgroundPlayback(title, artist, thumbnail, isPlaying, -1.0, -1.0);
        }

        @JavascriptInterface
        public void updatePlaybackProgress(double positionMs, double durationMs, boolean isPlaying) {
            try {
                long pos = Double.isNaN(positionMs) || positionMs < 0 ? 0L : (long) positionMs;
                long dur = Double.isNaN(durationMs) || durationMs < 0 ? 0L : (long) durationMs;

                Intent intent = new Intent(context, MeloAudioService.class);
                intent.setAction(MeloAudioService.ACTION_UPDATE_PROGRESS);
                intent.putExtra("position", pos);
                intent.putExtra("duration", dur);
                intent.putExtra("isPlaying", isPlaying);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @JavascriptInterface
        public void stopBackgroundPlayback() {
            try {
                Intent intent = new Intent(context, MeloAudioService.class);
                intent.setAction(MeloAudioService.ACTION_STOP);
                context.startService(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
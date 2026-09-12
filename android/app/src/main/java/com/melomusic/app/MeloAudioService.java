package com.melomusic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeloAudioService extends Service {
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "ACTION_NEXT";
    public static final String ACTION_PREV = "ACTION_PREV";
    public static final String ACTION_UPDATE_PROGRESS = "ACTION_UPDATE_PROGRESS";

    private static final String CHANNEL_ID = "melo_playback_channel";
    private static final int NOTIFICATION_ID = 1001;

    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();

    private String currentTitle = "MELO Music";
    private String currentArtist = "Playing";
    private String currentThumbnailUrl = "";
    private Bitmap currentArtBitmap = null;
    private boolean isPlaying = true;
    private long currentPositionMs = 0L;
    private long currentDurationMs = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MELO:AudioPlaybackLock");
            wakeLock.setReferenceCounted(false);
        }

        mediaSession = new MediaSessionCompat(this, "MeloMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                isPlaying = true;
                updatePlaybackState();
                publishNotification();
                MainActivity.sendJSEvent("togglePlayPause");
            }

            @Override
            public void onPause() {
                isPlaying = false;
                updatePlaybackState();
                publishNotification();
                MainActivity.sendJSEvent("togglePlayPause");
            }

            @Override
            public void onSkipToNext() {
                MainActivity.sendJSEvent("nextTrack");
            }

            @Override
            public void onSkipToPrevious() {
                MainActivity.sendJSEvent("prevTrack");
            }

            @Override
            public void onSeekTo(long pos) {
                currentPositionMs = pos;
                updatePlaybackState();
                double seconds = pos / 1000.0;
                MainActivity.sendJSEvent("seekToPosition:" + seconds);
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        // Route media button actions coming from lockscreen or notification controls
        MediaButtonReceiver.handleIntent(mediaSession, intent);

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            String title = intent.getStringExtra("title");
            String artist = intent.getStringExtra("artist");
            String thumb = intent.getStringExtra("thumbnail");
            boolean playing = intent.getBooleanExtra("isPlaying", true);
            long duration = intent.getLongExtra("duration", 0L);
            long position = intent.getLongExtra("position", 0L);

            if (title != null && !title.isEmpty()) currentTitle = title;
            if (artist != null && !artist.isEmpty()) currentArtist = artist;
            isPlaying = playing;
            if (duration > 0) currentDurationMs = duration;
            currentPositionMs = position;

            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }

            updatePlaybackState();

            if (thumb != null && !thumb.isEmpty() && !thumb.equals(currentThumbnailUrl)) {
                currentThumbnailUrl = thumb;
                fetchArtworkAndNotify(thumb);
            } else {
                publishNotification();
            }
        } else if (ACTION_UPDATE_PROGRESS.equals(action)) {
            currentPositionMs = intent.getLongExtra("position", currentPositionMs);
            long dur = intent.getLongExtra("duration", 0L);
            if (dur > 0) currentDurationMs = dur;
            isPlaying = intent.getBooleanExtra("isPlaying", isPlaying);
            updatePlaybackState();
        } else if (ACTION_PLAY_PAUSE.equals(action)) {
            if (isPlaying) {
                mediaSession.getController().getTransportControls().pause();
            } else {
                mediaSession.getController().getTransportControls().play();
            }
        } else if (ACTION_NEXT.equals(action)) {
            mediaSession.getController().getTransportControls().skipToNext();
        } else if (ACTION_PREV.equals(action)) {
            mediaSession.getController().getTransportControls().skipToPrevious();
        } else if (ACTION_STOP.equals(action)) {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            isPlaying = false;
            updatePlaybackState();
            stopForeground(true);
            stopSelf();
        }

        return START_STICKY;
    }

    private void updatePlaybackState() {
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO;

        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        float speed = isPlaying ? 1.0f : 0.0f;

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, Math.max(0L, currentPositionMs), speed, SystemClock.elapsedRealtime())
                .build());
    }

    private void fetchArtworkAndNotify(String urlString) {
        imageExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoInput(true);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.connect();
                InputStream input = conn.getInputStream();
                bmp = BitmapFactory.decodeStream(input);
            } catch (Exception ignored) {
            }

            currentArtBitmap = bmp;
            publishNotification();
        });
    }

    private void publishNotification() {
        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs > 0 ? currentDurationMs : -1L);

        if (currentArtBitmap != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtBitmap);
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtBitmap);
        }

        mediaSession.setMetadata(metaBuilder.build());

        Notification notification = buildMediaNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildMediaNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);

        // Use MediaButtonReceiver pending intents to bypass background service launch limitations on Android 12+
        PendingIntent prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        PendingIntent playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE);
        PendingIntent nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT);

        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
                .addAction(playPauseIcon, isPlaying ? "Pause" : "Play", playPauseIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (currentArtBitmap != null) {
            builder.setLargeIcon(currentArtBitmap);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MELO Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows media playback controls and artwork.");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        imageExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
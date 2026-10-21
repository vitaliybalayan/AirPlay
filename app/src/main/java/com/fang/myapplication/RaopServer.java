package com.fang.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.fang.myapplication.model.NALPacket;
import com.fang.myapplication.model.PCMPacket;
import com.fang.myapplication.player.AudioPlayer;
import com.fang.myapplication.player.VideoPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class RaopServer implements SurfaceHolder.Callback {

    static {
        System.loadLibrary("raop_server");
        System.loadLibrary("play-lib");
    }
    private static final String TAG = "RaopServer";
    private static final long VIDEO_STREAM_IDLE_LOG_MS = 5000L;
    private static final long VIDEO_WATCHDOG_INTERVAL_MS = 1000L;
    private static final long VIDEO_PLAYER_RELEASE_TIMEOUT_MS = 1500L;
    private static final long DIRECT_STATE_POLL_MS = 500L;
    // Some CDNs only serve AirPlay clients; present ourselves like an Apple TV media stack.
    private static final String DIRECT_USER_AGENT =
            "AppleCoreMedia/1.0.0.21J354 (Apple TV; U; CPU OS 17_0 like Mac OS X; en_us)";
    // Start adaptive streams at high quality instead of ramping up from the lowest variant.
    private static final long DIRECT_INITIAL_BITRATE_ESTIMATE = 20_000_000L;

    private final Object mVideoLock = new Object();
    private VideoPlayer mVideoPlayer;
    private NALPacket mLastCodecConfig;
    private AudioPlayer mAudioPlayer;
    private ExoPlayer mDirectPlayer;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private long mServerId = 0;
    private boolean mLoggedVideoConfig = false;
    private boolean mLoggedFirstVideoFrame = false;
    private boolean mLoggedFirstAudioPacket = false;
    private volatile long mLastVideoPacketRealtimeMs = 0L;
    private volatile boolean mVideoStreamActive = false;
    private volatile boolean mLoggedVideoIdle = false;
    private volatile boolean mReleased = false;
    // AirPlay sends -30..0 dB for its slider and -144 for mute.
    private static final float AIRPLAY_MIN_VOLUME_DB = -30.0f;
    private static final float AIRPLAY_MUTE_DB = -144.0f;
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private final AudioManager mAudioManager;
    private boolean mVolumeReceiverRegistered = false;

    // Snapshot of the direct player, read by native code from the RTSP thread.
    private volatile boolean mDirectActive = false;
    private volatile boolean mDirectReady = false;
    private volatile double mDirectDuration = 0.0;
    private volatile double mDirectPosition = 0.0;
    private volatile double mDirectRate = 0.0;
    private volatile double mDirectBuffered = 0.0;
    private double mDirectPendingStartFraction = -1.0;

    private final Runnable mVideoWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mReleased) {
                return;
            }
            long lastVideoPacket = mLastVideoPacketRealtimeMs;
            long idleMs = lastVideoPacket > 0 ? SystemClock.elapsedRealtime() - lastVideoPacket : 0L;
            if (mVideoStreamActive && lastVideoPacket > 0 && idleMs > VIDEO_STREAM_IDLE_LOG_MS && !mLoggedVideoIdle) {
                mLoggedVideoIdle = true;
                Log.i(TAG, "Video stream idle: idleMs=" + idleMs + ", preserving current output until native stop");
                if (mLogListener != null) {
                    mLogListener.onLog("[Video] Stream idle, waiting for native stop");
                }
            }
            mMainHandler.postDelayed(this, VIDEO_WATCHDOG_INTERVAL_MS);
        }
    };

    // Keeps the volume we report to iPhones in sync when the TV remote changes it.
    private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            publishTvVolumeToNative();
        }
    };

    private final Runnable mDirectStatePoller = new Runnable() {
        @Override
        public void run() {
            updateDirectSnapshot();
            if (mDirectPlayer != null) {
                mMainHandler.postDelayed(this, DIRECT_STATE_POLL_MS);
            }
        }
    };

    // Listener for forwarding C++ logs to Kotlin UI
    public interface LogListener {
        void onLog(String message);
    }
    private LogListener mLogListener;

    public interface VideoSizeListener {
        void onVideoSizeChanged(int width, int height);
    }
    private VideoSizeListener mVideoSizeListener;

    /** What an AirPlay audio sender (music, video sound via the player button) is playing. */
    public static final class NowPlaying {
        public final boolean active;
        public final String title;
        public final String artist;
        public final String album;
        public final byte[] coverArt;
        public final double durationSec;
        public final double positionSec;
        /** SystemClock.elapsedRealtime() when positionSec was sampled */
        public final long positionAtMs;

        NowPlaying(boolean active, String title, String artist, String album, byte[] coverArt,
                   double durationSec, double positionSec, long positionAtMs) {
            this.active = active;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.coverArt = coverArt;
            this.durationSec = durationSec;
            this.positionSec = positionSec;
            this.positionAtMs = positionAtMs;
        }

        NowPlaying withActive(boolean value) {
            return new NowPlaying(value, title, artist, album, coverArt, durationSec, positionSec, positionAtMs);
        }
    }

    public interface NowPlayingListener {
        void onNowPlayingChanged(NowPlaying nowPlaying);
    }
    private NowPlayingListener mNowPlayingListener;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int COMPRESSION_ALAC = 2;
    private NowPlaying mNowPlaying = new NowPlaying(false, null, null, null, null, 0, 0, 0);
    private volatile long mLastAudioPacketRealtimeMs = 0L;

    /** When the last audio packet arrived; the sender stops streaming while paused. */
    public long getLastAudioPacketRealtimeMs() {
        return mLastAudioPacketRealtimeMs;
    }

    public RaopServer(SurfaceView surfaceView) {
        Log.i(TAG, "constructor: loading RAOP server for surface=" + surfaceView);
        mSurfaceView = surfaceView;
        mSurfaceView.getHolder().addCallback(this);
        mAudioManager = (AudioManager) surfaceView.getContext().getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        mAudioPlayer = new AudioPlayer();
        mAudioPlayer.start();
        Log.i(TAG, "constructor: AudioPlayer started");
        mMainHandler.postDelayed(mVideoWatchdog, VIDEO_WATCHDOG_INTERVAL_MS);
    }

    public void setLogListener(LogListener listener) {
        mLogListener = listener;
    }

    public void setVideoSizeListener(VideoSizeListener listener) {
        mVideoSizeListener = listener;
    }

    public void setNowPlayingListener(NowPlayingListener listener) {
        mNowPlayingListener = listener;
    }

    // Native callbacks below arrive on the RTSP thread.

    public void onAudioSession(boolean active, int compressionType) {
        // Mirroring also opens an AAC-ELD audio stream; only plain AirPlay audio (ALAC) gets the screen.
        boolean show = active && compressionType == COMPRESSION_ALAC;
        Log.i(TAG, "onAudioSession: active=" + active + " ct=" + compressionType);
        if (show) {
            publishNowPlaying(new NowPlaying(true, null, null, null, null, 0, 0, 0));
        } else if (!active) {
            publishNowPlaying(mNowPlaying.withActive(false));
        }
    }

    public void onAudioMetadata(byte[] dmap) {
        String[] fields = new String[3];
        parseDmap(dmap, 0, dmap.length, fields);
        NowPlaying np = mNowPlaying;
        Log.i(TAG, "onAudioMetadata: title=" + fields[0] + " artist=" + fields[1] + " album=" + fields[2]);
        // Progress for the new track sometimes lands just before its metadata; keep that one.
        boolean freshProgress = SystemClock.elapsedRealtime() - np.positionAtMs < 1000;
        boolean newTrack = fields[0] != null && !fields[0].equals(np.title) && !freshProgress;
        // A new track starts from zero; the previous track's progress would race the lyrics ahead.
        publishNowPlaying(new NowPlaying(np.active, fields[0], fields[1], fields[2], np.coverArt,
                newTrack ? 0 : np.durationSec, newTrack ? 0 : np.positionSec, newTrack ? 0 : np.positionAtMs));
    }

    public void onAudioCoverArt(byte[] image) {
        NowPlaying np = mNowPlaying;
        Log.i(TAG, "onAudioCoverArt: bytes=" + image.length);
        publishNowPlaying(new NowPlaying(np.active, np.title, np.artist, np.album,
                image.length > 0 ? image : null, np.durationSec, np.positionSec, np.positionAtMs));
    }

    public void onAudioProgress(long start, long current, long end) {
        // RTP timestamps are unsigned 32-bit and may wrap.
        double duration = ((end - start) & 0xFFFFFFFFL) / (double) AUDIO_SAMPLE_RATE;
        double position = ((current - start) & 0xFFFFFFFFL) / (double) AUDIO_SAMPLE_RATE;
        NowPlaying np = mNowPlaying;
        publishNowPlaying(new NowPlaying(np.active, np.title, np.artist, np.album, np.coverArt,
                duration, Math.min(position, duration), SystemClock.elapsedRealtime()));
    }

    private void publishNowPlaying(NowPlaying nowPlaying) {
        mNowPlaying = nowPlaying;
        if (mNowPlayingListener != null) {
            mNowPlayingListener.onNowPlayingChanged(nowPlaying);
        }
    }

    // DMAP: 4-byte tag, 4-byte big-endian length, value. "mlit" wraps the item fields.
    private static void parseDmap(byte[] data, int offset, int end, String[] out) {
        while (offset + 8 <= end) {
            String tag = new String(data, offset, 4, StandardCharsets.US_ASCII);
            int len = ((data[offset + 4] & 0xFF) << 24) | ((data[offset + 5] & 0xFF) << 16) |
                    ((data[offset + 6] & 0xFF) << 8) | (data[offset + 7] & 0xFF);
            int valueStart = offset + 8;
            if (len < 0 || valueStart + len > end) {
                return;
            }
            if ("mlit".equals(tag)) {
                parseDmap(data, valueStart, valueStart + len, out);
            } else if ("minm".equals(tag)) {
                out[0] = new String(data, valueStart, len, StandardCharsets.UTF_8);
            } else if ("asar".equals(tag)) {
                out[1] = new String(data, valueStart, len, StandardCharsets.UTF_8);
            } else if ("asal".equals(tag)) {
                out[2] = new String(data, valueStart, len, StandardCharsets.UTF_8);
            }
            offset = valueStart + len;
        }
    }

    // Called from C++ JNI - forward native engine logs to UI
    public void onNativeLog(int level, String message) {
        String prefix;
        switch (level) {
            case 7: prefix = "[C++ DBG]"; break;
            case 4: prefix = "[C++ WRN]"; break;
            case 6: prefix = "[C++ INF]"; break;
            case 3: prefix = "[C++ ERR]"; break;
            default: prefix = "[C++ L" + level + "]"; break;
        }
        String fullMsg = prefix + " " + message;
        if (level == 3) {
            Log.e(TAG, fullMsg);
        } else if (level == 4) {
            Log.w(TAG, fullMsg);
        } else {
            Log.i(TAG, fullMsg);
        }
        if (mLogListener != null) {
            mLogListener.onLog(fullMsg);
        }
    }

    public void onRecvVideoData(byte[] nal, int nalType, long dts, long pts) {
        mLastVideoPacketRealtimeMs = SystemClock.elapsedRealtime();
        mVideoStreamActive = true;
        mLoggedVideoIdle = false;
        if (nalType == 0 && !mLoggedVideoConfig) {
            Log.i(TAG, "onRecvVideoData: first SPS/PPS bytes=" + nal.length + " pts=" + pts);
        } else if (nalType != 0 && !mLoggedFirstVideoFrame) {
            Log.i(TAG, "onRecvVideoData: first video frame bytes=" + nal.length + " nalType=" + nalType + " pts=" + pts);
        }
        if (mLogListener != null) {
            if (nalType == 0 && !mLoggedVideoConfig) {
                mLoggedVideoConfig = true;
                mLogListener.onLog("[Video] SPS/PPS received, bytes=" + nal.length);
            } else if (nalType != 0 && !mLoggedFirstVideoFrame) {
                mLoggedFirstVideoFrame = true;
                mLogListener.onLog("[Video] First frame received, bytes=" + nal.length + ", pts=" + pts);
            }
        }
        NALPacket nalPacket = new NALPacket();
        nalPacket.nalData = nal;
        nalPacket.nalType = nalType;
        nalPacket.pts = pts;

        if (mDirectActive) {
            // Mirroring took over the screen: the surface can only feed one producer.
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopDirectPlayer("mirror-started");
                }
            });
        }

        synchronized (mVideoLock) {
            if (nalType == 0) {
                mLastCodecConfig = nalPacket;
            }
            VideoPlayer player = mDirectActive ? null : ensureVideoPlayerLocked();
            if (player == null) {
                return;
            }
            player.addPacker(nalPacket);
        }
    }

    public void onRecvAudioData(short[] pcm, long pts) {
        mLastAudioPacketRealtimeMs = SystemClock.elapsedRealtime();
        if (!mLoggedFirstAudioPacket) {
            mLoggedFirstAudioPacket = true;
            Log.i(TAG, "onRecvAudioData: first audio packet samples=" + pcm.length + " pts=" + pts);
            if (mLogListener != null) {
                mLogListener.onLog("[Audio] First PCM packet samples=" + pcm.length + ", pts=" + pts);
            }
        }
        PCMPacket pcmPacket = new PCMPacket();
        pcmPacket.data = pcm;
        pcmPacket.pts = pts;
        mAudioPlayer.addPacker(pcmPacket);
    }

    // The iPhone volume buttons drive the TV's own media volume, like a TV remote would.
    public void onAudioVolumeChanged(final float volume) {
        final int maxIndex = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final int index = airPlayDbToVolumeIndex(volume, maxIndex);
        Log.i(TAG, "onAudioVolumeChanged: " + volume + " dB -> TV volume " + index + "/" + maxIndex);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI);
                } catch (SecurityException e) {
                    Log.w(TAG, "setStreamVolume denied", e);
                }
            }
        });
        if (mLogListener != null) {
            mLogListener.onLog("[Audio] Volume changed to " + volume + " dB");
        }
    }

    static int airPlayDbToVolumeIndex(float volumeDb, int maxIndex) {
        if (volumeDb <= AIRPLAY_MUTE_DB || maxIndex <= 0) {
            return 0;
        }
        float fraction = (volumeDb - AIRPLAY_MIN_VOLUME_DB) / -AIRPLAY_MIN_VOLUME_DB;
        fraction = Math.max(0.0f, Math.min(1.0f, fraction));
        return Math.round(fraction * maxIndex);
    }

    static float volumeIndexToAirPlayDb(int index, int maxIndex) {
        if (index <= 0 || maxIndex <= 0) {
            return AIRPLAY_MUTE_DB;
        }
        return AIRPLAY_MIN_VOLUME_DB * (1.0f - (float) index / maxIndex);
    }

    private void publishTvVolumeToNative() {
        if (mServerId == 0) {
            return;
        }
        int index = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxIndex = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        setReceiverVolume(mServerId, volumeIndexToAirPlayDb(index, maxIndex));
    }

    public void onVideoStreamStopped(String reason) {
        Log.i(TAG, "onVideoStreamStopped: reason=" + reason);
        if (mLogListener != null) {
            mLogListener.onLog("[Video] Native stream stopped: " + reason);
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                resetVideoOutput(reason != null ? reason : "native-video-stop");
            }
        });
    }

    public void onDirectPlay(final String url, final double startSeconds, final double startFraction) {
        Log.i(TAG, "onDirectPlay: url=" + url + " startSeconds=" + startSeconds + " startFraction=" + startFraction);
        if (mLogListener != null) {
            mLogListener.onLog("[Direct] Play requested");
        }
        // Report "loading" right away so the sender does not treat stale state as finished playback.
        mDirectActive = true;
        mDirectReady = false;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                startDirectPlayer(url, startSeconds, startFraction);
            }
        });
    }

    public void onDirectStop(String reason) {
        Log.i(TAG, "onDirectStop: reason=" + reason);
        if (mLogListener != null) {
            mLogListener.onLog("[Direct] Stop: " + reason);
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                stopDirectPlayer(reason != null ? reason : "direct-stop");
            }
        });
    }

    public void onDirectScrub(final double positionSeconds) {
        Log.i(TAG, "onDirectScrub: " + positionSeconds);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mDirectPlayer != null) {
                    mDirectPlayer.seekTo((long) (Math.max(0.0, positionSeconds) * 1000.0));
                    updateDirectSnapshot();
                }
            }
        });
    }

    public void onDirectRate(final double rate) {
        Log.i(TAG, "onDirectRate: " + rate);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mDirectPlayer != null) {
                    mDirectPlayer.setPlayWhenReady(rate > 0.0);
                    updateDirectSnapshot();
                }
            }
        });
    }

    // Called from native RTSP thread: {active, ready, duration, position, rate, buffered}
    public double[] getDirectPlaybackInfo() {
        return new double[]{
                mDirectActive ? 1.0 : 0.0,
                mDirectReady ? 1.0 : 0.0,
                mDirectDuration,
                mDirectPosition,
                mDirectRate,
                mDirectBuffered
        };
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");
        mSurfaceHolder = holder;
        if (mLogListener != null) {
            mLogListener.onLog("[Surface] surfaceCreated");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: format=" + format + " size=" + width + "x" + height);
        mSurfaceHolder = holder;
        if (mVideoStreamActive && !mDirectActive) {
            synchronized (mVideoLock) {
                ensureVideoPlayerLocked();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        mSurfaceHolder = null;
        stopDirectPlayer("surface-destroyed");
        resetVideoOutput("surface-destroyed");
    }

    public void startServer(String deviceName, String deviceId) {
        if (mServerId == 0) {
            Log.i(TAG, "startServer: calling native start()");
            mServerId = start(deviceName, deviceId);
            publishTvVolumeToNative();
            if (!mVolumeReceiverRegistered) {
                mSurfaceView.getContext().getApplicationContext()
                        .registerReceiver(mVolumeReceiver, new IntentFilter(VOLUME_CHANGED_ACTION));
                mVolumeReceiverRegistered = true;
            }
            Log.i(TAG, "startServer: native serverId=" + mServerId + " port=" + getPort());
            if (mLogListener != null) {
                mLogListener.onLog("[RAOP] startServer() mServerId=" + mServerId);
            }
        } else {
            Log.i(TAG, "startServer: already started serverId=" + mServerId + " port=" + getPort());
        }
    }

    public void stopServer() {
        Log.i(TAG, "stopServer: serverId=" + mServerId);
        mReleased = true;
        mMainHandler.removeCallbacks(mVideoWatchdog);
        if (mVolumeReceiverRegistered) {
            mSurfaceView.getContext().getApplicationContext().unregisterReceiver(mVolumeReceiver);
            mVolumeReceiverRegistered = false;
        }
        if (mServerId != 0) {
            stop(mServerId);
        }
        mServerId = 0;
        resetVideoOutput("server-stop");
        stopDirectPlayer("server-stop");
        mAudioPlayer.stopPlay();
        Log.i(TAG, "stopServer: AudioPlayer stopped");
    }

    private VideoPlayer ensureVideoPlayerLocked() {
        if (mVideoPlayer != null) {
            return mVideoPlayer;
        }
        SurfaceHolder holder = mSurfaceHolder != null ? mSurfaceHolder : mSurfaceView.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            Log.w(TAG, "ensureVideoPlayer: surface is not ready");
            return null;
        }

        Log.i(TAG, "ensureVideoPlayer: creating VideoPlayer");
        mVideoPlayer = new VideoPlayer(holder.getSurface(), new VideoPlayer.LogListener() {
            @Override
            public void onLog(String message) {
                if (mLogListener != null) {
                    mLogListener.onLog(message);
                }
            }
        }, new VideoPlayer.VideoSizeListener() {
            @Override
            public void onVideoSizeChanged(int width, int height) {
                if (mVideoSizeListener != null) {
                    mVideoSizeListener.onVideoSizeChanged(width, height);
                }
            }
        });
        mVideoPlayer.start();
        // SPS/PPS is only sent when the stream starts; replay it so a recreated decoder can start.
        if (mLastCodecConfig != null) {
            mVideoPlayer.addPacker(mLastCodecConfig);
        }
        Log.i(TAG, "ensureVideoPlayer: VideoPlayer started");
        return mVideoPlayer;
    }

    private void resetVideoOutput(String reason) {
        Log.i(TAG, "resetVideoOutput: reason=" + reason);
        mVideoStreamActive = false;
        mLastVideoPacketRealtimeMs = 0L;
        mLoggedVideoIdle = false;
        mLoggedVideoConfig = false;
        mLoggedFirstVideoFrame = false;
        VideoPlayer player;
        synchronized (mVideoLock) {
            player = mVideoPlayer;
            mVideoPlayer = null;
            if (!"surface-destroyed".equals(reason)) {
                mLastCodecConfig = null;
            }
        }
        if (player != null) {
            player.releaseAndWait(VIDEO_PLAYER_RELEASE_TIMEOUT_MS);
            if (mVideoSizeListener != null) {
                mVideoSizeListener.onVideoSizeChanged(0, 0);
            }
        }
    }

    private void startDirectPlayer(String url, double startSeconds, double startFraction) {
        if (url == null || url.trim().isEmpty()) {
            Log.w(TAG, "startDirectPlayer: empty URL");
            mDirectActive = mDirectPlayer != null;
            return;
        }
        SurfaceHolder holder = mSurfaceHolder != null ? mSurfaceHolder : mSurfaceView.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            Log.w(TAG, "startDirectPlayer: surface is not ready");
            mDirectActive = mDirectPlayer != null;
            return;
        }

        stopDirectPlayer("replace-direct");
        resetVideoOutput("direct-play");
        mDirectActive = true;
        mDirectReady = false;

        try {
            Context context = mSurfaceView.getContext().getApplicationContext();
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(DIRECT_USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000);
            DefaultMediaSourceFactory mediaSourceFactory =
                    new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, httpFactory));
            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(DIRECT_INITIAL_BITRATE_ESTIMATE)
                    .build();

            final ExoPlayer player = new ExoPlayer.Builder(context)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setBandwidthMeter(bandwidthMeter)
                    .build();
            mDirectPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(), false);
            player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            player.setVideoSurface(holder.getSurface());
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (mDirectPlayer != player) {
                        return;
                    }
                    Log.i(TAG, "Direct player state=" + state);
                    if (state == Player.STATE_READY) {
                        applyPendingStartFraction(player);
                    } else if (state == Player.STATE_ENDED) {
                        // Keep the session until the sender sends /stop; it sees position == duration.
                        player.setPlayWhenReady(false);
                    }
                    updateDirectSnapshot();
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (mDirectPlayer == player) {
                        updateDirectSnapshot();
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize videoSize) {
                    if (mDirectPlayer != player || videoSize.width <= 0 || videoSize.height <= 0) {
                        return;
                    }
                    int width = (int) (videoSize.width * videoSize.pixelWidthHeightRatio);
                    Log.i(TAG, "Direct video size: " + width + "x" + videoSize.height);
                    if (mVideoSizeListener != null) {
                        mVideoSizeListener.onVideoSizeChanged(width, videoSize.height);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (mDirectPlayer != player) {
                        return;
                    }
                    Log.e(TAG, "Direct player error " + error.getErrorCodeName(), error);
                    if (mLogListener != null) {
                        mLogListener.onLog("[Direct] Playback error " + error.getErrorCodeName() + ": " + error.getMessage());
                    }
                    stopDirectPlayer("error");
                }
            });

            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
            if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
                // HLS URLs from Safari often carry query strings, which defeats extension sniffing.
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            if (startSeconds > 0) {
                player.setMediaItem(item.build(), (long) (startSeconds * 1000.0));
            } else {
                player.setMediaItem(item.build());
                mDirectPendingStartFraction = startFraction > 0 && startFraction < 1 ? startFraction : -1.0;
            }
            player.setPlayWhenReady(true);
            player.prepare();
            mMainHandler.removeCallbacks(mDirectStatePoller);
            mMainHandler.postDelayed(mDirectStatePoller, DIRECT_STATE_POLL_MS);
            Log.i(TAG, "Direct ExoPlayer prepare started");
        } catch (Exception e) {
            Log.e(TAG, "startDirectPlayer failed", e);
            if (mLogListener != null) {
                mLogListener.onLog("[Direct] Failed to start playback: " + e.getMessage());
            }
            stopDirectPlayer("start-failed");
        }
    }

    private void applyPendingStartFraction(ExoPlayer player) {
        if (mDirectPendingStartFraction <= 0) {
            return;
        }
        long durationMs = player.getDuration();
        if (durationMs != C.TIME_UNSET && durationMs > 0) {
            player.seekTo((long) (durationMs * mDirectPendingStartFraction));
        }
        mDirectPendingStartFraction = -1.0;
    }

    private void updateDirectSnapshot() {
        ExoPlayer player = mDirectPlayer;
        if (player == null) {
            return;
        }
        int state = player.getPlaybackState();
        long durationMs = player.getDuration();
        boolean ready = state == Player.STATE_READY || state == Player.STATE_ENDED ||
                (state == Player.STATE_BUFFERING && durationMs != C.TIME_UNSET);
        double duration = durationMs != C.TIME_UNSET ? durationMs / 1000.0 : 0.0;
        double position = state == Player.STATE_ENDED ? duration : player.getCurrentPosition() / 1000.0;
        mDirectDuration = duration;
        mDirectPosition = position;
        mDirectBuffered = player.getBufferedPosition() / 1000.0;
        mDirectRate = player.isPlaying() ? player.getPlaybackParameters().speed : 0.0;
        mDirectReady = ready;
    }

    private void stopDirectPlayer(String reason) {
        mMainHandler.removeCallbacks(mDirectStatePoller);
        mDirectActive = false;
        mDirectReady = false;
        mDirectDuration = 0.0;
        mDirectPosition = 0.0;
        mDirectRate = 0.0;
        mDirectBuffered = 0.0;
        mDirectPendingStartFraction = -1.0;
        if (mDirectPlayer == null) {
            return;
        }
        Log.i(TAG, "stopDirectPlayer: reason=" + reason);
        ExoPlayer player = mDirectPlayer;
        mDirectPlayer = null;
        try {
            player.stop();
            // Detach before release so the mirror decoder can reuse the surface immediately.
            player.clearVideoSurface();
            player.release();
        } catch (Exception e) {
            Log.w(TAG, "Direct player release failed", e);
        }
        if (mVideoSizeListener != null) {
            mVideoSizeListener.onVideoSizeChanged(0, 0);
        }
    }

    public int getPort() {
        if (mServerId != 0) {
            return getPort(mServerId);
        }
        return 0;
    }

    public String getPublicKeyHex() {
        if (mServerId != 0) {
            return getPublicKey(mServerId);
        }
        return "";
    }

    private native long start(String deviceName, String deviceId);
    private native void stop(long serverId);
    private native int getPort(long serverId);
    private native String getPublicKey(long serverId);
    private native void setReceiverVolume(long serverId, float volumeDb);
}

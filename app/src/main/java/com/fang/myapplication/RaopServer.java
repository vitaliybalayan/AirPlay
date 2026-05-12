package com.fang.myapplication;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.fang.myapplication.model.NALPacket;
import com.fang.myapplication.model.PCMPacket;
import com.fang.myapplication.player.AudioPlayer;
import com.fang.myapplication.player.VideoPlayer;

public class RaopServer implements SurfaceHolder.Callback {

    static {
        System.loadLibrary("raop_server");
        System.loadLibrary("play-lib");
    }
    private static final String TAG = "RaopServer";
    private static final long VIDEO_STREAM_IDLE_LOG_MS = 5000L;
    private static final long VIDEO_WATCHDOG_INTERVAL_MS = 1000L;
    private static final long VIDEO_PLAYER_RELEASE_TIMEOUT_MS = 1500L;

    private VideoPlayer mVideoPlayer;
    private AudioPlayer mAudioPlayer;
    private MediaPlayer mDirectPlayer;
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
    
    // Listener for forwarding C++ logs to Kotlin UI
    public interface LogListener {
        void onLog(String message);
    }
    private LogListener mLogListener;

    public interface VideoSizeListener {
        void onVideoSizeChanged(int width, int height);
    }
    private VideoSizeListener mVideoSizeListener;

    public RaopServer(SurfaceView surfaceView) {
        Log.i(TAG, "constructor: loading RAOP server for surface=" + surfaceView);
        mSurfaceView = surfaceView;
        mSurfaceView.getHolder().addCallback(this);
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
        ensureVideoPlayer();
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
        if (mVideoPlayer != null) {
            mVideoPlayer.addPacker(nalPacket);
        }
    }

    public void onRecvAudioData(short[] pcm, long pts) {
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

    public void onAudioVolumeChanged(float volume) {
        Log.i(TAG, "onAudioVolumeChanged: " + volume + " dB");
        mAudioPlayer.setAirPlayVolume(volume);
        if (mLogListener != null) {
            mLogListener.onLog("[Audio] Volume changed to " + volume + " dB");
        }
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

    public void onDirectPlay(String url) {
        Log.i(TAG, "onDirectPlay: url=" + url);
        if (mLogListener != null) {
            mLogListener.onLog("[Direct] Play requested");
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                startDirectPlayer(url);
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
        ensureVideoPlayer();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        mSurfaceHolder = null;
        resetVideoOutput("surface-destroyed");
    }

    public void startServer() {
        if (mServerId == 0) {
            Log.i(TAG, "startServer: calling native start()");
            mServerId = start();
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
        if (mServerId != 0) {
            stop(mServerId);
        }
        mServerId = 0;
        resetVideoOutput("server-stop");
        stopDirectPlayer("server-stop");
        mAudioPlayer.stopPlay();
        Log.i(TAG, "stopServer: AudioPlayer stopped");
    }

    private void ensureVideoPlayer() {
        if (mVideoPlayer != null) {
            return;
        }
        SurfaceHolder holder = mSurfaceHolder != null ? mSurfaceHolder : mSurfaceView.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            Log.w(TAG, "ensureVideoPlayer: surface is not ready");
            return;
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
        Log.i(TAG, "ensureVideoPlayer: VideoPlayer started");
    }

    private void resetVideoOutput(String reason) {
        Log.i(TAG, "resetVideoOutput: reason=" + reason);
        mVideoStreamActive = false;
        mLastVideoPacketRealtimeMs = 0L;
        mLoggedVideoIdle = false;
        mLoggedVideoConfig = false;
        mLoggedFirstVideoFrame = false;
        if (mVideoPlayer != null) {
            mVideoPlayer.releaseAndWait(VIDEO_PLAYER_RELEASE_TIMEOUT_MS);
            mVideoPlayer = null;
        }
        if (mVideoSizeListener != null) {
            mVideoSizeListener.onVideoSizeChanged(0, 0);
        }
    }

    private void startDirectPlayer(String url) {
        if (url == null || url.trim().isEmpty()) {
            Log.w(TAG, "startDirectPlayer: empty URL");
            return;
        }
        SurfaceHolder holder = mSurfaceHolder != null ? mSurfaceHolder : mSurfaceView.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            Log.w(TAG, "startDirectPlayer: surface is not ready");
            return;
        }

        resetVideoOutput("direct-play");
        stopDirectPlayer("replace-direct");

        try {
            MediaPlayer player = new MediaPlayer();
            mDirectPlayer = player;
            player.setSurface(holder.getSurface());
            player.setDataSource(url);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    Log.i(TAG, "Direct MediaPlayer prepared");
                    if (mVideoSizeListener != null) {
                        int width = Math.max(mp.getVideoWidth(), 1);
                        int height = Math.max(mp.getVideoHeight(), 1);
                        mVideoSizeListener.onVideoSizeChanged(width, height);
                    }
                    mp.start();
                }
            });
            player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    Log.i(TAG, "Direct video size: " + width + "x" + height);
                    if (mVideoSizeListener != null && width > 0 && height > 0) {
                        mVideoSizeListener.onVideoSizeChanged(width, height);
                    }
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    Log.i(TAG, "Direct MediaPlayer completed");
                    stopDirectPlayer("completion");
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "Direct MediaPlayer error what=" + what + " extra=" + extra);
                    if (mLogListener != null) {
                        mLogListener.onLog("[Direct] Playback error " + what + "/" + extra);
                    }
                    stopDirectPlayer("error");
                    return true;
                }
            });
            player.prepareAsync();
            Log.i(TAG, "Direct MediaPlayer prepareAsync started");
        } catch (Exception e) {
            Log.e(TAG, "startDirectPlayer failed", e);
            if (mLogListener != null) {
                mLogListener.onLog("[Direct] Failed to start playback: " + e.getMessage());
            }
            stopDirectPlayer("start-failed");
        }
    }

    private void stopDirectPlayer(String reason) {
        if (mDirectPlayer == null) {
            return;
        }
        Log.i(TAG, "stopDirectPlayer: reason=" + reason);
        try {
            mDirectPlayer.setOnPreparedListener(null);
            mDirectPlayer.setOnVideoSizeChangedListener(null);
            mDirectPlayer.setOnCompletionListener(null);
            mDirectPlayer.setOnErrorListener(null);
            if (mDirectPlayer.isPlaying()) {
                mDirectPlayer.stop();
            }
        } catch (Exception e) {
            Log.w(TAG, "Direct MediaPlayer stop failed", e);
        }
        try {
            mDirectPlayer.release();
        } catch (Exception e) {
            Log.w(TAG, "Direct MediaPlayer release failed", e);
        }
        mDirectPlayer = null;
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

    private native long start();
    private native void stop(long serverId);
    private native int getPort(long serverId);
    private native String getPublicKey(long serverId);
}

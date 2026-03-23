package com.fang.myapplication;

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
    private VideoPlayer mVideoPlayer;
    private AudioPlayer mAudioPlayer;
    private SurfaceView mSurfaceView;
    private long mServerId = 0;
    private boolean mLoggedVideoConfig = false;
    private boolean mLoggedFirstVideoFrame = false;
    
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
        mSurfaceView = surfaceView;
        mSurfaceView.getHolder().addCallback(this);
        mAudioPlayer = new AudioPlayer();
        mAudioPlayer.start();
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
        Log.d(TAG, fullMsg);
        if (mLogListener != null) {
            mLogListener.onLog(fullMsg);
        }
    }

    public void onRecvVideoData(byte[] nal, int nalType, long dts, long pts) {
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
        PCMPacket pcmPacket = new PCMPacket();
        pcmPacket.data = pcm;
        pcmPacket.pts = pts;
        mAudioPlayer.addPacker(pcmPacket);
    }

    public void onAudioVolumeChanged(float volume) {
        mAudioPlayer.setAirPlayVolume(volume);
        if (mLogListener != null) {
            mLogListener.onLog("[Audio] Volume changed to " + volume + " dB");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
        if (mLogListener != null) {
            mLogListener.onLog("[Surface] surfaceCreated");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mVideoPlayer == null) {
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
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
        if (mVideoPlayer != null) {
            mVideoPlayer.release();
            mVideoPlayer = null;
        }
        if (mVideoSizeListener != null) {
            mVideoSizeListener.onVideoSizeChanged(0, 0);
        }
    }

    public void startServer() {
        if (mServerId == 0) {
            mServerId = start();
            if (mLogListener != null) {
                mLogListener.onLog("[RAOP] startServer() mServerId=" + mServerId);
            }
        }
    }

    public void stopServer() {
        if (mServerId != 0) {
            stop(mServerId);
        }
        mServerId = 0;
        mAudioPlayer.stopPlay();
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

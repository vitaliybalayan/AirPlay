package com.fang.myapplication.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import com.fang.myapplication.model.PCMPacket;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioPlayer extends Thread {

    private static final String TAG = "AudioPlayer";

    private volatile AudioTrack mTrack;
    private final int mChannel = AudioFormat.CHANNEL_OUT_STEREO;
    private final int mSampleRate = 44100;
    private final int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final LinkedBlockingQueue<PCMPacket> mQueue = new LinkedBlockingQueue<>(512);
    private volatile boolean isStopThread = false;

    // (frames written before this packet, packet RTP timestamp): maps the playback head
    // back to the sender's RTP clock, i.e. what is audible right now.
    private static final int MAX_MARKS = 4096;
    private final ArrayDeque<long[]> mMarks = new ArrayDeque<>();
    private long mFramesWritten = 0;

    public AudioPlayer() {
        // The minimum buffer underruns (clicks) on busy TV SoCs; 4x is still well under 100 ms.
        this.mTrack = createTrack();
        setAirPlayVolume(0.0f);
    }

    private AudioTrack createTrack() {
        int bufferSize = AudioTrack.getMinBufferSize(mSampleRate, mChannel, mAudioFormat) * 4;
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate, mChannel, mAudioFormat,
                bufferSize, AudioTrack.MODE_STREAM);
        track.play();
        return track;
    }

    /**
     * Called when a sender starts a new audio stream. The track lives for the whole process,
     * and the TV can leave it paused (e.g. after another app played audio); a paused track
     * fills up and blocks write(), so every later stream would be silent.
     */
    public void startSession() {
        mQueue.clear();
        synchronized (mMarks) {
            mMarks.clear();
        }
        AudioTrack track = mTrack;
        try {
            track.pause();
            track.flush();
            track.play();
            mFramesWritten = 0;
        } catch (IllegalStateException e) {
            Log.w(TAG, "startSession: track unusable, recreating", e);
            recreateTrack();
        }
    }

    private synchronized void recreateTrack() {
        AudioTrack old = mTrack;
        mTrack = createTrack();
        mFramesWritten = 0;
        synchronized (mMarks) {
            mMarks.clear();
        }
        try {
            old.release();
        } catch (Exception ignored) {
        }
    }

    public void addPacker(PCMPacket pcmPacket) {
        if (!mQueue.offer(pcmPacket)) {
            mQueue.poll();
            mQueue.offer(pcmPacket);
        }
    }

    /** AirPlay volume is -30..0 dB, with -144 meaning mute. */
    public static float airPlayDbToLinear(float volumeDb) {
        float clamped = Math.max(-144.0f, Math.min(0.0f, volumeDb));
        return clamped <= -144.0f ? 0.0f : (float) Math.pow(10.0, clamped / 20.0);
    }

    public void setAirPlayVolume(float volumeDb) {
        float linear = airPlayDbToLinear(volumeDb);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mTrack.setVolume(linear);
            } else {
                mTrack.setStereoVolume(linear, linear);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void run() {
        super.run();
        while (!isStopThread) {
            try {
                PCMPacket packet = mQueue.take();
                doPlay(packet);
            } catch (InterruptedException e) {
                if (isStopThread) {
                    break;
                }
            }
        }
    }

    private void doPlay(PCMPacket pcmPacket) {
        if (pcmPacket != null && pcmPacket.data != null) {
            synchronized (mMarks) {
                mMarks.addLast(new long[]{mFramesWritten, pcmPacket.pts & 0xFFFFFFFFL});
                if (mMarks.size() > MAX_MARKS) {
                    mMarks.removeFirst();
                }
            }
            try {
                AudioTrack track = mTrack;
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    Log.w(TAG, "track was not playing (state=" + track.getPlayState() + "), resuming");
                    track.play();
                }
                int written = track.write(pcmPacket.data, 0, pcmPacket.data.length);
                if (written > 0) {
                    mFramesWritten += written / 2;
                } else if (written < 0) {
                    Log.w(TAG, "write failed: " + written + ", recreating track");
                    recreateTrack();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "write on a broken track, recreating", e);
                recreateTrack();
            }
        }
    }

    /** RTP timestamp of the sample being played right now, or -1 if unknown. */
    public long getPlayingRtpTimestamp() {
        long head;
        try {
            head = mTrack.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (IllegalStateException e) {
            return -1;
        }
        synchronized (mMarks) {
            Iterator<long[]> it = mMarks.descendingIterator();
            while (it.hasNext()) {
                long[] mark = it.next();
                if (mark[0] <= head) {
                    return (mark[1] + (head - mark[0])) & 0xFFFFFFFFL;
                }
            }
        }
        return -1;
    }


    public void stopPlay() {
        isStopThread = true;
        interrupt();
        if (mTrack != null) {
            try {
                mTrack.flush();
                mTrack.stop();
            } catch (IllegalStateException ignored) {
            }
            mTrack.release();
        }
    }

}

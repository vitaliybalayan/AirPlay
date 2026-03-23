package com.fang.myapplication.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import com.fang.myapplication.model.PCMPacket;

import java.util.concurrent.LinkedBlockingQueue;

public class AudioPlayer extends Thread {

    private final AudioTrack mTrack;
    private final int mChannel = AudioFormat.CHANNEL_OUT_STEREO;
    private final int mSampleRate = 44100;
    private final int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final LinkedBlockingQueue<PCMPacket> mQueue = new LinkedBlockingQueue<>(512);
    private volatile boolean isStopThread = false;

    public AudioPlayer() {
        this.mTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate, mChannel, mAudioFormat,
                AudioTrack.getMinBufferSize(mSampleRate, mChannel, mAudioFormat), AudioTrack.MODE_STREAM);
        this.mTrack.play();
        setAirPlayVolume(0.0f);
    }

    public void addPacker(PCMPacket pcmPacket) {
        if (!mQueue.offer(pcmPacket)) {
            mQueue.poll();
            mQueue.offer(pcmPacket);
        }
    }

    public void setAirPlayVolume(float volumeDb) {
        float clamped = Math.max(-144.0f, Math.min(0.0f, volumeDb));
        float linear = clamped <= -144.0f ? 0.0f : (float) Math.pow(10.0, clamped / 20.0);
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
            try {
                mTrack.write(pcmPacket.data, 0, Math.min(pcmPacket.data.length, 960));
            } catch (IllegalStateException ignored) {
            }
        }
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

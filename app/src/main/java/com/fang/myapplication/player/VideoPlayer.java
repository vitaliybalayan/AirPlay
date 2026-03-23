package com.fang.myapplication.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.fang.myapplication.model.NALPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class VideoPlayer extends Thread {

    private static final String TAG = "VideoPlayer";
    private static final long CONFIG_STABILIZE_MS = 700;
    private static final long MIN_DECODER_RESTART_INTERVAL_MS = 2500;

    public interface LogListener {
        void onLog(String message);
    }

    public interface VideoSizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private final String mMimeType = "video/avc";
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private final Surface mSurface;
    private final LogListener mLogListener;
    private final VideoSizeListener mVideoSizeListener;
    private volatile boolean mIsEnd = false;
    private final LinkedBlockingQueue<NALPacket> mQueue = new LinkedBlockingQueue<>(300);

    private MediaCodec mDecoder = null;
    private int mVideoWidth = 1280;
    private int mVideoHeight = 720;
    private byte[] mSps = null;
    private byte[] mPps = null;
    private byte[] mPendingSps = null;
    private byte[] mPendingPps = null;
    private int mPendingVideoWidth = 0;
    private int mPendingVideoHeight = 0;
    private long mPendingConfigSinceMs = 0L;
    private boolean mLoggedFirstRenderedFrame = false;
    private int mDroppedBeforeDecoder = 0;
    private int mConsecutiveInputUnavailable = 0;
    private long mLastDecoderInitMs = 0L;
    private int mReportedVideoWidth = 0;
    private int mReportedVideoHeight = 0;

    public VideoPlayer(Surface surface, LogListener logListener, VideoSizeListener videoSizeListener) {
        mSurface = surface;
        mLogListener = logListener;
        mVideoSizeListener = videoSizeListener;
    }

    private void log(String message) {
        Log.d(TAG, message);
        if (mLogListener != null) {
            mLogListener.onLog("[Decoder] " + message);
        }
    }

    private void logError(String message, Exception e) {
        Log.e(TAG, message, e);
        if (mLogListener != null) {
            String suffix = e != null && e.getMessage() != null ? ": " + e.getMessage() : "";
            mLogListener.onLog("[Decoder] ERROR " + message + suffix);
        }
    }

    private void initDecoder(String reason) {
        if (mSps == null || mPps == null) {
            log("Waiting for SPS/PPS before MediaCodec init");
            return;
        }
        releaseDecoder();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mMimeType, mVideoWidth, mVideoHeight);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(mSps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(mPps)));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(mVideoWidth * mVideoHeight, 1024 * 1024));
            mDecoder = MediaCodec.createDecoderByType(mMimeType);
            mDecoder.configure(format, mSurface, null, 0);
            mDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            mDecoder.start();
            mLastDecoderInitMs = SystemClock.elapsedRealtime();
            mLoggedFirstRenderedFrame = false;
            mConsecutiveInputUnavailable = 0;
            if (mDroppedBeforeDecoder > 0) {
                log("Decoder ready after dropping " + mDroppedBeforeDecoder + " frames while waiting for SPS/PPS");
                mDroppedBeforeDecoder = 0;
            }
            log("MediaCodec started " + mVideoWidth + "x" + mVideoHeight + " reason=" + reason);
        } catch (Exception e) {
            mDecoder = null;
            logError("MediaCodec init failed", e);
        }
    }

    public void addPacker(NALPacket nalPacket) {
        if (mIsEnd) return;
        if (!mQueue.offer(nalPacket)) {
            Log.w(TAG, "Video queue full, dropping frame! pts=" + nalPacket.pts);
        }
    }

    @Override
    public void run() {
        super.run();
        Log.d(TAG, "VideoPlayer Thread running...");

        while (!mIsEnd) {
            NALPacket nalPacket = null;
            try {
                nalPacket = mQueue.take();
            } catch (InterruptedException e) {
                if (mIsEnd) break;
            }

            if (nalPacket == null) {
                continue;
            }

            maybeApplyPendingCodecConfig(false);

            if (nalPacket.nalType == 0) {
                handleCodecConfig(nalPacket);
                continue;
            }

            if (mDecoder != null) {
                doDecode(nalPacket);
            } else {
                mDroppedBeforeDecoder++;
                if (mDroppedBeforeDecoder == 1 || mDroppedBeforeDecoder % 60 == 0) {
                    log("Dropping frame until decoder is configured, dropped=" + mDroppedBeforeDecoder + ", pts=" + nalPacket.pts);
                }
            }
        }

        releaseDecoder();
        Log.d(TAG, "VideoPlayer Thread exited.");
    }

    private void handleCodecConfig(NALPacket nalPacket) {
        List<byte[]> units = splitAnnexB(nalPacket.nalData);
        byte[] newSps = null;
        byte[] newPps = null;
        for (byte[] unit : units) {
            if (unit.length == 0) {
                continue;
            }
            int nalType = unit[0] & 0x1F;
            if (nalType == 7) {
                newSps = unit;
            } else if (nalType == 8) {
                newPps = unit;
            }
        }

        if (newSps == null || newPps == null) {
            log("Codec config packet received but SPS/PPS not found");
            return;
        }

        boolean changed = !Arrays.equals(mSps, newSps) || !Arrays.equals(mPps, newPps);
        int parsedWidth = mVideoWidth;
        int parsedHeight = mVideoHeight;
        int[] dimensions = parseDimensionsFromSps(newSps);
        if (dimensions != null) {
            parsedWidth = dimensions[0];
            parsedHeight = dimensions[1];
        }

        log("SPS/PPS parsed, size=" + parsedWidth + "x" + parsedHeight + ", changed=" + changed);
        notifyVideoSize(parsedWidth, parsedHeight, "codec-config");

        if (mDecoder == null) {
            applyCodecConfig(newSps, newPps, parsedWidth, parsedHeight, "initial");
            return;
        }

        if (!changed) {
            return;
        }

        if (canKeepCurrentDecoder(parsedWidth, parsedHeight)) {
            log("Keeping decoder " + mVideoWidth + "x" + mVideoHeight + " for stream " + parsedWidth + "x" + parsedHeight);
            return;
        }

        queuePendingCodecConfig(newSps, newPps, parsedWidth, parsedHeight);
        maybeApplyPendingCodecConfig(false);
    }

    private void doDecode(NALPacket nalPacket) {
        final int TIMEOUT_USEC = 10000;

        try {
            int inputBufIndex = mDecoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufIndex >= 0) {
                if (mConsecutiveInputUnavailable >= 10) {
                    log("Input buffer recovered after " + mConsecutiveInputUnavailable + " unavailable polls");
                }
                mConsecutiveInputUnavailable = 0;
                ByteBuffer inputBuf = mDecoder.getInputBuffer(inputBufIndex);
                if (inputBuf != null) {
                    inputBuf.clear();
                    inputBuf.put(nalPacket.nalData);
                    mDecoder.queueInputBuffer(inputBufIndex, 0, nalPacket.nalData.length, nalPacket.pts, 0);
                }
            } else {
                mConsecutiveInputUnavailable++;
                if (mConsecutiveInputUnavailable == 10 || mConsecutiveInputUnavailable % 120 == 0) {
                    log("Input buffer unavailable, consecutive=" + mConsecutiveInputUnavailable + ", pts=" + nalPacket.pts);
                }
            }

            int outputBufferIndex;
            do {
                outputBufferIndex = mDecoder.dequeueOutputBuffer(mBufferInfo, 1000);
                if (outputBufferIndex >= 0) {
                    mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                    if (!mLoggedFirstRenderedFrame) {
                        mLoggedFirstRenderedFrame = true;
                        log("First frame rendered, pts=" + mBufferInfo.presentationTimeUs);
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mDecoder.getOutputFormat();
                    int[] size = extractDisplayedSize(newFormat, mVideoWidth, mVideoHeight);
                    notifyVideoSize(size[0], size[1], "output-format");
                    log("Output format changed: " + newFormat);
                }
            } while (outputBufferIndex >= 0);

        } catch (Exception e) {
            logError("Exception in doDecode", e);
        }
    }

    private void releaseDecoder() {
        if (mDecoder == null) {
            return;
        }
        try {
            mDecoder.stop();
        } catch (Exception e) {
            Log.w(TAG, "MediaCodec stop failed", e);
        }
        try {
            mDecoder.release();
        } catch (Exception e) {
            Log.w(TAG, "MediaCodec release failed", e);
        }
        mDecoder = null;
        mConsecutiveInputUnavailable = 0;
    }

    private void applyCodecConfig(byte[] sps, byte[] pps, int width, int height, String reason) {
        mSps = sps;
        mPps = pps;
        mVideoWidth = width;
        mVideoHeight = height;
        clearPendingCodecConfig();
        initDecoder(reason);
    }

    private void queuePendingCodecConfig(byte[] sps, byte[] pps, int width, int height) {
        boolean sameAsPending = Arrays.equals(mPendingSps, sps) &&
                Arrays.equals(mPendingPps, pps) &&
                mPendingVideoWidth == width &&
                mPendingVideoHeight == height;
        if (sameAsPending) {
            return;
        }

        boolean replacingPending = mPendingSps != null;
        mPendingSps = sps;
        mPendingPps = pps;
        mPendingVideoWidth = width;
        mPendingVideoHeight = height;
        mPendingConfigSinceMs = SystemClock.elapsedRealtime();
        log((replacingPending ? "Replacing" : "Queued") +
                " pending codec config " + width + "x" + height);
    }

    private void clearPendingCodecConfig() {
        mPendingSps = null;
        mPendingPps = null;
        mPendingVideoWidth = 0;
        mPendingVideoHeight = 0;
        mPendingConfigSinceMs = 0L;
    }

    private void maybeApplyPendingCodecConfig(boolean force) {
        long now;

        if (mPendingSps == null || mPendingPps == null) {
            return;
        }
        now = SystemClock.elapsedRealtime();
        if (!force) {
            if (now - mPendingConfigSinceMs < CONFIG_STABILIZE_MS) {
                return;
            }
            if (mLastDecoderInitMs > 0 && now - mLastDecoderInitMs < MIN_DECODER_RESTART_INTERVAL_MS) {
                return;
            }
        }
        applyCodecConfig(mPendingSps, mPendingPps, mPendingVideoWidth, mPendingVideoHeight, "stabilized-config");
    }

    private boolean canKeepCurrentDecoder(int width, int height) {
        if (mDecoder == null) {
            return false;
        }
        return width > 0 && height > 0 && width <= mVideoWidth && height <= mVideoHeight;
    }

    private void notifyVideoSize(int width, int height, String source) {
        if (mVideoSizeListener == null || width <= 0 || height <= 0) {
            return;
        }
        if (mReportedVideoWidth == width && mReportedVideoHeight == height) {
            return;
        }
        mReportedVideoWidth = width;
        mReportedVideoHeight = height;
        mVideoSizeListener.onVideoSizeChanged(width, height);
        log("Video size -> " + width + "x" + height + " source=" + source);
    }

    private static int[] extractDisplayedSize(MediaFormat format, int fallbackWidth, int fallbackHeight) {
        int width = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : fallbackWidth;
        int height = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : fallbackHeight;

        if (format.containsKey("crop-right") &&
                format.containsKey("crop-left") &&
                format.containsKey("crop-bottom") &&
                format.containsKey("crop-top")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
        }
        return new int[]{width, height};
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static List<byte[]> splitAnnexB(byte[] data) {
        ArrayList<byte[]> units = new ArrayList<>();
        int start = findStartCode(data, 0);
        while (start >= 0) {
            int prefixLength = startCodeLength(data, start);
            int nalStart = start + prefixLength;
            int nextStart = findStartCode(data, nalStart);
            int nalEnd = nextStart >= 0 ? nextStart : data.length;
            if (nalEnd > nalStart) {
                units.add(Arrays.copyOfRange(data, nalStart, nalEnd));
            }
            start = nextStart;
        }
        return units;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = from; i <= data.length - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int index) {
        if (index <= data.length - 4 && data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 0 && data[index + 3] == 1) {
            return 4;
        }
        return 3;
    }

    private static int[] parseDimensionsFromSps(byte[] sps) {
        try {
            byte[] rbsp = unescapeRbsp(sps);
            BitReader br = new BitReader(rbsp);
            br.readBits(8);
            int profileIdc = br.readBits(8);
            br.readBits(8);
            br.readBits(8);
            br.readUnsignedExpGolomb();

            int chromaFormatIdc = 1;
            if (isExtendedProfile(profileIdc)) {
                chromaFormatIdc = br.readUnsignedExpGolomb();
                if (chromaFormatIdc == 3) {
                    br.readBit();
                }
                br.readUnsignedExpGolomb();
                br.readUnsignedExpGolomb();
                br.readBit();
                if (br.readBit() == 1) {
                    int scalingListCount = chromaFormatIdc == 3 ? 12 : 8;
                    for (int i = 0; i < scalingListCount; i++) {
                        if (br.readBit() == 1) {
                            skipScalingList(br, i < 6 ? 16 : 64);
                        }
                    }
                }
            }

            br.readUnsignedExpGolomb();
            int picOrderCntType = br.readUnsignedExpGolomb();
            if (picOrderCntType == 0) {
                br.readUnsignedExpGolomb();
            } else if (picOrderCntType == 1) {
                br.readBit();
                br.readSignedExpGolomb();
                br.readSignedExpGolomb();
                int count = br.readUnsignedExpGolomb();
                for (int i = 0; i < count; i++) {
                    br.readSignedExpGolomb();
                }
            }

            br.readUnsignedExpGolomb();
            br.readBit();
            int picWidthInMbsMinus1 = br.readUnsignedExpGolomb();
            int picHeightInMapUnitsMinus1 = br.readUnsignedExpGolomb();
            int frameMbsOnlyFlag = br.readBit();
            if (frameMbsOnlyFlag == 0) {
                br.readBit();
            }
            br.readBit();

            int frameCropLeftOffset = 0;
            int frameCropRightOffset = 0;
            int frameCropTopOffset = 0;
            int frameCropBottomOffset = 0;
            if (br.readBit() == 1) {
                frameCropLeftOffset = br.readUnsignedExpGolomb();
                frameCropRightOffset = br.readUnsignedExpGolomb();
                frameCropTopOffset = br.readUnsignedExpGolomb();
                frameCropBottomOffset = br.readUnsignedExpGolomb();
            }

            int cropUnitX;
            int cropUnitY;
            if (chromaFormatIdc == 0) {
                cropUnitX = 1;
                cropUnitY = 2 - frameMbsOnlyFlag;
            } else if (chromaFormatIdc == 1) {
                cropUnitX = 2;
                cropUnitY = 2 * (2 - frameMbsOnlyFlag);
            } else if (chromaFormatIdc == 2) {
                cropUnitX = 2;
                cropUnitY = 2 - frameMbsOnlyFlag;
            } else {
                cropUnitX = 1;
                cropUnitY = 2 - frameMbsOnlyFlag;
            }

            int width = (picWidthInMbsMinus1 + 1) * 16;
            int height = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag);
            width -= (frameCropLeftOffset + frameCropRightOffset) * cropUnitX;
            height -= (frameCropTopOffset + frameCropBottomOffset) * cropUnitY;

            if (width > 0 && height > 0) {
                return new int[]{width, height};
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse SPS dimensions", e);
        }
        return null;
    }

    private static boolean isExtendedProfile(int profileIdc) {
        switch (profileIdc) {
            case 100:
            case 110:
            case 122:
            case 244:
            case 44:
            case 83:
            case 86:
            case 118:
            case 128:
            case 138:
            case 139:
            case 134:
            case 135:
                return true;
            default:
                return false;
        }
    }

    private static void skipScalingList(BitReader br, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int deltaScale = br.readSignedExpGolomb();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            lastScale = nextScale == 0 ? lastScale : nextScale;
        }
    }

    private static byte[] unescapeRbsp(byte[] data) {
        byte[] out = new byte[data.length];
        int outIndex = 0;
        for (int i = 0; i < data.length; i++) {
            if (i >= 2 && data[i] == 0x03 && data[i - 1] == 0x00 && data[i - 2] == 0x00) {
                continue;
            }
            out[outIndex++] = data[i];
        }
        return Arrays.copyOf(out, outIndex);
    }

    private static final class BitReader {
        private final byte[] data;
        private int bitOffset = 0;

        private BitReader(byte[] data) {
            this.data = data;
        }

        private int readBit() {
            if (bitOffset >= data.length * 8) {
                throw new IllegalStateException("No more bits");
            }
            int value = (data[bitOffset / 8] >> (7 - (bitOffset % 8))) & 0x01;
            bitOffset++;
            return value;
        }

        private int readBits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }

        private int readUnsignedExpGolomb() {
            int leadingZeroBits = 0;
            while (readBit() == 0) {
                leadingZeroBits++;
            }
            int suffix = leadingZeroBits == 0 ? 0 : readBits(leadingZeroBits);
            return ((1 << leadingZeroBits) - 1) + suffix;
        }

        private int readSignedExpGolomb() {
            int codeNum = readUnsignedExpGolomb();
            int sign = (codeNum & 1) == 0 ? -1 : 1;
            return sign * ((codeNum + 1) / 2);
        }
    }

    public void release() {
        mIsEnd = true;
        clearPendingCodecConfig();
        interrupt();
    }
}

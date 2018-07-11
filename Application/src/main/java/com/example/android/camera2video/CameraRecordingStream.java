 /*
  * Copyright (C) 2013 The Android Open Source Project
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *      http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package com.example.android.camera2video;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.example.android.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
/**
 * Camera video recording class. It takes frames produced by camera and encoded
 * with either MediaCodec or MediaRecorder. MediaRecorder path is not
 * implemented yet.
 */
public class CameraRecordingStream {
    private static final String TAG = "CameraRecordingStream";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final int STREAM_STATE_IDLE = 0;
    private static final int STREAM_STATE_CONFIGURED = 1;
    private static final int STREAM_STATE_RECORDING = 2;
    private static final String MIME_TYPE = "video/avc"; // H.264 AVC encoding
    private static final int FRAME_RATE = 30; // 30fps
    private static final int IFRAME_INTERVAL = 1; // 1 seconds between I-frames
    private static final int TIMEOUT_USEC = 10000; // Timeout value 10ms.
    // Sync object to protect stream state access from multiple threads.
    private final Object mStateLock = new Object();
    private int mStreamState = STREAM_STATE_IDLE;
    private MediaCodec mEncoder;
    private Surface mRecordingSurface;
    private int mEncBitRate;
    private MediaCodec.BufferInfo mBufferInfo;
    private MediaMuxer mMuxer;
    private int mTrackIndex = -1;
    private boolean mMuxerStarted;
    private boolean mUseMediaCodec = false;
    private Size mStreamSize = new Size(-1, -1);
    private Thread mRecordingThread;
    private String mSavePath;
    public interface DataProvider{
        public Camera2VideoFragment.ImageDataInfo getImageData();
        public void recycleImageData(Camera2VideoFragment.ImageDataInfo data);
    }
    public DataProvider mDataProvider;
    public void setDataProvider(DataProvider provider){
        this.mDataProvider = provider;
    }
    public CameraRecordingStream() {
    }
    /**
     * Configure stream with a size and encoder mode.
     *
     * @param size Size of recording stream.
     * @param useMediaCodec The encoder for this stream to use, either MediaCodec
     * or MediaRecorder.
     * @param bitRate Bit rate the encoder takes.
     */
    public synchronized void configure(Size size, boolean useMediaCodec, int bitRate, String savePath, boolean usingInputSurface) {
        if (getStreamState() == STREAM_STATE_RECORDING) {
            throw new IllegalStateException(
                    "Stream can only be configured when stream is in IDLE state");
        }
        mSavePath = savePath;
        mUsingInputSurface = usingInputSurface;
        boolean isConfigChanged =
                (!mStreamSize.equals(size)) ||
                        (mUseMediaCodec != useMediaCodec) ||
                        (mEncBitRate != bitRate);
        mStreamSize = size;
        mUseMediaCodec = useMediaCodec;
        mEncBitRate = bitRate;
//        if (mUseMediaCodec) {
            if (getStreamState() == STREAM_STATE_CONFIGURED) {
                /**
                 * Stream is already configured, need release encoder and muxer
                 * first, then reconfigure only if configuration is changed.
                 */
                if (!isConfigChanged) {
                    /**
                     * TODO: this is only the skeleton, it is tricky to
                     * implement because muxer need reconfigure always. But
                     * muxer is closely coupled with MediaCodec for now because
                     * muxer can only be started once format change callback is
                     * sent from mediacodec. We need decouple MediaCodec and
                     * Muxer for future.
                     */
                }
                releaseEncoder();
                releaseMuxer();
                configureMediaCodecEncoder();
            } else {
                configureMediaCodecEncoder();
            }
//        } else {
//            // TODO: implement MediaRecoder mode.
//            Log.w(TAG, "MediaRecorder configure is not implemented yet");
//        }
        setStreamState(STREAM_STATE_CONFIGURED);
    }
    /**
     * Add the stream output surface to the target output surface list.
     *
     * @param outputSurfaces The output surface list where the stream can
     * add/remove its output surface.
     * @param detach Detach the recording surface from the outputSurfaces.
     */
    public synchronized void onConfiguringOutputs(List<Surface> outputSurfaces,
                                                  boolean detach) {
        if (detach) {
            // Can detach the surface in CONFIGURED and RECORDING state
            if (getStreamState() != STREAM_STATE_IDLE) {
                outputSurfaces.remove(mRecordingSurface);
            } else {
                Log.w(TAG, "Can not detach surface when recording stream is in IDLE state");
            }
        } else {
            // Can add surface only in CONFIGURED state.
            if (getStreamState() == STREAM_STATE_CONFIGURED) {
                outputSurfaces.add(mRecordingSurface);
            } else {
                Log.w(TAG, "Can only add surface when recording stream is in CONFIGURED state");
            }
        }
    }
    /**
     * Update capture request with configuration required for recording stream.
     *
     * @param requestBuilder Capture request builder that needs to be updated
     * for recording specific camera settings.
     * @param detach Detach the recording surface from the capture request.
     */
    public synchronized void onConfiguringRequest(CaptureRequest.Builder requestBuilder, List<Surface> surfaces,
                                                  boolean detach) {
        if(!mUsingInputSurface)
            return;
        if (detach) {
            // Can detach the surface in CONFIGURED and RECORDING state
            if (getStreamState() != STREAM_STATE_IDLE) {
                requestBuilder.removeTarget(mRecordingSurface);
                surfaces.remove(mRecordingSurface);
            } else {
                Log.w(TAG, "Can not detach surface when recording stream is in IDLE state");
            }
        } else {
            // Can add surface only in CONFIGURED state.
            if (getStreamState() == STREAM_STATE_CONFIGURED) {
                requestBuilder.addTarget(mRecordingSurface);
                surfaces.add(mRecordingSurface);
            } else {
                Log.w(TAG, "Can only add surface when recording stream is in CONFIGURED state");
            }
        }
    }
    /**
     * Start recording stream. Calling start on an already started stream has no
     * effect.
     */
    public synchronized void start() {
        if (getStreamState() == STREAM_STATE_RECORDING) {
            Log.w(TAG, "Recording stream is already started");
            return;
        }
        if (getStreamState() != STREAM_STATE_CONFIGURED) {
            throw new IllegalStateException("Recording stream is not configured yet");
        }
        if (mUseMediaCodec) {
            setStreamState(STREAM_STATE_RECORDING);
            startMediaCodecRecording();
        } else {
            setStreamState(STREAM_STATE_RECORDING);
        }
    }
    /**
     * <p>
     * Stop recording stream. Calling stop on an already stopped stream has no
     * effect. Producer(in this case, CameraDevice) should stop before this call
     * to avoid sending buffers to a stopped encoder.
     * </p>
     * <p>
     * TODO: We have to release encoder and muxer for MediaCodec mode because
     * encoder is closely coupled with muxer, and muxser can not be reused
     * across different recording session(by design, you can not reset/restart
     * it). To save the subsequent start recording time, we need avoid releasing
     * encoder for future.
     * </p>
     */
    public synchronized void stop() {
        if (getStreamState() != STREAM_STATE_RECORDING) {
            Log.w(TAG, "Recording stream is not started yet");
            return;
        }
        setStreamState(STREAM_STATE_IDLE);
        Log.e(TAG, "setting camera to idle");
        if (mUseMediaCodec) {
            // Wait until recording thread stop
            try {
                mRecordingThread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException("Stop recording failed", e);
            }
            // Drain encoder
            doMediaCodecEncoding(/* notifyEndOfStream */true);
            releaseEncoder();
            releaseMuxer();
        } else {
            releaseEncoder();
            releaseMuxer();
            Log.w(TAG, "MediaRecorder mode recording stop is not implemented yet");
        }
    }
    /**
     * Starts MediaCodec mode recording.
     */
    private void startMediaCodecRecording() {
        /**
         * Start video recording asynchronously. we need a loop to handle output
         * data for each frame.
         */
        mRecordingThread = new Thread() {
            @Override
            public void run() {
                if (VERBOSE) {
                    Log.v(TAG, "Recording thread starts");
                }
                while (getStreamState() == STREAM_STATE_RECORDING) {
                    // Feed encoder output into the muxer until recording stops.
                    doMediaCodecEncoding(/* notifyEndOfStream */false);
                }
                if (VERBOSE) {
                    Log.v(TAG, "Recording thread completes");
                }
                return;
            }
        };
        mRecordingThread.start();
    }
    // Thread-safe access to the stream state.
    private synchronized void setStreamState(int state) {
        synchronized (mStateLock) {
            if (state < STREAM_STATE_IDLE) {
                throw new IllegalStateException("try to set an invalid state");
            }
            mStreamState = state;
        }
    }
    // Thread-safe access to the stream state.
    private int getStreamState() {
        synchronized(mStateLock) {
            return mStreamState;
        }
    }
    private void releaseEncoder() {
        // Release encoder
        if (VERBOSE) {
            Log.v(TAG, "releasing encoder");
        }
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            if (mRecordingSurface != null) {
                mRecordingSurface.release();
            }
            mEncoder = null;
        }
    }
    private void releaseMuxer() {
        if (VERBOSE) {
            Log.v(TAG, "releasing muxer");
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }
    private String getOutputMediaFileName() {
        String state = Environment.getExternalStorageState();
        // Check if external storage is mounted
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            Log.e(TAG, "External storage is not mounted!");
            return null;
        }
        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "TestingCamera2");
        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.e(TAG, "Failed to create directory " + mediaStorageDir.getPath()
                        + " for pictures/video!");
                return null;
            }
        }
        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String mediaFileName = mediaStorageDir.getPath() + File.separator +
                "VID_" + timeStamp + ".mp4";
        return mediaFileName;
    }
    private boolean mUsingInputSurface = false;
    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     * Initializes mEncoder, mMuxer, mRecordingSurface, mBufferInfo,
     * mTrackIndex, and mMuxerStarted.
     */
    private void configureMediaCodecEncoder() {
        mBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format =
                MediaFormat.createVideoFormat(MIME_TYPE,
                        mStreamSize.getWidth(), mStreamSize.getHeight());
        /**
         * Set encoding properties. Failing to specify some of these can cause
         * the MediaCodec configure() call to throw an exception.
         */
        if(mUsingInputSurface) {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        }else {
            MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
            if (codecInfo == null) {
                // Don't fail CTS if they don't have an AVC codec (not here, anyway).
                Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
                return;
            }
            Log.d(TAG, "found codec: " + codecInfo.getName());
            int colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
             Log.d(TAG, "found colorFormat: " + colorFormat);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, mEncBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        Log.i(TAG, "configure video encoding format: " + format);
        // Create/configure a MediaCodec encoder.
        try {
            mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        } catch (IOException ioe) {
            throw new IllegalStateException("MediaCodec creation failed", ioe);
        }
        mMuxerStarted = false;
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        if(mUsingInputSurface)
            mRecordingSurface = mEncoder.createInputSurface();
        mEncoder.start();

        String outputFileName = mSavePath;
        if(outputFileName == null) {
            outputFileName = getOutputMediaFileName();
        }
        if (outputFileName == null) {
            throw new IllegalStateException("Failed to get video output file");
        }
        /**
         * Create a MediaMuxer. We can't add the video track and start() the
         * muxer until the encoder starts and notifies the new media format.
         */
        try {
            mMuxer = new MediaMuxer(
                    outputFileName, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new IllegalStateException("MediaMuxer creation failed", ioe);
        }
        mMuxerStarted = false;
    }
    /**
     * Do encoding by using MediaCodec encoder, then extracts all pending data
     * from the encoder and forwards it to the muxer.
     * <p>
     * If notifyEndOfStream is not set, this returns when there is no more data
     * to output. If it is set, we send EOS to the encoder, and then iterate
     * until we see EOS on the output. Calling this with notifyEndOfStream set
     * should be done once, before stopping the muxer.
     * </p>
     * <p>
     * We're just using the muxer to get a .mp4 file and audio is not included
     * here.
     * </p>
     */
    private void doMediaCodecEncoding(boolean notifyEndOfStream) {
        boolean notDone = true;
        if (mUsingInputSurface && notifyEndOfStream) {
            mEncoder.signalEndOfInputStream();
        }
        while (notDone) {
            if(!mUsingInputSurface) {
                if (mDataProvider == null)
                    return;
                Camera2VideoFragment.ImageDataInfo info = mDataProvider.getImageData();
                if(info != null){
                    int inputBufIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                    Log.d(TAG, "set image to input buffer index " + inputBufIndex);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufIndex);
                        inputBuffer.clear();
                        Log.d(TAG,"Buffer size:"+inputBuffer.capacity()+", image size:"+info.mImageData.length);//    CXD Buffer size:1200128, image size:1572862
                        inputBuffer.put(info.mImageData);
//                        FileUtils.saveRawData(info.mImageData, "/sdcard/ArcAR_Dump/dumpImageNV_" + info.mPresentationTimeUs+"_1024x768.nv21");
                        mEncoder.queueInputBuffer(inputBufIndex, 0, info.mImageData.length, info.mPresentationTimeUs, notifyEndOfStream ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        lastPresentationTimeUs =  info.mPresentationTimeUs;
                        mDataProvider.recycleImageData(info);
                    }
                }else if(notifyEndOfStream){
                    int inputBufIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                    Log.d(TAG, "notifyEndOfStream buffer index " + inputBufIndex);
                    if(inputBufIndex >=0) {
                        mEncoder.queueInputBuffer(inputBufIndex, 0, 0, lastPresentationTimeUs + 33000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }

            }

            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            Log.d(TAG, "output buffer index:"+encoderStatus);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!notifyEndOfStream) {
                    notDone = false;
                } else {
                        Log.i(TAG, "no output available, spinning to await EOS");
                }
            }
//            else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
//                // generic case for mediacodec, not likely occurs for encoder.
//                encoderOutputBuffers = mEncoder.getOutputBuffers();
//            }
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                /**
                 * should happen before receiving buffers, and should only
                 * happen once
                 */
                if (mMuxerStarted) {
                    throw new IllegalStateException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                mTrackIndex = mMuxer.addTrack(newFormat);
                Log.i(TAG, "doMediaCodecEncoding  mMuxer.addTrack "+newFormat);
                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else {
                // Normal flow: get output encoded buffer, send to muxer.
                ByteBuffer encodedData = mEncoder.getOutputBuffer(encoderStatus);//encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mBufferInfo.size = 0;
                }
                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    Log.i(TAG, "Buffer info timestamp:"+mBufferInfo.presentationTimeUs);
                }
                mEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!notifyEndOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                            Log.v(TAG, "end of stream reached");
                    }
                    // Finish encoding.
                    notDone = false;
                    Log.d(TAG,"output BUFFER_FLAG_END_OF_STREAM");
                }
            }
        } // End of while(notDone)
    }
    private long lastPresentationTimeUs;

    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }
    /**
     * Returns a color format that is supported by the codec and by this test code.  If no
     * match is found, this throws a test failure -- the set of formats known to the test
     * should be expanded for new platforms.
     */
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        Log.e(TAG,"couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return 0;   // not reached
    }

    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }
}
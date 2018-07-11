/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2video;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Toast;

import com.example.android.utils.FileUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public class Camera2VideoFragment extends Fragment
        implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    private static final String TAG = "Camera2VideoFragment";
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to record video
     */
    private Button mButtonVideo;

    private CheckBox mDumpRawCheckBox;

    private boolean mUsingInputSurface = false;

    /**
     * A reference to the opened {@link android.hardware.camera2.CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * A reference to the current {@link android.hardware.camera2.CameraCaptureSession} for
     * preview.
     */
    private CameraCaptureSession mPreviewSession;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

    };

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * The {@link android.util.Size} of video recording.
     */
    private Size mVideoSize;

    private android.util.Range<Integer> mFPSRange;
    private  final int EXPECTED_FPS = 30;

    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;


    private CameraRecordingStream mMediaCodecWrapper;

    /**
     * Whether the app is recording video now
     */
    private boolean mIsRecordingVideo;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
     */
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };
    private Integer mSensorOrientation;
//    private String mNextVideoAbsolutePath;
    private String mMP4VideoFileName, mAVIVideoFileName, mDumpFolder;
    private CaptureRequest.Builder mPreviewBuilder;
    private ImageReader mImageReaderPreview;

    public static Camera2VideoFragment newInstance() {
        return new Camera2VideoFragment();
    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_video, container, false);
    }

    private boolean bDumpRawFiles = false;
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mButtonVideo = (Button) view.findViewById(R.id.video);
        mButtonVideo.setOnClickListener(this);
//        view.findViewById(R.id.info).setOnClickListener(this);
        mDumpRawCheckBox = (CheckBox)view.findViewById(R.id.dump_raw);
        mDumpRawCheckBox.setChecked(bDumpRawFiles);
        mDumpRawCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){

            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                bDumpRawFiles = isChecked;
            }
        });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorManager = (SensorManager)getActivity().getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
        registerSensorManagerListener();
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        unregisterSensorManagerListener();
        super.onPause();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.video: {
                if (mIsRecordingVideo) {
                    stopRecordingVideo();
                } else {
                    startRecordingVideo();
                }
                break;
            }
//            case R.id.info: {
//                Activity activity = getActivity();
//                if (null != activity) {
//                    new AlertDialog.Builder(activity)
//                            .setMessage(R.string.intro_message)
//                            .setPositiveButton(android.R.string.ok, null)
//                            .show();
//                }
//                break;
//            }
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationale(String[] permissions) {
        for (String permission : permissions) {
            if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Requests permissions needed for recording video.
     */
    private void requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.length == VIDEO_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        break;
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
     */
    @SuppressWarnings("MissingPermission")
    private void openCamera(int width, int height) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
            return;
        }
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) {
            return;
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            String cameraId = manager.getCameraIdList()[0];

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            //fps
            android.util.Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            for(android.util.Range<Integer> range:fpsRanges){
                if(range.getLower()== EXPECTED_FPS && range.getUpper() == EXPECTED_FPS){
                    mFPSRange = Range.create(range.getLower(), range.getUpper());
                    break;
                }
            }
            Log.d(TAG,"FPS:"+mFPSRange);

            Integer value = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            if(value != null) {
                if(value.intValue() == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN) {
                    Log.d(TAG,"Sensor timestamp source is unknown");
                } else if(value.intValue() == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                    Log.d(TAG, "Sensor timestamp source is realtime");
                } else {
                    Log.d(TAG,"Sensor timestamp source unknown (" + value.intValue() + ")");
                }
            }
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);
            Log.i(TAG, "Video size:"+mVideoSize.toString()+", preview size:"+mPreviewSize.toString());

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            configureTransform(width, height);
//            mMediaRecorder = new MediaRecorder();
            mMediaCodecWrapper = new CameraRecordingStream();
            manager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_LONG).show();
            activity.finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Start the camera preview.
     */
    private void startPreview() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            closePreviewSession();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface previewSurface = new Surface(texture);
            mPreviewBuilder.addTarget(previewSurface);

            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mFPSRange);

            mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mPreviewSession = session;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Activity activity = getActivity();
                            if (null != activity) {
                                Toast.makeText(activity, "Failed", Toast.LENGTH_LONG).show();
                            }
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Update the camera preview. {@link #startPreview()} needs to be called in advance.
     */
    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private void setUpMediaCodecWrapper(String outputFileName) {
        if(null != mMediaCodecWrapper)
            mMediaCodecWrapper.configure(mVideoSize, true,1250000 , outputFileName, mUsingInputSurface);

    }
    private void setUpMediaRecorder(String outputFileName) throws IOException {
        final Activity activity = getActivity();
        if (null == activity || null == mMediaRecorder) {
            return;
        }

        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(outputFileName);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        mMediaRecorder.prepare();
    }
/*
    private void setUpMediaCodecEncoder() {
        if(null == mBufferInfo)
            mBufferInfo = new MediaCodec.BufferInfo();
        if(null == mMediaCodec) {
            try {
                mMediaCodec = MediaCodec.createEncoderByType("video/avc");
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", mVideoSize.getWidth(), mVideoSize.getHeight());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1250000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        format.setInteger(MediaFormat.KEY_ROTATION, 90);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000 / 30);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

    }
    */
    private static MediaCodecInfo selectCodec(String mimeType) {
        MediaCodecInfo codecInfos[]= new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for ( MediaCodecInfo codecInfo :codecInfos) {
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
    private String DUMP_FOLDER = Environment.getExternalStorageDirectory().getAbsolutePath().concat(File.separator).concat("ArcAR_Dump").concat(File.separator);
    private String getDumpFolderPath(){
        String timeStamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        File dir = new File(DUMP_FOLDER.concat(timeStamp).concat(File.separator));
        if(!dir.exists())
            dir.mkdirs();
        return dir.getAbsolutePath().concat(File.separator);
    }

    private void startRecordingVideo() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        mDumpRawCheckBox.setEnabled(false);
        mDumpFolder = getDumpFolderPath();
        mAVIVideoFileName = mDumpFolder.concat(File.separator)+System.currentTimeMillis()+".avi";
        mMP4VideoFileName = mDumpFolder.concat(File.separator)+System.currentTimeMillis()+".mp4";
        Log.d(TAG, "save to dir:"+getDumpFolderPath());
        try {
            closePreviewSession();
            setUpMediaRecorder(mAVIVideoFileName);
            setUpMediaCodecWrapper(mMP4VideoFileName);
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            mImageReaderPreview = ImageReader.newInstance(mVideoSize.getWidth(), mVideoSize.getHeight(), ImageFormat.YUV_420_888, 2);
            mImageReaderPreview.setOnImageAvailableListener(mOnPreviewAvailableListener, mBackgroundHandler);

            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder
            if(null != mMediaRecorder) {
                Surface recorderSurface = mMediaRecorder.getSurface();
                surfaces.add(recorderSurface);
                mPreviewBuilder.addTarget(recorderSurface);
            }
            if(null != mMediaCodecWrapper) {
                if(mUsingInputSurface)
                    mMediaCodecWrapper.onConfiguringRequest(mPreviewBuilder, surfaces, false);
                mMediaCodecWrapper.setDataProvider(mCodecImageProvider);
            }
            //set up Surface for ImageReader
            Surface imageSurface = mImageReaderPreview.getSurface();
            surfaces.add(imageSurface);
            mPreviewBuilder.addTarget(imageSurface);

            //set fps
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mFPSRange);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // UI
                            mButtonVideo.setText(R.string.stop);
                            mIsRecordingVideo = true;
                            startTimestamp = -1;
                            // Start recording
                            if(null != mMediaRecorder)
                                mMediaRecorder.start();
                            if(null != mMediaCodecWrapper)
                                mMediaCodecWrapper.start();
                            //start dump sensor info
                            createSensorDumpFiles();
                        }
                    });
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Activity activity = getActivity();
                    if (null != activity) {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_LONG).show();
                    }
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }

    }

    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    private Lock closeLock = new java.util.concurrent.locks.ReentrantLock(true);
    private void stopRecordingVideo() {

        // UI
        mIsRecordingVideo = false;
        mButtonVideo.setText(R.string.record);
        mDumpRawCheckBox.setEnabled(true);
        // Stop recording
        if(null != mMediaRecorder) {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
        }
        closeLock.lock();
        if (null != mImageReaderPreview) {
            mImageReaderPreview.close();
            mImageReaderPreview = null;
        }
        closeLock.unlock();

        if(null != mMediaCodecWrapper)
            mMediaCodecWrapper.stop();
        closeSensorDumpFiles();

        Activity activity = getActivity();
        if (null != activity) {
            Toast.makeText(activity, "Video saved: " + mDumpFolder,
                    Toast.LENGTH_LONG).show();
            Log.d(TAG, "Video saved: " + mDumpFolder);
        }
        mDumpFolder = null;
        startPreview();
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    public static class ConfirmationDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.permission_request)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FragmentCompat.requestPermissions(parent, VIDEO_PERMISSIONS,
                                    REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    parent.getActivity().finish();
                                }
                            })
                    .create();
        }

    }
    private long startTimestamp = -1;
    private final ImageReader.OnImageAvailableListener mOnPreviewAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            closeLock.lock();
            Image image = reader.acquireLatestImage();
            if (image == null)
                return;
            if(startTimestamp <= 0){
                startTimestamp = image.getTimestamp();
            }
//            Log.d(TAG, "Image timestamp:"+image.getTimestamp());
            if(mImageTimestampFW != null)
                mImageTimestampFW.println(image.getTimestamp());

//            Image.Plane Y = image.getPlanes()[0];
//            Image.Plane U = image.getPlanes()[1];
//            Image.Plane V = image.getPlanes()[2];
//
//            int Yb = Y.getBuffer().remaining();
//            int Ub = U.getBuffer().remaining();
//            int Vb = V.getBuffer().remaining();
//
//            ImageDataInfo info = getIdleImageData(Yb + Ub + Vb);
//            byte[] data = info.mImageData;//new byte[Yb + Ub + Vb];
//
//            Y.getBuffer().get(data, 0, Yb);
//            U.getBuffer().get(data, Yb, Ub);
//            V.getBuffer().get(data, Yb + Ub, Vb);
            long timestamp = image.getTimestamp();
            Rect crop = image.getCropRect();
            int format = image.getFormat();
            int width = crop.width();
            int height = crop.height();
            int size = width * height * ImageFormat.getBitsPerPixel(format) / 8;
            ImageDataInfo info = getIdleImageData(size);
            getDataFromImage(image, COLOR_FormatNV12, info.mImageData);
            info.mPresentationTimeUs = (timestamp - startTimestamp) / 1000;
            putImageData(info);
            if(bDumpRawFiles) {
                FileUtils.saveRawData(info.mImageData, mDumpFolder + "dumpImage_" + image.getTimestamp() + "_" + image.getWidth() + "x" + image.getHeight() + ".nv12");
            }

            image.close();
            closeLock.unlock();
        }
    };

    private LinkedBlockingQueue<ImageDataInfo> mIdleImageDataQueue = new LinkedBlockingQueue<>(15);
    private LinkedBlockingQueue<ImageDataInfo> mImageDataQueue = new LinkedBlockingQueue<>(15);
    public class ImageDataInfo{
        public byte[] mImageData;
        public long mPresentationTimeUs = -1;
    }

    private ImageDataInfo getIdleImageData(int dataSize) {
        ImageDataInfo info = mIdleImageDataQueue.poll();
        if(info == null || info.mImageData.length != dataSize){
            info = new ImageDataInfo();
            info.mImageData = new byte[dataSize];
        }

        return info;
    }

    public void putImageData(ImageDataInfo info){
        while(!mImageDataQueue.offer(info)) {//添加一个元素并返回true       如果队列已满，则返回false
            mImageDataQueue.poll();
            Log.d(TAG, "getImageData");
        }
    }

    public ImageDataInfo getImageData(){
            ImageDataInfo info =  mImageDataQueue.poll();
            Log.d(TAG, "getImageData");
        return info;
    }

    public void recycleImageData(ImageDataInfo data){
        data.mPresentationTimeUs = -1;
        mIdleImageDataQueue.offer(data);
    }
    CameraRecordingStream.DataProvider mCodecImageProvider = new  CameraRecordingStream.DataProvider() {

        @Override
        public ImageDataInfo getImageData() {
            return Camera2VideoFragment.this.getImageData();
        }

        @Override
        public void recycleImageData(ImageDataInfo data) {
            Camera2VideoFragment.this.recycleImageData(data);
        }
    };

    public void registerSensorManagerListener() {
        boolean result =   sensorManager.registerListener(mySensorListener,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_FASTEST);                              //最后一个参数用于控制传感器数据获取的频率，频率可以自由调整

        sensorManager.registerListener(mySensorListener,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED),
                SensorManager.SENSOR_DELAY_FASTEST);

        sensorManager.registerListener(mySensorListener,
                sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                SensorManager.SENSOR_DELAY_FASTEST);

        sensorManager.registerListener(mySensorListener,
                sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
                SensorManager.SENSOR_DELAY_FASTEST);


    }
    public void unregisterSensorManagerListener() {
        sensorManager.unregisterListener(mySensorListener);
        System.out.println("StateCollectService listener is unregistered ! ");
    }


    //下面是传感器变化监听的关键类
    private SensorEventListener mySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            switch(event.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    if(null != mAccelerometerFW)
                        mAccelerometerFW.println(event.timestamp+","+event.values[0]+","+event.values[1]+","+event.values[2]);
//                    Log.d("MainActivity","TYPE_ACCELEROMETER, "+event.values[0]+","+event.values[1]+","+event.values[2]+", timestamp(ns):"+event.timestamp+",curTime(ms):"+System.currentTimeMillis());
                    break;
                case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED:
                    if(null != mAccelerometerUncalibratedFW)
                        mAccelerometerUncalibratedFW.println(event.timestamp+","+event.values[0]+","+event.values[1]+","+event.values[2]);
//                    Log.d("MainActivity","TYPE_ACCELEROMETER_UNCALIBRATED, "+event.values[0]+","+event.values[1]+","+event.values[2]+", timestamp:"+event.timestamp);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    if(null != mGyroscopeFW) {
                        mGyroscopeFW.printf("%d,%f,%f,%f", event.timestamp, event.values[0],event.values[1], event.values[2]);//(event.timestamp+event.values[0]+","+event.values[1]+","+event.values[2]);
                        mGyroscopeFW.println();
                    }
//                    Log.d("MainActivity","TYPE_GYROSCOPE, "+event.values[0]+","+event.values[1]+","+event.values[2]+", timestamp:"+event.timestamp);
                    break;
                case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                    if(null != mGyroscopeUncalibratedFW)
                        mGyroscopeUncalibratedFW.println(event.timestamp+","+event.values[0]+","+event.values[1]+","+event.values[2]);
//                    Log.d("MainActivity","TYPE_GYROSCOPE_UNCALIBRATED, "+event.values[0]+","+event.values[1]+","+event.values[2]+", timestamp:"+event.timestamp);
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
        }
    };
    private SensorManager sensorManager;
    private String mAccelerometerName = "accelerometer.csv", mAccelerometerUncalibratedName = "accelerometerUncalibrated.csv",
            mGyroscopeName = "gyroscope.csv", mGyroscopeUncalibratedName = "gyroscopeUncalibrated.csv", mImageTimestampName = "image_timestamp.csv";
    private PrintWriter mAccelerometerFW = null, mAccelerometerUncalibratedFW = null,
            mGyroscopeFW = null, mGyroscopeUncalibratedFW = null, mImageTimestampFW = null;
    private void createSensorDumpFiles(){
        try{
            mAccelerometerFW = new PrintWriter(new BufferedWriter(new FileWriter(mDumpFolder+mAccelerometerName, true)));
            mAccelerometerFW.println("#timestamp [ns], a_RS_S_x [m s^-2], a_RS_S_y [m s^-2], a_RS_S_z [m s^-2]");

            mAccelerometerUncalibratedFW = new PrintWriter(new BufferedWriter(new FileWriter(mDumpFolder+mAccelerometerUncalibratedName, true)));
            mAccelerometerUncalibratedFW.println("#timestamp [ns], a_RS_S_x [m s^-2], a_RS_S_y [m s^-2], a_RS_S_z [m s^-2]");

            mGyroscopeFW = new PrintWriter(new BufferedWriter(new FileWriter(mDumpFolder+mGyroscopeName, true)));
            mGyroscopeFW.println("#timestamp [ns], w_RS_S_x [rad s^-1], w_RS_S_y [rad s^-1], w_RS_S_z [rad s^-1]");

            mGyroscopeUncalibratedFW = new PrintWriter(new BufferedWriter(new FileWriter(mDumpFolder+mGyroscopeUncalibratedName, true)));
            mGyroscopeUncalibratedFW.println("#timestamp [ns], w_RS_S_x [rad s^-1], w_RS_S_y [rad s^-1], w_RS_S_z [rad s^-1]");

            mImageTimestampFW = new PrintWriter(new BufferedWriter(new FileWriter(mDumpFolder+mImageTimestampName, true)));
            mImageTimestampFW.println("#timestamp [ns]");
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void closeSensorDumpFiles()
    {
        if(null != mAccelerometerFW){
            mAccelerometerFW.close();
            mAccelerometerFW = null;
        }
        if(null != mAccelerometerUncalibratedFW){
            mAccelerometerUncalibratedFW.close();
            mAccelerometerUncalibratedFW = null;
        }
        if(null != mGyroscopeFW){
            mGyroscopeFW.close();
            mGyroscopeFW = null;
        }
        if(null != mGyroscopeUncalibratedFW){
            mGyroscopeUncalibratedFW.close();
            mGyroscopeUncalibratedFW = null;
        }
        if(null != mImageTimestampFW){
            mImageTimestampFW.close();
            mImageTimestampFW = null;
        }
    }
    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;
    private static final int COLOR_FormatNV12 = 3;
    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                return true;
        }
        return false;
    }

    private static void getDataFromImage(Image image, int colorFormat, byte[]  outputData) {
        if (colorFormat != COLOR_FormatI420 && colorFormat != COLOR_FormatNV21 && colorFormat != COLOR_FormatNV12) {
            throw new IllegalArgumentException("only support COLOR_FormatI420 " + "and COLOR_FormatNV21");
        }
        if (!isImageFormatSupported(image)) {
            throw new RuntimeException("can't convert Image to byte array, format " + image.getFormat());
        }
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Log.d(TAG, "crop top:"+crop.top+",left:"+crop.left+", bottom:"+crop.bottom+", right:"+crop.right);
        Image.Plane[] planes = image.getPlanes();
        int size = width * height * ImageFormat.getBitsPerPixel(format) / 8;
        if(outputData == null || outputData.length != size) {
            throw new RuntimeException("Output data bytes is null or length is unequal to " + size);
        }
//        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
//         Log.v(TAG, "get data from " + planes.length + " planes");
        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = width * height;
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    } else if (colorFormat == COLOR_FormatNV12) {
                        channelOffset = width * height;
                        outputStride = 2;
                    }
                    break;
                case 2:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = (int) (width * height * 1.25);
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height;
                        outputStride = 2;
                    } else if (colorFormat == COLOR_FormatNV12) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    }
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
//            {
//                Log.v(TAG, "plane "+i+" pixelStride " + pixelStride);
//                Log.v(TAG, "plane "+i+" rowStride " + rowStride);
//                Log.v(TAG, "plane "+i+" width " + width);
//                Log.v(TAG, "plane "+i+" height " + height);
//                Log.v(TAG, "plane "+i+" buffer size " + buffer.remaining());
//            }
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(outputData, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        outputData[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
//             Log.v(TAG, "Finished reading data from plane " + i);
        }
//        return data;
    }
}

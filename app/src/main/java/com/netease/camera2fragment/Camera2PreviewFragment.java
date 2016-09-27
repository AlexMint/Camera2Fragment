package com.netease.camera2fragment;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.TranslateAnimation;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by hzchenggang on 2016/9/18.
 */
public class Camera2PreviewFragment extends Fragment{

    private static final String TAG = "Camera2PreviewFragment";
    private static final int STATE_PREVIEW = 0;
    private int mState = STATE_PREVIEW;
    private Handler mPreviewHandler;
    private HandlerThread mHandlerThread;
    private ImageReader mImageReader;
    private Size mPreviewSize;
    private String mCameraID;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private AutoFitTextureView mPreviewView;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mPreviewBuilder;
    private CameraCaptureSession mSession;
    private Surface mSurface;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0,90);
        ORIENTATIONS.append(Surface.ROTATION_90,0);
        ORIENTATIONS.append(Surface.ROTATION_180,270);
        ORIENTATIONS.append(Surface.ROTATION_270,180);
    }

    public static Camera2PreviewFragment newInstance(){
//        Log.i(TAG,"001 newInstance");
        Camera2PreviewFragment fragment = new Camera2PreviewFragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
//        Log.i(TAG,"002 onCreateView");
        return inflater.inflate(R.layout.camera2preview_fragment,null);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPreviewView = (AutoFitTextureView)view.findViewById(R.id.texture);
//        Log.i(TAG,"003 onViewCreated");
    }

    @Override
    public void onResume() {
        super.onResume();
//        Log.i(TAG,"004 onResume");
        initLooper();
        if(mPreviewView.isAvailable()){
            openCamera(mPreviewView.getWidth(),mPreviewView.getHeight());
        }else {
//            Log.i(TAG,"setSurfaceTextureListener");
            mPreviewView.setSurfaceTextureListener(mSurfacetextureListener);
        }
    }
    @Override
    public void onPause() {
        super.onPause();
//        Log.i(TAG,"onPause");
        closeCamera();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
    private void initLooper(){
        mHandlerThread = new HandlerThread("Camera_2");
        mHandlerThread.start();
        mPreviewHandler = new Handler(mHandlerThread.getLooper());
    }

    private void openCamera(int width,int height){
//        Log.i(TAG,"006 openCamera");
        setUpCameraOutputs(width, height);
        configureTransform(width,height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraID, DeviceStateCallback, mPreviewHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }
    private void closeCamera(){
//        Log.i(TAG,"closeCamera");
        mCameraOpenCloseLock.release();
        mCameraDevice.close();
        mCameraDevice = null;
    }
    private CameraDevice.StateCallback DeviceStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice camera) {
//            Log.i(TAG,"CameraDevice.StateCallback opened");
            mCameraOpenCloseLock.release();
            mCameraDevice = camera;
            try {
                createCameraPreviewSession();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
//            Log.i(TAG,"CameraDevice.StateCallback Disconnected");
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
//            Log.i(TAG,"CameraDevice.StateCallback onError");
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }
    };

    private void createCameraPreviewSession() throws CameraAccessException {
//        Log.i(TAG,"createCameraPreviewSession");
        initSurface();
        mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        mPreviewBuilder.addTarget(mSurface);
        mState = STATE_PREVIEW;
        mCameraDevice.createCaptureSession(Arrays.asList(mSurface, mImageReader.getSurface()), mSessionPreviewStateCallback, mPreviewHandler);
    }


    private CameraCaptureSession.StateCallback mSessionPreviewStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {
//            Log.d(TAG, " CameraCaptureSession.StateCallback onConfigured");
            if (null == mCameraDevice) {
                return;
            }
            mSession = session;
            try {
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                session.setRepeatingRequest(mPreviewBuilder.build(), mSessionCaptureCallback, mPreviewHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
//            Log.d(TAG, " CameraCaptureSession.StateCallback onConfigureFailed");
            Activity activity = getActivity();
            if (null != activity) {
                Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
            }
        }
    };


    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
//            Log.i(TAG,"CameraCaptureSession.CaptureCallback onCaptureCompleted");
            mSession = session;

        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
//            Log.i(TAG,"CameraCaptureSession.CaptureCallback onCaptureProgressed");
            mSession = session;
        }

    };

    private void initSurface() {
//        Log.i(TAG,"initSurface");
        SurfaceTexture texture = mPreviewView.getSurfaceTexture();
        assert texture != null;
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        mSurface = new Surface(texture);
    }
    
    private void configureTransform(int viewWidth,int viewHeight){
//        Log.i(TAG,"configureTransform");
        Activity activity = getActivity();
        if (null == mPreviewView || null == mPreviewSize || null == activity) {
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
        mPreviewView.setTransform(matrix);
    }
    private void setUpCameraOutputs(int width,int height){
//        Log.i(TAG,"setUpCameraOutputs:"+width+","+height);
        Activity activity = getActivity();
        CameraManager cameraManager = (CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraID = "0";
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraID);

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size largest;
            largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                    new CompareSizesByArea());
//            Size[] sizses = map.getOutputSizes(ImageFormat.YUV_420_888);
//            for (Size mms : sizses   ) {
//                Log.i(TAG,"ss:"+mms.toString());
//            }
            initImageReader(largest,ImageFormat.YUV_420_888);
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, largest);
            int orientation = getResources().getConfiguration().orientation;
            if(orientation == Configuration.ORIENTATION_LANDSCAPE){
//                Log.i(TAG,"setUpCameraOutputs:LANDSCAPE");
                mPreviewView.setAspectRatio(mPreviewSize.getWidth(),mPreviewSize.getHeight());
            }else {
//                Log.i(TAG,"setUpCameraOutputs:PORTRAIT");
                mPreviewView.setAspectRatio(mPreviewSize.getHeight(),mPreviewSize.getWidth());
            }

            mCameraID = cameraID;
        }catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    private void initImageReader(Size size,int format){
//        Log.i(TAG,"initImageReader");
        mImageReader = ImageReader.newInstance(size.getWidth(),size.getHeight(),format,7);
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListner,mPreviewHandler);
    }

    private Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<Size>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return choices[0];
        }
    }
    private ImageReader.OnImageAvailableListener mOnImageAvailableListner =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
//                    Log.i(TAG,"ImageReader.OnImageAvailableListener onImageAvailable");
                    Image image = imageReader.acquireNextImage();
//                    Log.i(TAG,"image:"+image.getWidth()+","+image.getHeight()+","+image.getTimestamp());
                    image.close();
                }
            };
    private TextureView.SurfaceTextureListener mSurfacetextureListener =
            new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
//            Log.i(TAG,"005 onSurfaceTextureAvailable:"+width+","+height);
            openCamera(width,height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
//            Log.i(TAG,"onSurfaceTextureSizeChanged:"+width+","+height);
            configureTransform(width,height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
//            Log.i(TAG,"onSurfaceTextureDestroyed");
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
//            Log.i(TAG,"onSurfaceTextureUpdated");
        }
    };

}

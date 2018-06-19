package com.example.saisai.record;


import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;

import com.example.saisai.ffmepgstreamer.Jni;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Created by saisai on 2018/6/5 0005.
 */

public class CameraWrapper {
    public static final int IMAGE_HEIGHT=1080;
    public static final int IMAGE_WIDTH=1920;
    private static final String TAG="CameraWrapper";
    private static final boolean DEBUG=true;
    private static CameraWrapper mCameraWrapper;



    private int openCameraId=Camera.CameraInfo.CAMERA_FACING_FRONT;
    private Camera mCamera;
    private boolean mIsPreviewing=false;
    private float mPreviewRate =-1.0f;
    private Camera.Parameters mCameraParameters;
    private CameraPreviewCallback mCameraPreviewCallback;
    private boolean isBlur=false;
    Camera.PreviewCallback previewCallback;

    private CameraWrapper(){}

    public static CameraWrapper getInstance(){
        if(mCameraWrapper==null){
            synchronized (CameraWrapper.class){
                if(mCameraWrapper==null){
                    mCameraWrapper=new CameraWrapper();
                }
            }
        }
        return mCameraWrapper;
    }

    private static String getSaveFilePath(String fileName){
        StringBuilder fullPath=new StringBuilder();
        fullPath.append(FileUtils.getExternalStorageDirectory());
        fullPath.append(FileUtils.getMainDirName());
        fullPath.append("/video2/");
        fullPath.append(fileName);
        fullPath.append(".mp4");

        String string=fullPath.toString();
        File file=new File(string);
        File parentFile = file.getParentFile();
        if (!parentFile.exists()) {
            parentFile.mkdirs();
        }
        return string;
    }

    public void switchCameraId(){
        if (openCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
            openCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        } else {
            openCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        }
    }

    public void doOpenCamera(CamOpenOverCallback callback){

        Log.i(TAG,"Camera open...");
        int numCameras=Camera.getNumberOfCameras();
        Camera.CameraInfo info=new Camera.CameraInfo();
        for(int i=0;i<numCameras;i++){
            Camera.getCameraInfo(i,info);
            if(info.facing==openCameraId){
                mCamera = Camera.open(i);
                break;
            }
        }
        if(mCamera==null){
            throw new RuntimeException("unable to open camera");
        }
        Log.i(TAG,"Camera open over...");
        callback.cameraHasOpened();
    }

    public void doStartPreview(SurfaceHolder holder,float previewRate){
        Log.i(TAG,"doStartPreview...");
        if(mIsPreviewing){
            this.mCamera.stopPreview();
            return;
        }

        try{
            this.mCamera.setPreviewDisplay(holder);
        }catch (IOException e){
            e.printStackTrace();
        }
        initCamera();
    }

    public void doStartPreview(SurfaceTexture surface){

        Log.i(TAG,"doStartPreview()");
        if(mIsPreviewing){
            this.mCamera.stopPreview();
            return;
        }

        try {
            this.mCamera.setPreviewTexture(surface);
        } catch (IOException e) {
            e.printStackTrace();
        }
        initCamera();
    }

    public void doStopCamera(){
        Log.i(TAG,"doStopCamera");
        if(this.mCamera!=null){
            if(mCameraPreviewCallback!=null){
                mCameraPreviewCallback.close();
            }
            this.mCamera.setPreviewCallback(null);
            this.mCamera.stopPreview();
            this.mIsPreviewing=false;
            this.mPreviewRate=-1f;
            this.mCamera.release();
            this.mCamera=null;
        }
    }

    private void initCamera() {

        if(this.mCamera!=null){
            this.mCameraParameters = this.mCamera.getParameters();
            this.mCameraParameters.setPreviewFormat(ImageFormat.NV21);
            this.mCameraParameters.setFlashMode("off");
            this.mCameraParameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
            this.mCameraParameters.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
            this.mCameraParameters.setPreviewSize(IMAGE_WIDTH,IMAGE_HEIGHT);

            mCameraPreviewCallback = new CameraPreviewCallback();
            mCamera.setPreviewCallback(mCameraPreviewCallback);
            List<String> focusModes=this.mCameraParameters.getSupportedFocusModes();
            if(focusModes.contains("continuous-video")){
                this.mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            this.mCamera.setParameters(this.mCameraParameters);
            this.mCamera.startPreview();

            this.mIsPreviewing=true;

        }
    }

    public void setBlur(boolean blur){
        isBlur=blur;
    }

    public void setPreviewCallback(Camera.PreviewCallback callback){
        previewCallback=callback;
    }


    public interface CamOpenOverCallback {
        public void cameraHasOpened();
    }

    class CameraPreviewCallback implements Camera.PreviewCallback{

        private CameraPreviewCallback(){
            startRecording();
        }

        public void close(){
            stopRecording();
        }

        private void stopRecording() {
            MediaMuxerRunnable.stopMuxer();
        }

        private void startRecording() {
            MediaMuxerRunnable.startMuxer();
        }

        boolean isPreview=false;

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
//            try {
//            if(!isPreview){
//                isPreview=true;
//                Jni.getInstance().stream(
//                        "udp://127.0.0.1:8888",
//                        "udp://127.0.0.1:8889",
//                        Environment.getExternalStorageDirectory().getAbsolutePath()+"/aaa.ts");
//            }
//                Log.e("onPreviewFrame", "" + data.length);
                MediaMuxerRunnable.addVideoFrameData(data);
//            }catch (Exception e){
//
//            }
        }
    }

}

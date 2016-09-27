package com.netease.camera2fragment;

import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

import java.security.PrivateKey;

/**
 * Created by hzchenggang on 2016/9/18.
 */
public class ImageTreatment implements Runnable {

    private ImageReader mImageReader;
    private static String TAG = "ImageTreatment";

    public ImageTreatment(ImageReader imageReader){
        mImageReader = imageReader;
    }
    @Override
    public void run() {
        Image image = mImageReader.acquireNextImage();
        Log.i(TAG,"stamp:"+image.getTimestamp());
        Log.i(TAG,"size:"+image.getWidth()+","+image.getHeight());

        image.close();
    }
}

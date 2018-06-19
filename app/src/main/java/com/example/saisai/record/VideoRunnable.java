package com.example.saisai.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.example.saisai.ffmepgstreamer.Jni;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Vector;

/**
 * Created by saisai on 2018/6/6 0006.
 */

public class VideoRunnable extends Thread {

    public static final boolean DEBUG = false;
    private static final String TAG = "VideoRunnable";
    private static final boolean VERBOSE = false;//lots of logging
    //parameters for the encoder
    private static final String MIME_TYPE = "video/avc";//H.264 Advanced Video
    private static final int FRAME_RATE = 30;//15fps
    private static final int IFRAME_INTERNAL = 1;//10 between
    //I-frames
//    private static final int TIMEOUT_USEC = -1;
    private static final int TIMEOUT_USEC = 10000;
    private static final int COMPERSS_RATIO = 256;
    private static final int BIT_RATE = CameraWrapper.IMAGE_HEIGHT * CameraWrapper.IMAGE_WIDTH * 3 * 8 * FRAME_RATE / COMPERSS_RATIO;//bit rate CameraWrapper
    private final Object lock = new Object();
    byte[] mFrameData;
    Vector<byte[]> frameBytes;
    private int mWidth;
    private int mHeight;
    private MediaCodec mMediaCodec;
    private MediaCodec.BufferInfo mBufferInfo;
    private int mColorFormat;
    private long mStartTime = 0;
    private volatile boolean isExit = false;
    private WeakReference<MediaMuxerRunnable> mediaMuxerRunnable;
    private MediaFormat mediaFormat;
    private MediaCodecInfo codecInfo;
    private volatile boolean isStart = false;
    private volatile boolean isMuxerReady = false;
    private byte[] m_info;
    private DatagramSocket socket;
    private byte[] config;
    private BufferedOutputStream bos;

    public VideoRunnable(int mWidth, int mHeight, WeakReference<MediaMuxerRunnable> mediaMuxerRunnable) {
        this.mWidth = mWidth;
        this.mHeight = mHeight;
        this.mediaMuxerRunnable = mediaMuxerRunnable;
        frameBytes = new Vector<byte[]>();

        prepare();
    }

    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {

        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        if (DEBUG) Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName()
                + " / " + mimeType);

        return 0;
    }

    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            //these are the formats we know how to handle fo this test
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

    private static void NV21toI420SemiPlanar(byte[] nv21bytes, byte[] i420bytes, int width, int height) {
        System.arraycopy(nv21bytes, 0, i420bytes, 0, width * height);
        for (int i = width * height; i < nv21bytes.length; i += 2) {
            i420bytes[i] = nv21bytes[i + 1];
            i420bytes[i + 1] = nv21bytes[i];
        }
    }

    public void exit() {
        isExit = true;
    }

    public void add(byte[] data) {
        if (frameBytes != null && isMuxerReady) {
            frameBytes.add(data);
        }
    }

    private void prepare() {
        if (DEBUG) Log.i(TAG, "VideoENcoder()");
        mFrameData = new byte[this.mWidth * this.mHeight * 3 / 2];
        mBufferInfo = new MediaCodec.BufferInfo();
        codecInfo = selectCodec(MIME_TYPE);
        if (codecInfo == null) {
            if (DEBUG) Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
            return;
        }
        if (VERBOSE)
            if (DEBUG) Log.d(TAG, "found codec: " + codecInfo.getName());
        mColorFormat = selectColorFormat(codecInfo, MIME_TYPE);
        if (VERBOSE)
            if (DEBUG) Log.d(TAG, "found colorFormat: " + mColorFormat);
        mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, this.mWidth, this.mHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERNAL);
        if (VERBOSE)
            if (DEBUG) Log.d(TAG, "format: " + mediaFormat);
    }

    private void startMediaCodec() throws IOException {
        mMediaCodec = MediaCodec.createByCodecName(codecInfo.getName());
        mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();

        isStart = true;
    }

    private void stopMediaCodec() {
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
        isStart = false;
        if (DEBUG) Log.e("angcyo-->", "stop video 录制...");
    }

    public synchronized void restart() {
        isStart = false;
        isMuxerReady = false;
        frameBytes.clear();
    }

    public void setMuxerReady(boolean muxerReady) {
        synchronized (lock) {
            if (DEBUG)
                Log.e("angcyo-->", Thread.currentThread().getId() + " video -- setMuxerReady..." + muxerReady);
            isMuxerReady = muxerReady;
            lock.notifyAll();
        }
    }

    private void encodeFrame(byte[] input) {

        //region 保存成mp4
        if (VERBOSE)
            if (DEBUG) Log.i(TAG, "encodeFrame()");
        NV21toI420SemiPlanar(input, mFrameData, this.mWidth, this.mHeight);

        ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
        if (VERBOSE)
            if (DEBUG) Log.i(TAG, "inputBufferIndex-->" + inputBufferIndex);
        if (inputBufferIndex >= 0) {
            long endTime = System.nanoTime();
            long ptsUsec = (endTime - mStartTime) / 1000;
            if (VERBOSE)
                if (DEBUG) Log.i(TAG, "resentationTime: " + ptsUsec);
            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.put(mFrameData);

            mMediaCodec.queueInputBuffer(inputBufferIndex, 0, mFrameData.length, System.nanoTime() / 1000, 0);

        } else {
            if (VERBOSE)
                if (DEBUG) Log.d(TAG, "input buffer not available");
        }

        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
        if (VERBOSE)
            if (DEBUG) Log.i(TAG, "outputBufferIndex-->" + outputBufferIndex);
        do {
//            Log.e(TAG, "encodeFrame while=========");


            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = mMediaCodec.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mMediaCodec.getOutputFormat();
                MediaMuxerRunnable mediaMuxerRunnable = this.mediaMuxerRunnable.get();
                if (mediaMuxerRunnable != null) {
                    mediaMuxerRunnable.addTrackIndex(MediaMuxerRunnable.TRACK_VIDEO, newFormat);
                }

                if (DEBUG)
                    Log.e("angcyo-->", "添加视轨 INFO_OUTPUT_FORMAT_CHANGED " + newFormat.toString());
            } else if (outputBufferIndex < 0) {

            } else {
                if (VERBOSE)
                    if (DEBUG) Log.d(TAG, "perform encoding");
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                if (outputBuffer == null) {
                    throw new RuntimeException("encoderOutputBuffer " + outputBufferIndex + " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {

                    MediaFormat format=mMediaCodec.getOutputFormat();
                    ByteBuffer spsb=format.getByteBuffer("csd-0");
                    ByteBuffer ppsb=format.getByteBuffer("csd-1");
                    byte[] sps=new byte[spsb.capacity()];
                    byte[] pps=new byte[ppsb.capacity()];
                    spsb.get(sps);
                    ppsb.get(pps);
                    config=new byte[sps.length+pps.length];

                    System.arraycopy(sps,0,config,0,sps.length);
                    System.arraycopy(pps,0,config,sps.length,pps.length);

//                    config = new byte[mBufferInfo.size];
//                    byte[] temp = new byte[mBufferInfo.size];
//                    outputBuffer.get(temp);
//                    System.arraycopy(temp,0,config,0,temp.length);

                    //the codec config data was pulled out fed to the muxer when we got
                    //the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
                    if (VERBOSE) if (DEBUG) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;

                }
                if (mBufferInfo.size != 0) {
                    MediaMuxerRunnable mediaMuxerRunnable = this.mediaMuxerRunnable.get();
                    if (mediaMuxerRunnable != null && !mediaMuxerRunnable.isVideoAdd()) {
                        MediaFormat newFormat = mMediaCodec.getOutputFormat();
                        if (DEBUG) Log.e("angcyo-->", "添加视轨  " + newFormat.toString());
                        mediaMuxerRunnable.addTrackIndex(MediaMuxerRunnable.TRACK_VIDEO, newFormat);
                    }
                    //adjust the ByteBuffer values to match
                    outputBuffer.position(mBufferInfo.offset);
                    outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);


                    byte[] array = new byte[mBufferInfo.size];
                    outputBuffer.get(array);

//                    Log.e(TAG,"array length = "+mBufferInfo.size);
//
//                    if(mediaMuxerRunnable!=null && mediaMuxerRunnable.isMuxerStart()){
//                        mediaMuxerRunnable.addMuxerData(new MediaMuxerRunnable.MuxerData(MediaMuxerRunnable.TRACK_VIDEO,outputBuffer,mBufferInfo));
//                    }
                    byte[] temp;
                    //是否是关键帧
                    if(mBufferInfo.flags==1){

                        temp=new byte[mBufferInfo.size+config.length];
                        System.arraycopy(config,0,temp,0,config.length);
                        System.arraycopy(array,0,temp,config.length,array.length);
//                        Log.e(TAG,"key frame");
                        Jni.getInstance().write(
                                0,
                                1,
                                video_pts,
                                video_pts,
                                temp,
                                temp.length);
                    }else{
                        temp=new byte[mBufferInfo.size];
                        System.arraycopy(array,0,temp,0,array.length);
                        Jni.getInstance().write(
                                0,
                                0,
                                video_pts,
                                video_pts,
                                temp,
                                temp.length);
                    }

                    inc++;
                    video_pts = inc*video_pts_increment;

                    if (VERBOSE) {
                        if (DEBUG) Log.d(TAG, "sent " + mBufferInfo.size + "  frameBytes to muxer");
                    }

                }

                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
            }
            outputBufferIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);


        } while (outputBufferIndex >= 0);
        //endregion

    }

    int video_pts_increment = 90000 / FRAME_RATE;   //用一秒钟除以帧率,得到每一帧应该耗时是多少，单位是 timescale单位
    int inc=0;
    int video_pts = 0;


    public int offerEncoder(byte[] input)
    {
        byte[] output = new byte[mWidth*mHeight*3/2];
        int pos = 0;
        long pts=0;
        long dts=0;

        NV21toI420SemiPlanar(input, mFrameData, mWidth, mHeight);
        try {
            ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
            ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0)
            {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(mFrameData);
                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, mFrameData.length, System.nanoTime() / 1000, 0);
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo,0);

            while (outputBufferIndex >= 0)
            {
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                byte[] outData = new byte[bufferInfo.size];
                outputBuffer.get(outData);

                if(m_info != null)
                {
                    System.arraycopy(outData, 0,  output, pos, outData.length);
                    pos += outData.length;

                }

                else //保存pps sps 只有开始时 第一个帧里有， 保存起来后面用
                {
                    ByteBuffer spsPpsBuffer = ByteBuffer.wrap(outData);
                    if (spsPpsBuffer.getInt() == 0x00000001)
                    {
                        m_info = new byte[outData.length];
                        System.arraycopy(outData, 0, m_info, 0, outData.length);
                    }
                    else
                    {
                        return -1;
                    }
                }

                pts=bufferInfo.presentationTimeUs;
                dts=bufferInfo.presentationTimeUs;

                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            }

            if(output[4] == 0x65) //key frame   编码器生成关键帧时只有 00 00 00 01 65 没有pps sps， 要加上
            {
                System.arraycopy(output, 0,  mFrameData, 0, pos);
                System.arraycopy(m_info, 0,  output, 0, m_info.length);
                System.arraycopy(mFrameData, 0,  output, m_info.length, pos);
                pos += m_info.length;
            }

//            Jni.getInstance().write(0,pts,dts,output,pos);
            SocketAddress address = new InetSocketAddress("192.168.0.194",10000);
            DatagramPacket packet=new DatagramPacket(output,pos,address);

            socket.send(packet);

        } catch (Throwable t) {
            t.printStackTrace();
        }

        return pos;
    }

    @Override
    public void run() {


        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }

        try {
            bos = new BufferedOutputStream(new FileOutputStream(Environment.getExternalStorageDirectory().getAbsoluteFile()+"/video.264"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        while (!isExit) {
            if (!isStart) {
                stopMediaCodec();

                if (!isMuxerReady) {
                    synchronized (lock) {
                        try {
                            if (DEBUG) Log.e("ang-->", "video -- 等待混合器准备 ...");
                            lock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                if (isMuxerReady) {
                    try {
                        if (DEBUG) Log.e("angcyo-->", "video -- startMediaCodec...");
                        startMediaCodec();
                    } catch (IOException e) {
                        isStart = false;
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                    }
                }
            } else if (!frameBytes.isEmpty()) {
//                Log.e(TAG, "encodeFrame=========");
                byte[] bytes = this.frameBytes.remove(0);

                try {
                    encodeFrame(bytes);
//                    offerEncoder(bytes);
                } catch (Exception e) {
                    if (DEBUG) Log.e("angcyo-->", "编码视频（Video）数据 失败");
                    e.printStackTrace();
                }
            }
        }
        if (DEBUG) Log.e("angcyo", "Video 录制线程 退出。。。");
    }

}

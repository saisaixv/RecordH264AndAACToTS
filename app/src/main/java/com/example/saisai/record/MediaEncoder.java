package com.example.saisai.record;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Created by saisai on 2018/6/5 0005.
 */

public abstract class MediaEncoder implements Runnable {
    private static final boolean DEBUG = true;
    private static final String TAG = "MediaEncoder";

    protected static final int TIMEOUT_USEC = 10000;//10msec
    protected static final int MSG_FRAME_AVAILABLE = 1;
    protected static final int MSG_STOP_RECORDING = 9;


    public interface MediaEncoderListener {
        public void onPrepared(MediaEncoder encoder);

        public void onStopped(MediaEncoder encoder);
    }

    protected final Object mSync = new Object();

    /**
     * flag that indicate this encoder is capturing now
     */
    protected volatile boolean mIsCapturing;

    /**
     * flag that indicate the frame data will be available soon
     */
    private int mRequestDrain;

    /**
     * flag to request stop capturing
     */
    protected volatile boolean mRequestStop;

    /**
     * flag that indicate encoder received EOS(end of stream)
     */
    protected boolean mIsEOS;

    /**
     * flag the indicate the muxer is running
     */
    protected boolean mMuxerStarted;

    /**
     * Track Number
     */
    protected int mTrackIndex;

    /**
     * MediaCodec instance for encoding
     */
    protected MediaCodec mMediaCodec;

    /**
     * Weak refarence of MediaMuxerWarapper instance
     */
    protected final WeakReference<MediaMuxerWrapper> mWeakMuxer;

    /**
     * BufferInfo instance for dequeuing
     */
    private MediaCodec.BufferInfo mBufferInfo;

    protected final MediaEncoderListener mListener;

    public MediaEncoder(final MediaMuxerWrapper muxer, final MediaEncoderListener listener) {
        if (listener == null) {
            throw new NullPointerException("MediaEncoderListener is null");
        }
        if (muxer == null) {
            throw new NullPointerException("MediaMuxerWrapper is null");
        }
        mWeakMuxer = new WeakReference<MediaMuxerWrapper>(muxer);
        muxer.addEncoder(this);
        mListener = listener;
        synchronized (mSync) {
            //create BUfferInfo here for effectiveness(to reduce GC)
            mBufferInfo = new MediaCodec.BufferInfo();
            //wait for starting thread
            new Thread(this, getClass().getSimpleName()).start();
            try {
                mSync.wait();
            } catch (final InterruptedException e) {

            }
        }
    }

    public String getOutputPath() {
        MediaMuxerWrapper muxer = mWeakMuxer.get();
        return muxer != null ? muxer.getOutputPath() : null;
    }

    /**
     * the method to indicate frame data is soon available or already available
     *
     * @return return true if encoder is ready to encode
     */
    public boolean frameAvailableSoon() {
        synchronized (mSync) {
            if (!mIsCapturing || mRequestStop) {
                return false;
            }
            mRequestDrain++;
            mSync.notifyAll();
        }
        return true;
    }

    /**
     * encoding loop on private thread
     */
    @Override
    public void run() {
        synchronized (mSync) {
            mRequestStop = false;
            mRequestDrain = 0;
            mSync.notifyAll();
        }

        final boolean isRunning = true;
        boolean localRequestStop;
        boolean localRequestDrain;
        while (isRunning) {
            synchronized (mSync) {
                localRequestStop = mRequestStop;
                localRequestDrain = (mRequestDrain > 0);
                if (localRequestDrain)
                    mRequestDrain--;
            }

            if (localRequestStop) {
                drain();
                //request stop recording
                signalEndOfInputStream();
                //process output data again for EOS signale
                drain();
                //release all related objects
                release();
                break;
            }
            if(localRequestDrain){
                drain();
            }else {
                synchronized (mSync){
                    try {
                        mSync.wait();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }//end of while
        if(DEBUG) Log.d(TAG,"Encoder thread exiting");
        synchronized (mSync){
            mRequestStop=true;
            mIsCapturing=false;
        }
    }

    /**
     * Prepareing method for each sub class
     * this method should be implemented in sub class,so set this as abstract method
     * @throws IOException
     */
    /*package*/ abstract void prepare()throws IOException;

    /*package*/ void startRecording(){
        if(DEBUG) Log.v(TAG,"startRecording");
        synchronized (mSync){
            mIsCapturing=true;
            mRequestStop=false;
            mSync.notifyAll();
        }
    }

    /**
     * the method to request stop encoding
     */
    /*package*/ void stopRecording(){
        if(DEBUG) Log.v(TAG,"stopRecording");
        synchronized (mSync){
            if(!mIsCapturing || mRequestStop){
                return;
            }
            mRequestStop=true;//for rejecting newer frame
            mSync.notifyAll();
            //we can not know when the encoding and writing finish
            //so we return immediately after request to avoid delay of caller thread
        }
    }

    /**
     * Release all releated objects
     */
    protected void release() {
        if(DEBUG) Log.d(TAG,"release:");
        try {
            mListener.onStopped(this);
        }catch (final  Exception e){
            Log.e(TAG,"failed onStopped",e);
        }

        mIsCapturing=false;
        if(mMediaCodec!=null){

            try {
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            } catch (final Exception e) {
                Log.e(TAG, "failed releasing MediaCodec", e);
            }
        }
        if(mMuxerStarted){
            final MediaMuxerWrapper muxer=mWeakMuxer!=null?mWeakMuxer.get():null;
            if(muxer!=null){
                try {
                    muxer.stop();
                }catch (final Exception e){
                    Log.e(TAG,"failed stopping muxer", e);
                }

            }
        }
        mBufferInfo=null;
    }

    protected void signalEndOfInputStream() {
        if(DEBUG) Log.d(TAG,"sending EOS to encoder");
        //signalEndOfInputStream is only avairable for video encoding with surface
        //and equivalent sending a empty buffer with BUFFER_FLAG_END_OF_STREAM flag
        //mMediaCodec.signalEndOfInputStream();
        encode(null,0,getPTSUs());
    }

    /**
     * Method to set byte array to the MediaCodec encoder
     * @param buffer
     * @param length    length of byte array,zero means EOS
     * @param presentationTimeUs
     */
    protected void encode(final ByteBuffer buffer,final int length,final long presentationTimeUs) {
        if(!mIsCapturing) return;
        final ByteBuffer[] inputBuffers=mMediaCodec.getInputBuffers();

        while (mIsCapturing){
            final int inputBufferIndex=mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if(inputBufferIndex>=0){
                final ByteBuffer inputBuffer=inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                if(buffer!=null){
                    inputBuffer.put(buffer);
                }
                if(length<=0){
                    mIsEOS=true;
                    if(DEBUG) Log.i(TAG,"send BUFFER_FLAG_END_OF_STREAM");
                    mMediaCodec.queueInputBuffer(inputBufferIndex,0,0,presentationTimeUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }else {
                    mMediaCodec.queueInputBuffer(inputBufferIndex,0,length,presentationTimeUs,0);
                }
                break;
            }else if(inputBufferIndex==MediaCodec.INFO_TRY_AGAIN_LATER){
                //wait for MediaCodec encoder is ready to encode
                //nothing to do here becouse MediaCodec#dequeueInputBuffer(TIMEOUT_USEC)
                //will wait for maximum TIMEOUT_USEC(10msec) on each call

            }
        }
    }


    /**
     * drain encoded data and write them to muxer
     */
    private void drain() {

        if (mMediaCodec == null) {
            return;
        }
        ByteBuffer[] encoderOutputBuffers = mMediaCodec.getOutputBuffers();
        int encoderStatus, count = 0;
        final MediaMuxerWrapper muxer = mWeakMuxer.get();
        if (muxer == null) {
            Log.w(TAG, "muxer is unexpectedly null");
            return;
        }

        LOOP:
        while (mIsCapturing) {
            //get encoded data with maximum timeout duration of TIMEOUT_USEC(=10 msec)
            encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
                if (!mIsEOS) {
                    if (++count > 5)
                        break LOOP;
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (DEBUG) {
                    Log.v(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                    //this should not come when encoding
                    encoderOutputBuffers = mMediaCodec.getOutputBuffers();
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (DEBUG) Log.v(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
                //this status indicate the output format of codec is changed
                //this should come only once before actual encoded data
                //but this status naver come on android 4.3 or less
                //and in  that case,you should treat when Mediacodec.BUFFER_FLAG_CODEC_CONFIG come
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                //get output format from codec and pass them to muxer
                //getOutputFormat should be called after INFO_OUTPUT_FORMAT_CHANGED otherwise creash
                final MediaFormat format = mMediaCodec.getOutputFormat();
                mTrackIndex = muxer.addTrack(format);
                mMuxerStarted = true;
                if (!muxer.start()) {
                    //we should wait until muxer is ready
                    synchronized (muxer) {
                        while (!muxer.isStarted())
                            try {
                                muxer.wait(100);
                            } catch (final InterruptedException e) {
                                break LOOP;
                            }
                    }
                }
            }else if(encoderStatus<0){
                //unexpected status
                if(DEBUG) Log.w(TAG,"drain:unexpected result from encoder#dequeueOutputBuffer: " + encoderStatus);
            }else {
                final ByteBuffer encodedData=encoderOutputBuffers[encoderStatus];
                if(encodedData==null){
                    //this never should come...may be a MediaCodec internal error
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }
                if((mBufferInfo.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){
                    //you should set output format to muxer here when you target android 4.3 or less
                    //but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
                    //there for we should expand and prepare output format from buffer data.
                    //this sample is for API》=18，just ignore this flag here
                    if(DEBUG) Log.d(TAG,"drain:BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size=0;
                }

                if(mBufferInfo.size!=0){
                    //encoded data is ready,clear waiting counter
                    count=0;
                    if(!mMuxerStarted){
                        //muxer is not ready...this will programing failure
                        throw new RuntimeException("drain:muxer hasn't started");
                    }
                    //write encoded data to muxer(need to adjust presentationTimeUs)
                    mBufferInfo.presentationTimeUs=getPTSUs();
                    muxer.writeSampleData(mTrackIndex,encodedData,mBufferInfo);
                    prevOutputPTSUs=mBufferInfo.presentationTimeUs;
                }
                //return buffer to encoder
                mMediaCodec.releaseOutputBuffer(encoderStatus,false);
                if((mBufferInfo.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0){

                    //when EOS come
                    mIsCapturing=false;
                    break;//out of while
                }
            }
        }


    }

    /**
     * previous presendationTimeUs for writing
     */
    private long prevOutputPTSUs=0;

    /**
     * get next encoding presentationTimeUs
     * @return
     */
    protected long getPTSUs() {
        long result=System.nanoTime()/1000L;
        //presendationTimeUs should be monotonic
        //otherwise muxer fail to write
        if(result < prevOutputPTSUs){
            result=(prevOutputPTSUs-result)+result;
        }
        return result;
    }
}

package com.example.saisai.ffmepgstreamer;

/**
 * Created by saisai on 2018/6/7 0007.
 */

public class Jni {
    static {
        System.loadLibrary("sffstreamer");
    }

    private static Jni instance = null;

    private Jni() {
    }

    public static Jni getInstance() {
        if (instance == null) {
            synchronized (Jni.class) {
                if (instance == null) {
                    instance = new Jni();
                }
            }
        }
        return instance;
    }

    public native int stream(String input_a, String input_v, String output);

    public native int createTSHandle(String ip,int port,String path);
    public native int udp(String ip,int port);
    public native int close();

//    public native int write(int codecID, long pts, long dts, byte[] data, int bytes,int keyFrame);
    public native int write(int codecID, int flag,long pts, long dts, byte[] data, int bytes);
}

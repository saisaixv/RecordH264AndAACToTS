package com.example.saisai.record;

import android.graphics.SurfaceTexture;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;

import com.example.saisai.ffmepgstreamer.Jni;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, CameraWrapper.CamOpenOverCallback {

    private CameraTexturePreview mCameraTexturePreview;
    private EditText edtHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        //隐藏标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        //隐藏状态栏
        //定义全屏参数
        int flag= WindowManager.LayoutParams.FLAG_FULLSCREEN;
        //设置当前窗体为全屏显示
        window.setFlags(flag, flag);


        setContentView(R.layout.activity_main);

        edtHost = findViewById(R.id.edt_host);
        edtHost.setText("192.168.10.125:10000");
        findViewById(R.id.btn_start).setOnClickListener(this);
        mCameraTexturePreview = (CameraTexturePreview)findViewById(R.id.texture);

//        Jni.getInstance().udp("127.0.0.1",10000);

//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//
//                    DatagramSocket socket=new DatagramSocket();
//                    byte[] buf={'1','1','2'};
//                    SocketAddress address = new InetSocketAddress("192.168.15.129",10000);
//                    for(;;){
//
//                        DatagramPacket packet=new DatagramPacket(buf,buf.length,address);
//                        socket.send(packet);
//                        Log.e("tag","snedto=========");
//                        Thread.sleep(100);
//                    }
//                } catch (SocketException e) {
//                    e.printStackTrace();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();

    }


    @Override
    public void onClick(View v) {

        Button btn=(Button)v;
        if(btn.getText().toString().equals("开始")){
            btn.setText("停止");
            String str=edtHost.getText().toString();
            if(str.equals("")){
                return;
            }

            String[] split = str.split(":");
            Jni.getInstance().createTSHandle(split[0],Integer.parseInt(split[1]),
                    Environment.getExternalStorageDirectory().toString()+"/main.ts");

            openCamera();
        }else{
            btn.setText("开始");

            Jni.getInstance().close();
            CameraWrapper.getInstance().doStopCamera();
        }
    }

    private void openCamera() {
        Thread openThread=new Thread(){
            @Override
            public void run() {
                CameraWrapper.getInstance().doOpenCamera(MainActivity.this);
            }
        };
        openThread.start();
    }

    @Override
    public void cameraHasOpened() {
        SurfaceTexture surface=this.mCameraTexturePreview.getSurfaceTexture();
        CameraWrapper.getInstance().doStartPreview(surface);
    }
}

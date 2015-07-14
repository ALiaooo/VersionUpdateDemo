package com.aliao.versionupdate;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;


/**
 * 多线程分段下载：
 * 一个下载按钮和一个进度条，点击下载按钮，开始下载apk，进度条实时更新下载进度,下载的文件存储到本地，下载完毕后自动启动安装apk。
 * http://blog.csdn.net/mad1989/article/details/38421465
 * http://www.cnblogs.com/hanyonglu/archive/2012/02/20/2358801.html
 * http://blog.csdn.net/wwj_748/article/details/20146869
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener{

    private static final String TAG = MainActivity.class.getSimpleName();

    private TextView mTvProgressMsg;

    private ProgressBar mProgressBar;

    private String apkPath;

    private long startTime;
    private long endTime;

    private final String FILE_NAME = "soufunland_android_20000_2.4.1.apk";
    private final int THREAD_NUM = 3;//线程数

//    String downloadUrl = "http://gdown.baidu.com/data/wisegame/91319a5a1dfae322/baidu_16785426.apk";
    private final String mDownloadUrl = "http://js.soufunimg.com/industry/land/appupdate/soufunland_android_20000_2.4.1.apk";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
    }

    private void initViews() {
        findViewById(R.id.btn_start_download).setOnClickListener(this);
        mProgressBar = (ProgressBar) findViewById(R.id.progressbar);
        mTvProgressMsg = (TextView) findViewById(R.id.tv_progress_msg);
    }

    Handler handler = new Handler(){
        @Override
        public void handleMessage(Message msg) {

            mProgressBar.setProgress(msg.getData().getInt("size"));
            float temp = (float)mProgressBar.getProgress() / (float)mProgressBar.getMax();

            Log.d(TAG, "temp:" + temp );
            int progress = (int) (temp * 100);
            if (progress == 100){
                endTime();
                Toast.makeText(MainActivity.this, "下载完毕！", Toast.LENGTH_SHORT).show();
                ReplaceLaunchApk(apkPath);
            }

            mTvProgressMsg.setText("下载进度:"+progress+"%");


        }
    };


    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.btn_start_download:
                doDownload();
                break;
        }
    }

    private void startTime(){
        startTime = System.nanoTime();  //开始时间
    }

    private void endTime(){
        endTime = System.nanoTime() - startTime; //消耗时间
        Log.d(TAG, "下载耗时：" + endTime / 1000000000 + "秒");
    }
    private void doDownload() {
        startTime();
        mProgressBar.setProgress(0);

        File file = getStorageDir("ApkDownload");
        if (!file.exists()){
            file.mkdir();
        }

        apkPath = file.getPath() + File.separator + FILE_NAME;


        DownloadManager downloadManager = new DownloadManager(THREAD_NUM, apkPath, mDownloadUrl);
        downloadManager.start();
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private File getStorageDir(String storageDir){
        String cachedPath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !Environment.isExternalStorageRemovable()){
            cachedPath = getExternalCacheDir().getPath();
        }else {
            cachedPath = getCacheDir().getPath();
        }
        return new File(cachedPath + File.separator + storageDir);
    }

    class DownloadManager extends Thread{

        private String downloadUrl;
        private String filePath;//保存文件路径地址
        private int blockSize;//每一个线程的下载量
        private int threadNum;//开启的线程数

        public DownloadManager(int threadNum, String filePath, String downloadUrl) {
            this.threadNum = threadNum;
            this.filePath = filePath;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public void run() {

            FileDownloadThread[] threads = new FileDownloadThread[threadNum];
            HttpURLConnection connection = null;
            try {
                URL url = new URL(downloadUrl);
                connection = (HttpURLConnection) url.openConnection();
                //读取下载文件总大小
                int fileSize = connection.getContentLength();
                if (fileSize <= 0){
                    throw new RuntimeException("Unknown file size");
                }
                mProgressBar.setMax(fileSize);//设置最大的长度为文件的总大小

                /**
                 * 计算每条线程下载的数据长度
                 * 文件大小是fileSize，总共n个线程，那每个线程下载多少数据呢？
                 * 1.n个线程平均分fileSize大小
                 * fileSize % threadNum用来判断fileSize是否能够整除threadNum，如果能整除即余数为0那么这个threaNum个线程就平均分这个fileSize，这n个线程每一个都下载fileSize/n长度的数据
                 * 2.如果不能整除，那就在整除以得到的数值上加1（不用在意为什么+1，加2加3都可以，只要比/（整除）得来的数值大就行）
                 * 这个blockSize的作用就只是确定每个线程要下载多少数据，确保n个线程的下载数据长度之和不小于文件大小即可。
                 * filesize = 4317517
                 */

                blockSize = (fileSize % threadNum) == 0 ? fileSize / threadNum : fileSize / threadNum + 1;//blockSize = 1439173

                Log.d(TAG, "fileSize:" + fileSize + "  blockSize:"+blockSize);

                File file = new File(filePath);
                /**
                 * 启动多个线程分段下载文件
                 * 每个线程只拉取自己被分配的数据段，并进行写操作
                 */
                for (int i = 0; i<threads.length; i++){
                    threads[i] = new FileDownloadThread((i+1), file, blockSize, downloadUrl);
                    threads[i].setName("Thread:"+i);
                    threads[i].start();
                }
                boolean isFinished = false;
                int downloadedAllSize = 0;
                while (!isFinished){
                    isFinished = true;
                    downloadedAllSize = 0;
                    for (int i = 0; i<threads.length; i++){
                        downloadedAllSize += threads[i].getDownloadLength();
                        if (!threads[i].isCompleted()){
                            isFinished = false;
                        }
                    }

                    //通知handler去更新UI
                    Message message = new Message();
                    message.getData().putInt("size", downloadedAllSize);
                    handler.sendMessage(message);
                    Thread.sleep(1000);

                }

            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * 下载文件，并写到sd卡上的存储路径上
     */
    class FileDownloadThread extends Thread {

        private String downloadUrl;
        private int threadId;//当前下载线程id
        private File file;//文件保存路径
        private int blockSize;//线程下载数据长度
        private int downloadLength;
        private boolean isCompleted;

        public FileDownloadThread(int threadId, File file, int blockSize, String downloadUrl) {
            this.threadId = threadId;
            this.file = file;
            this.blockSize = blockSize;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public void run() {

            HttpURLConnection connection = null;
            BufferedInputStream bis = null;
            RandomAccessFile raf = null;
            try {
                URL url = new URL(downloadUrl);
                connection = (HttpURLConnection) url.openConnection();
                /**
                 * threadId = 1
                 * startPos = 0， endPos = 1439173-1
                 *
                 * threadId = 2
                 * startPos = 1439173 * 1 = 1439173, endPos = 1439173*2 - 1
                 *
                 *  threadId = 3
                 *  ...
                 */
                int startPos = blockSize * (threadId - 1);//开始位置
                int endPos = blockSize * threadId - 1;//结束位置
                /**
                 * 设置当前线程下载的起点、终点
                 * threadId = 1
                 * "byte=0-(blockSize-1)"
                 * write 1439173
                 *
                 * threadId = 2
                 * "byte= blockSize8*1-(blockSize*2-1)
                 * write 1439173
                 *
                 * threadId = 3
                 * "byte= blockSize8*2-(blockSize*3-1)
                 * write 1439171
                 */
                connection.setRequestProperty("Range", "bytes="+startPos+"-"+endPos);//拉取byte=startPos-endPos这段的数据
                Log.d(TAG, Thread.currentThread().getName() + "  bytes="+ startPos + "-" + endPos);
                byte[] buffer = new byte[1024];
                bis = new BufferedInputStream(connection.getInputStream());
                raf = new RandomAccessFile(file, "rwd");
                raf.seek(startPos);
                int len;
                while ((len = bis.read(buffer, 0, 1024)) != -1){
                    raf.write(buffer, 0, len);
                    downloadLength += len;
                    Log.d(TAG, Thread.currentThread().getName()+" - downloadLength = "+downloadLength);
                }
                isCompleted = true;

            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                if (connection != null){
                    connection.disconnect();
                }
                if (bis != null){
                    try {
                        bis.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (raf != null){
                    try {
                        raf.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public boolean isCompleted(){
            return isCompleted;
        }

        public int getDownloadLength(){
            return downloadLength;
        }
    }



    /**
     * 启动安装替换apk
     * http://blog.csdn.net/zhouhuiah/article/details/18664225
     */
    private void ReplaceLaunchApk(String apkpath) {
        File file=new File(apkpath);
        if(file.exists())
        {
            Log.e(TAG,file.getName());
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            startActivity(intent);
//            context.finish();
        }
        else
        {
            Log.e(TAG, "File not exsit:"+apkpath);
        }
    }


}

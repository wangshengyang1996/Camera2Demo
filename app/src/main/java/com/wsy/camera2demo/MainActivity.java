package com.wsy.camera2demo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraDevice;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.wsy.camera2demo.camera2.Camera2Helper;
import com.wsy.camera2demo.camera2.Camera2Listener;
import com.wsy.camera2demo.util.ImageUtil;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements ViewTreeObserver.OnGlobalLayoutListener, Camera2Listener {
    private static final String TAG = "MainActivity";
    private static final int ACTION_REQUEST_PERMISSIONS = 1;
    private Camera2Helper camera2Helper;
    private TextureView textureView;
    // 用于显示原始预览数据
    private ImageView ivOriginFrame;
    // 用于显示和预览画面相同的图像数据
    private ImageView ivPreviewFrame;
    // 默认打开的CAMERA
    private static final String CAMERA_ID = Camera2Helper.CAMERA_ID_BACK;
    // 图像帧数据，全局变量避免反复创建，降低gc频率
    private byte[] nv21;
    // 显示的旋转角度
    private int displayOrientation;
    // 是否手动镜像预览
    private boolean isMirrorPreview;
    // 实际打开的cameraId
    private String openedCameraId;
    // 当前获取的帧数
    private int currentIndex = 0;
    // 处理的间隔帧
    private static final int PROCESS_INTERVAL = 1;  // set to 1 instead of 30 to get fluent preview
    // 线程池
    private ExecutorService imageProcessExecutor;
    // 需要的权限
    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        imageProcessExecutor = Executors.newSingleThreadExecutor();
        initView();
    }

    private void initView() {
        textureView = findViewById(R.id.texture_preview);
        textureView.getViewTreeObserver().addOnGlobalLayoutListener(this);
    }

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(this, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    void initCamera() {
        camera2Helper = new Camera2Helper.Builder()
                .cameraListener(this)
                .maxPreviewSize(new Point(1920, 1080))
                .minPreviewSize(new Point(1280, 720))
                .specificCameraId(CAMERA_ID)
                .context(getApplicationContext())
                .previewOn(textureView)
                .previewViewSize(new Point(textureView.getWidth(), textureView.getHeight()))
                .rotation(getWindowManager().getDefaultDisplay().getRotation())
                .build();
        camera2Helper.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ACTION_REQUEST_PERMISSIONS) {
            boolean isAllGranted = true;
            for (int grantResult : grantResults) {
                isAllGranted &= (grantResult == PackageManager.PERMISSION_GRANTED);
            }
            if (isAllGranted) {
                initCamera();
            } else {
                Toast.makeText(this, "权限被拒绝", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onGlobalLayout() {
        textureView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initCamera();
        }
    }

    @Override
    protected void onPause() {
        if (camera2Helper != null) {
            camera2Helper.stop();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (camera2Helper != null) {
            camera2Helper.start();
        }
    }


    @Override
    public void onCameraOpened(CameraDevice cameraDevice, String cameraId, final Size previewSize, final int displayOrientation, boolean isMirror) {
        Log.i(TAG, "onCameraOpened:  previewSize = " + previewSize.getWidth() + "x" + previewSize.getHeight());
        this.displayOrientation = displayOrientation;
        this.isMirrorPreview = isMirror;
        this.openedCameraId = cameraId;
        //在相机打开时，添加右上角的view用于显示原始数据和预览数据
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ivPreviewFrame = new BorderImageView(MainActivity.this);
                ivOriginFrame = new BorderImageView(MainActivity.this);
                TextView tvPreview = new TextView(MainActivity.this);
                TextView tvOrigin = new TextView(MainActivity.this);
                tvPreview.setTextColor(Color.WHITE);
                tvOrigin.setTextColor(Color.WHITE);
                tvPreview.setText(R.string.tag_preview);
                tvOrigin.setText(R.string.tag_origin);
                boolean needRotate = displayOrientation % 180 != 0;
                DisplayMetrics displayMetrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int longSide = displayMetrics.widthPixels > displayMetrics.heightPixels ? displayMetrics.widthPixels : displayMetrics.heightPixels;
                int shortSide = displayMetrics.widthPixels < displayMetrics.heightPixels ? displayMetrics.widthPixels : displayMetrics.heightPixels;

                FrameLayout.LayoutParams previewLayoutParams = new FrameLayout.LayoutParams(
                        !needRotate ? longSide / 4 : shortSide / 4,
                        needRotate ? longSide / 4 : shortSide / 4
                );
                FrameLayout.LayoutParams originLayoutParams = new FrameLayout.LayoutParams(
                        longSide / 4, shortSide / 4
                );
                previewLayoutParams.gravity = Gravity.END | Gravity.TOP;
                originLayoutParams.gravity = Gravity.END | Gravity.TOP;
                previewLayoutParams.topMargin = originLayoutParams.height;
                ivPreviewFrame.setLayoutParams(previewLayoutParams);
                tvPreview.setLayoutParams(previewLayoutParams);
                ivOriginFrame.setLayoutParams(originLayoutParams);
                tvOrigin.setLayoutParams(originLayoutParams);

                ((FrameLayout) textureView.getParent()).addView(ivPreviewFrame);
                ((FrameLayout) textureView.getParent()).addView(ivOriginFrame);
                ((FrameLayout) textureView.getParent()).addView(tvPreview);
                ((FrameLayout) textureView.getParent()).addView(tvOrigin);
            }
        });
    }


    @Override
    public void onPreview(final byte[] y, final byte[] u, final byte[] v, final Size previewSize, final int stride) {
        if (currentIndex++ % PROCESS_INTERVAL == 0) {
            imageProcessExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (nv21 == null) {
                        nv21 = new byte[stride * previewSize.getHeight() * 3 / 2];
                    }
                    // 回传数据是YUV422
                    if (y.length / u.length == 2) {
                        ImageUtil.yuv422ToYuv420sp(y, u, v, nv21, stride, previewSize.getHeight());
                    }
                    // 回传数据是YUV420
                    else if (y.length / u.length == 4) {
                        ImageUtil.yuv420ToYuv420sp(y, u, v, nv21, stride, previewSize.getHeight());
                    }
                    YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, stride, previewSize.getHeight(), null);
                    // ByteArrayOutputStream的close中其实没做任何操作，可不执行
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

                    // 由于某些stride和previewWidth差距大的分辨率，[0,previewWidth)是有数据的，而[previewWidth,stride)补上的U、V均为0，因此在这种情况下运行会看到明显的绿边
//                    yuvImage.compressToJpeg(new Rect(0, 0, stride, previewSize.getHeight()), 100, byteArrayOutputStream);

                    // 由于U和V一般都有缺损，因此若使用方式，可能会有个宽度为1像素的绿边
                    yuvImage.compressToJpeg(new Rect(0, 0, previewSize.getWidth(), previewSize.getHeight()), 100, byteArrayOutputStream);

                    // 为了删除绿边，抛弃一行像素
//                    yuvImage.compressToJpeg(new Rect(0, 0, previewSize.getWidth() - 1, previewSize.getHeight()), 100, byteArrayOutputStream);

                    byte[] jpgBytes = byteArrayOutputStream.toByteArray();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 4;
                    // 原始预览数据生成的bitmap
                    final Bitmap originalBitmap = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.length, options);
                    Matrix matrix = new Matrix();
                    // 预览相对于原数据可能有旋转
                    matrix.postRotate(Camera2Helper.CAMERA_ID_BACK.equals(openedCameraId) ? displayOrientation : -displayOrientation);

                    // 对于前置数据，镜像处理；若手动设置镜像预览，则镜像处理；若都有，则不需要镜像处理
                    if (Camera2Helper.CAMERA_ID_FRONT.equals(openedCameraId) ^ isMirrorPreview) {
                        matrix.postScale(-1, 1);
                    }
                    // 和预览画面相同的bitmap
                    final Bitmap previewBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, false);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ivOriginFrame.setImageBitmap(originalBitmap);
                            ivPreviewFrame.setImageBitmap(previewBitmap);
                        }
                    });
                }
            });
        }
    }

    @Override
    public void onCameraClosed() {
        Log.i(TAG, "onCameraClosed: ");
    }

    @Override
    public void onCameraError(Exception e) {
        e.printStackTrace();
    }

    @Override
    protected void onDestroy() {
        if (imageProcessExecutor != null) {
            imageProcessExecutor.shutdown();
            imageProcessExecutor = null;
        }
        if (camera2Helper != null) {
            camera2Helper.release();
        }
        super.onDestroy();
    }

    public void switchCamera(View view) {
        if (camera2Helper != null) {
            camera2Helper.switchCamera();
        }
        nv21 = null;  // added to prevent OutOfBoundsException in arraycopy
    }
}

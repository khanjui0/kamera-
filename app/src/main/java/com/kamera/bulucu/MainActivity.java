package com.kamera.bulucu;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
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
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "KayipBulucu";
    private static final int REQ_CAMERA = 1;
    /** Long side of the analysis grid; a cell is roughly 1/160 of the frame. */
    private static final int GRID_LONG_SIDE = 160;
    /**
     * How directly the back camera must face down (cosine of the angle from straight down) for
     * scanning: lost things are on the floor, a table or a seat, not on walls and ceilings.
     */
    private static final float FACING_DOWN = 0.5f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Semaphore openCloseLock = new Semaphore(1);
    private final Tracker tracker = new Tracker();

    private AspectFrame previewFrame;
    private TextureView textureView;
    private OverlayView overlay;
    private TextView statusText;
    private TextView sensitivityLabel;
    private Button torchBtn;
    private Button retryBtn;

    private HandlerThread bgThread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private ImageReader reader;
    private boolean torchAvailable;
    private volatile boolean torchOn;
    private volatile int sensitivity = 50;
    private boolean askedPermission;
    private SensorManager sensors;
    /** Assume facing down until a sensor says otherwise, so phones without one still work. */
    private volatile boolean facingDown = true;

    private Size previewSize;
    private Size analysisSize;
    private int sensorRotation;
    private FloorDetector detector;
    private byte[] yBytes, uBytes, vBytes;
    private float[] gridY, gridU, gridV;
    private String lastStatus = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        previewFrame = new AspectFrame(this);
        textureView = new TextureView(this);
        overlay = new OverlayView(this);
        previewFrame.addView(textureView);
        previewFrame.addView(overlay);
        root.addView(previewFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad / 2, pad, pad / 2);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(17);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Kamera açılıyor…");
        panel.addView(statusText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sensitivityLabel = new TextView(this);
        sensitivityLabel.setTextColor(0xFFBDBDBD);
        sensitivityLabel.setPadding(0, pad / 2, 0, 0);
        panel.addView(sensitivityLabel);

        SeekBar sensitivityBar = new SeekBar(this);
        sensitivityBar.setMax(100);
        sensitivityBar.setProgress(sensitivity);
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                sensitivity = value;
                updateSensitivityLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        panel.addView(sensitivityBar);
        updateSensitivityLabel();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        torchBtn = new Button(this);
        torchBtn.setText("Feneri Aç");
        torchBtn.setEnabled(false);
        torchBtn.setOnClickListener(v -> setTorch(!torchOn));
        buttons.addView(torchBtn);
        retryBtn = new Button(this);
        retryBtn.setText("Kamera İzni Ver");
        retryBtn.setVisibility(View.GONE);
        retryBtn.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA));
        buttons.addView(retryBtn);
        panel.addView(buttons);

        TextView hint = new TextView(this);
        hint.setTextColor(0xFF9E9E9E);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setText("Telefonu aradığın yere (yer, halı, masa, koltuk…) doğru tutup yavaşça gezdir. Yeşil kutu, yüzeyden farklı görünen küçük şeyi gösterir; ayakkabı ya da mobilya gibi büyük şeyleri göstermez.");
        panel.addView(hint);

        root.addView(panel);
        setContentView(root);
    }

    private void updateSensitivityLabel() {
        sensitivityLabel.setText("Hassasiyet: " + sensitivity
                + (sensitivity < 35 ? " (sadece belirgin şeyler)" : sensitivity > 70 ? " (en küçük farklar)" : ""));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensors != null) {
            Sensor gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (gravity == null) gravity = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (gravity != null) sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_UI);
        }
        startBackground();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Ask once by itself; after a refusal the button asks again, so a denial can't loop.
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            return;
        }
        startWhenReady();
    }

    @Override
    protected void onPause() {
        if (sensors != null) sensors.unregisterListener(this);
        closeCamera();
        stopBackground();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            retryBtn.setVisibility(View.GONE);
            startWhenReady();
        } else {
            setStatus("Düşen şeyleri bulmak için kamera izni gerekli.");
            retryBtn.setVisibility(View.VISIBLE);
        }
    }

    private void startWhenReady() {
        if (textureView.isAvailable()) {
            openCamera();
            return;
        }
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    private void startBackground() {
        bgThread = new HandlerThread("camera");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
    }

    private void stopBackground() {
        if (bgThread == null) return;
        bgThread.quitSafely();
        try {
            bgThread.join();
        } catch (InterruptedException ignored) {
        }
        bgThread = null;
        bg = null;
    }

    private void openCamera() {
        if (camera != null || bg == null) return;
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = pickCamera(mgr);
            if (id == null) {
                setStatus("Bu cihazda arka kamera bulunamadı.");
                return;
            }
            CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
            if (!chooseSizes(cc)) {
                setStatus("Kamera bu uygulamanın istediği görüntüyü veremiyor.");
                return;
            }
            Integer orientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorRotation = orientation == null ? 90 : orientation;
            Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            torchAvailable = flash != null && flash;
            torchBtn.setEnabled(torchAvailable);
            if (!torchAvailable) torchBtn.setText("Fener yok");

            boolean sideways = sensorRotation % 180 != 0;
            int pw = previewSize.getWidth(), ph = previewSize.getHeight();
            previewFrame.setAspect(sideways ? (float) ph / pw : (float) pw / ph);
            setupAnalysis(sideways);

            reader = ImageReader.newInstance(analysisSize.getWidth(), analysisSize.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, bg);

            if (!openCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                setStatus("Kamera meşgul, uygulamayı yeniden aç.");
                return;
            }
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    openCloseLock.release();
                    camera = device;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    openCloseLock.release();
                    device.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    openCloseLock.release();
                    device.close();
                    camera = null;
                    setStatus("Kamera açılamadı (hata " + error + ").");
                }
            }, bg);
        } catch (CameraAccessException | SecurityException e) {
            Log.w(TAG, "openCamera", e);
            setStatus("Kamera açılamadı.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String pickCamera(CameraManager mgr) throws CameraAccessException {
        String fallback = null;
        for (String id : mgr.getCameraIdList()) {
            Integer facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    /** Picks a preview and an analysis size with the same shape, so boxes line up with the picture. */
    private boolean chooseSizes(CameraCharacteristics cc) {
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return false;
        Size[] previews = map.getOutputSizes(SurfaceTexture.class);
        Size[] yuvs = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (previews == null || yuvs == null) return false;
        Size bestPreview = null, bestYuv = null;
        long bestScore = Long.MIN_VALUE;
        for (Size p : previews) {
            if (p.getWidth() > 1920 || p.getHeight() > 1440) continue;
            for (Size y : yuvs) {
                if ((long) p.getWidth() * y.getHeight() != (long) p.getHeight() * y.getWidth()) continue;
                // Prefer 4:3 (the whole sensor), a sharp preview and an analysis frame near 640 wide.
                boolean fourThree = p.getWidth() * 3 == p.getHeight() * 4;
                long score = (fourThree ? 10_000_000L : 0) + (long) p.getWidth() * p.getHeight() / 100
                        - Math.abs(y.getWidth() - 640) * 2000L;
                if (score > bestScore) {
                    bestScore = score;
                    bestPreview = p;
                    bestYuv = y;
                }
            }
        }
        if (bestPreview == null) return false;
        previewSize = bestPreview;
        analysisSize = bestYuv;
        return true;
    }

    private void setupAnalysis(boolean sideways) {
        int step = Math.max(1, Math.round(Math.max(analysisSize.getWidth(), analysisSize.getHeight()) / (float) GRID_LONG_SIDE));
        int gw = analysisSize.getWidth() / step, gh = analysisSize.getHeight() / step;
        int dw = sideways ? gh : gw, dh = sideways ? gw : gh;
        if (detector == null || detector.width() != dw || detector.height() != dh) {
            detector = new FloorDetector(dw, dh);
            gridY = new float[dw * dh];
            gridU = new float[dw * dh];
            gridV = new float[dw * dh];
        }
        tracker.reset();
    }

    private void startSession() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null || camera == null || reader == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(st);
            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(reader.getSurface());
            CameraCharacteristics cc = ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(camera.getId());
            int[] af = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (af != null) {
                if (contains(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                } else if (contains(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
            }
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            applyTorch();
            camera.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (camera == null) return;
                    session = s;
                    repeat();
                    setStatus("Taranıyor… kamerayı yüzeyde yavaşça gezdir.");
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    setStatus("Kamera ayarlanamadı.");
                }
            }, bg);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "startSession", e);
            setStatus("Kamera başlatılamadı.");
        }
    }

    private static boolean contains(int[] values, int v) {
        for (int x : values) if (x == v) return true;
        return false;
    }

    private void repeat() {
        try {
            if (session != null) session.setRepeatingRequest(request.build(), null, bg);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "repeat", e);
        }
    }

    private void applyTorch() {
        if (request == null) return;
        request.set(CaptureRequest.FLASH_MODE, torchOn && torchAvailable ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
    }

    private void setTorch(boolean on) {
        torchOn = on;
        torchBtn.setText(on ? "Feneri Kapat" : "Feneri Aç");
        if (bg != null) bg.post(() -> {
            applyTorch();
            repeat();
        });
    }

    private void closeCamera() {
        try {
            openCloseLock.acquire();
            if (session != null) {
                session.close();
                session = null;
            }
            if (camera != null) {
                camera.close();
                camera = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            request = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            openCloseLock.release();
        }
        overlay.setBoxes(new ArrayList<>());
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        float g = (float) Math.sqrt(x * x + y * y + z * z);
        // Screen up means the back camera looks down; gravity then lies along the screen's normal.
        if (g > 1f) facingDown = z / g > FACING_DOWN;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Runs on the camera thread for every analysis frame. */
    private void onFrame(ImageReader r) {
        Image image;
        try {
            image = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) return;
        if (!facingDown) {
            image.close();
            tracker.reset();
            main.post(() -> {
                overlay.setBoxes(new ArrayList<>());
                setStatus("Kamerayı aşağı, aradığın yere (yer, masa, koltuk…) doğrult.");
            });
            return;
        }
        try {
            toGrid(image);
        } catch (RuntimeException e) {
            Log.w(TAG, "toGrid", e);
            return;
        } finally {
            image.close();
        }
        detector.setSensitivity(sensitivity);
        List<FloorDetector.Box> found = detector.detect(gridY, gridU, gridV);
        int fresh = tracker.update(found);
        List<float[]> boxes = new ArrayList<>();
        for (Tracker.Track t : tracker.visible()) boxes.add(new float[]{t.left, t.top, t.right, t.bottom});
        boolean busy = detector.wasBusy();
        main.post(() -> {
            overlay.setBoxes(boxes);
            if (busy && boxes.isEmpty()) {
                setStatus("Yüzey çok desenli — biraz yaklaş ya da hassasiyeti düşür.");
            } else if (boxes.isEmpty()) {
                setStatus("Taranıyor… kamerayı yüzeyde yavaşça gezdir.");
            } else {
                setStatus(boxes.size() == 1 ? "Bir şey bulundu! Yeşil kutuya bak." : boxes.size() + " şey bulundu! Yeşil kutulara bak.");
            }
            if (fresh > 0) buzz();
        });
    }

    /**
     * Averages the camera frame down to a small grid, turned to match how the preview is shown
     * (the activity is portrait-only, so the turn is just the sensor's own orientation).
     */
    private void toGrid(Image image) {
        Image.Plane[] planes = image.getPlanes();
        yBytes = copy(planes[0].getBuffer(), yBytes);
        uBytes = copy(planes[1].getBuffer(), uBytes);
        vBytes = copy(planes[2].getBuffer(), vBytes);
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int cRow = planes[1].getRowStride(), cPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();

        int iw = image.getWidth(), ih = image.getHeight();
        boolean sideways = sensorRotation % 180 != 0;
        int gw = sideways ? detector.height() : detector.width();
        int gh = sideways ? detector.width() : detector.height();
        int step = iw / gw;
        int dw = detector.width();
        float lumaCount = step * step;
        int cStep = Math.max(1, step / 2);
        float chromaCount = cStep * cStep;

        for (int gy = 0; gy < gh; gy++) {
            int y0 = gy * step;
            for (int gx = 0; gx < gw; gx++) {
                int x0 = gx * step;
                int sy = 0;
                for (int yy = y0; yy < y0 + step; yy++) {
                    int base = yy * yRow;
                    for (int xx = x0; xx < x0 + step; xx++) sy += yBytes[base + xx * yPix] & 0xFF;
                }
                int su = 0, sv = 0;
                int cy0 = y0 / 2, cx0 = x0 / 2;
                for (int yy = cy0; yy < cy0 + cStep; yy++) {
                    for (int xx = cx0; xx < cx0 + cStep; xx++) {
                        su += uBytes[yy * cRow + xx * cPix] & 0xFF;
                        sv += vBytes[yy * vRow + xx * vPix] & 0xFF;
                    }
                }
                int dx, dy;
                switch (sensorRotation) {
                    case 90:
                        dx = gh - 1 - gy;
                        dy = gx;
                        break;
                    case 180:
                        dx = gw - 1 - gx;
                        dy = gh - 1 - gy;
                        break;
                    case 270:
                        dx = gy;
                        dy = gw - 1 - gx;
                        break;
                    default:
                        dx = gx;
                        dy = gy;
                }
                int i = dy * dw + dx;
                gridY[i] = sy / lumaCount;
                gridU[i] = su / chromaCount - 128f;
                gridV[i] = sv / chromaCount - 128f;
            }
        }
    }

    private static byte[] copy(ByteBuffer buf, byte[] into) {
        buf.rewind();
        int len = buf.remaining();
        if (into == null || into.length < len) into = new byte[len];
        buf.get(into, 0, len);
        return into;
    }

    private void buzz() {
        Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vib.vibrate(40);
        }
    }

    private void setStatus(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> setStatus(text));
            return;
        }
        if (text.equals(lastStatus)) return;
        lastStatus = text;
        statusText.setText(text);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Lays its children out at a fixed width/height ratio, centred, as large as fits. */
    private static final class AspectFrame extends FrameLayout {
        private float aspect = 3f / 4f;

        AspectFrame(Context context) {
            super(context);
        }

        void setAspect(float aspect) {
            this.aspect = aspect;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            int cw = w, ch = Math.round(w / aspect);
            if (ch > h) {
                ch = h;
                cw = Math.round(h * aspect);
            }
            int ws = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), hs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(ws, hs);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                int left = (r - l - c.getMeasuredWidth()) / 2, top = (b - t - c.getMeasuredHeight()) / 2;
                c.layout(left, top, left + c.getMeasuredWidth(), top + c.getMeasuredHeight());
            }
        }
    }
}

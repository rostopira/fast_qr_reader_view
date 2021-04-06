package co.apperto.fastqrreaderview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import com.google.mlkit.vision.barcode.Barcode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import co.apperto.fastqrreaderview.common.CameraSource;
import co.apperto.fastqrreaderview.common.CameraSourcePreview;
import co.apperto.fastqrreaderview.java.barcodescanning.BarcodeScanningProcessor;
import co.apperto.fastqrreaderview.java.barcodescanning.OnCodeScanned;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.view.FlutterView;
import io.flutter.view.TextureRegistry;

/**
 * FastQrReaderViewPlugin
 */
public class FastQrReaderViewPlugin implements MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, FlutterPlugin, ActivityAware {

    private static final int CAMERA_REQUEST_ID = 513469796;
    private static final int REQUEST_PERMISSION = 47;
    private static final String TAG = "FastQrReaderViewPlugin";
    private static final SparseIntArray ORIENTATIONS =
            new SparseIntArray() {
                {
                    append(Surface.ROTATION_0, 0);
                    append(Surface.ROTATION_90, 90);
                    append(Surface.ROTATION_180, 180);
                    append(Surface.ROTATION_270, 270);
                }
            };

    private static CameraManager cameraManager;
    private QrReader camera;
    private Activity activity;
    // The code to run after requesting camera permissions.
    private Runnable cameraPermissionContinuation;
    private boolean requestingPermission;
    private static MethodChannel channel;
    private Result permissionResult;
    private BinaryMessenger messenger;
    private TextureRegistry textureRegistry;

    // Whether we should ignore process(). This is usually caused by feeding input data faster than
    // the model can handle.
//    private final AtomicBoolean shouldThrottle = new AtomicBoolean(false);


    public FastQrReaderViewPlugin() {}

    /*
     * Open Settings screens
     */
    private void openSettings() {
        if (activity == null)
            return;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName()));
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION) {
            // for each permission check if the user granted/denied them
            // you may want to group the rationale in a single dialog,
            // this is just an example
            for (int i = 0, len = permissions.length; i < len; i++) {
                String permission = permissions[i];
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                // user rejected the permission
                    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale( activity, permission );
                    if (! showRationale) {
                        // user also CHECKED "never ask again"
                        // you can either enable some fall back,
                        // disable features of your app
                        // or open another dialog explaining
                        // again the permission and directing to
                        // the app setting
                        permissionResult.success("dismissedForever");
                    } else {
                        permissionResult.success("denied");
                    }
                } else if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    permissionResult.success("granted");
                } else {
                    permissionResult.success("unknown");
                }
            }
            return true;
        }

        return false;
    }


    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        switch (call.method) {
            case "init":
                if (camera != null) {
                    camera.close();
                }
                result.success(null);
                break;
            case "availableCameras":
                try {
                    String[] cameraNames = cameraManager.getCameraIdList();
                    List<Map<String, Object>> cameras = new ArrayList<>();
                    for (String cameraName : cameraNames) {
                        HashMap<String, Object> details = new HashMap<>();
                        CameraCharacteristics characteristics =
                                cameraManager.getCameraCharacteristics(cameraName);
//                        Object test = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
//                        Log.d(TAG, "onMethodCall: "+test.toString());
                        details.put("name", cameraName);
                        @SuppressWarnings("ConstantConditions")
                        int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        switch (lensFacing) {
                            case CameraMetadata.LENS_FACING_FRONT:
                                details.put("lensFacing", "front");
                                break;
                            case CameraMetadata.LENS_FACING_BACK:
                                details.put("lensFacing", "back");
                                break;
                            case CameraMetadata.LENS_FACING_EXTERNAL:
                                details.put("lensFacing", "external");
                                break;
                        }
                        cameras.add(details);
                    }
                    result.success(cameras);
                } catch (CameraAccessException e) {
                    result.error("cameraAccess", e.getMessage(), null);
                }
                break;
            case "initialize": {
                String cameraName = call.argument("cameraName");
                String resolutionPreset = call.argument("resolutionPreset");
                ArrayList<String> codeFormats = call.argument("codeFormats");

                if (camera != null) {
                    camera.close();
                }
                camera = new QrReader(cameraName, resolutionPreset, codeFormats, result);
                break;
            }
            case "startScanning":
                startScanning(result);
                break;
            case "stopScanning":
                stopScanning(result);
                break;
            case "checkPermission":
                String permission;
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    permission = "granted";
                } else {
                    permission = "denied";
                }
                result.success(permission);
                break;
            case "requestPermission":
                this.permissionResult = result;
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION);
                break;
            case "settings":
                openSettings();
            case "toggleFlash":
                toggleFlash(result);
                break;
            case "dispose": {
                if (camera != null) {
                    camera.dispose();
                }
                result.success(null);
                break;
            }
            default:
                result.notImplemented();
                break;
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        messenger = binding.getBinaryMessenger();
        channel = new MethodChannel(messenger, "fast_qr_reader_view");
        channel.setMethodCallHandler(this);
        textureRegistry = binding.getTextureRegistry();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        messenger = null;
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());
        cameraManager = (CameraManager) binding.getActivity().getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {

    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private class CameraRequestPermissionsListener
            implements PluginRegistry.RequestPermissionsResultListener {
        @Override
        public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
            if (id == CAMERA_REQUEST_ID) {
                if (cameraPermissionContinuation != null)
                    cameraPermissionContinuation.run();
                return true;
            }
            return false;
        }
    }


    void startScanning(@NonNull Result result) {
        camera.scanning = true;
        camera.barcodeScanningProcessor.shouldThrottle.set(false);
        result.success(null);
    }

    void stopScanning(@NonNull Result result) {
        stopScanning();
        result.success(null);
    }

    private void stopScanning() {
        camera.scanning = false;
        camera.barcodeScanningProcessor.shouldThrottle.set(true);
    }

    void toggleFlash(@NonNull Result result) {
        toggleFlash();
        result.success(null);
    }

    private void toggleFlash() {
        camera.cameraSource.toggleFlash();
    }


    private class QrReader {

        private static final int PERMISSION_REQUESTS = 1;

        private CameraSource cameraSource = null;
        private CameraSourcePreview preview;

        private final FlutterView.SurfaceTextureEntry textureEntry;
        private EventChannel.EventSink eventSink;

        BarcodeScanningProcessor barcodeScanningProcessor;

        ArrayList<Integer> reqFormats;
        private int sensorOrientation;
        private boolean isFrontFacing;
        private String cameraName;
        private Size captureSize;
        private Size previewSize;
        //        private CaptureRequest.Builder captureRequestBuilder;
        private Size videoSize;
//        FirebaseVisionBarcodeDetectorOptions visionOptions;
//        FirebaseVisionBarcodeDetector codeDetector;
//        private Handler codeDetectionHandler = null;
//        private HandlerThread mHandlerThread = null;
//
        private boolean scanning;

        private void startCameraSource() {
            if (cameraSource != null) {
                try {
                    if (preview == null) {
                        Log.d(TAG, "resume: Preview is null");
                    } else {
                        preview.start(cameraSource);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Unable to start camera source.", e);
                    cameraSource.release();
                    cameraSource = null;
                }
            }
        }

        //
        QrReader(final String cameraName, final String resolutionPreset, final ArrayList<String> formats, @NonNull final Result result) {

            // AVAILABLE FORMATS:
            // enum CodeFormat { codabar, code39, code93, code128, ean8, ean13, itf, upca, upce, aztec, datamatrix, pdf417, qr }

            Map<String, Integer> map = new HashMap<>();
            map.put("codabar", Barcode.FORMAT_CODABAR);
            map.put("code39", Barcode.FORMAT_CODE_39);
            map.put("code93", Barcode.FORMAT_CODE_93);
            map.put("code128", Barcode.FORMAT_CODE_128);
            map.put("ean8", Barcode.FORMAT_EAN_8);
            map.put("ean13", Barcode.FORMAT_EAN_13);
            map.put("itf", Barcode.FORMAT_ITF);
            map.put("upca", Barcode.FORMAT_UPC_A);
            map.put("upce", Barcode.FORMAT_UPC_E);
            map.put("aztec", Barcode.FORMAT_AZTEC);
            map.put("datamatrix", Barcode.FORMAT_DATA_MATRIX);
            map.put("pdf417", Barcode.FORMAT_PDF417);
            map.put("qr", Barcode.FORMAT_QR_CODE);


            reqFormats = new ArrayList<>();

            for (String f :
                    formats) {
                if (map.get(f) != null) {
                    reqFormats.add(map.get(f));
                }
            }

            textureEntry = textureRegistry.createSurfaceTexture();
//barcodeScanningProcessor.onSuccess();
//
            try {
                Size minPreviewSize;
                switch (resolutionPreset) {
                    case "high":
                        minPreviewSize = new Size(1024, 768);
                        break;
                    case "medium":
                        minPreviewSize = new Size(640, 480);
                        break;
                    case "low":
                        minPreviewSize = new Size(320, 240);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
                }
//
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
                StreamConfigurationMap streamConfigurationMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                //noinspection ConstantConditions
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                //noinspection ConstantConditions
                isFrontFacing =
                        characteristics.get(CameraCharacteristics.LENS_FACING)
                                == CameraMetadata.LENS_FACING_FRONT;
                computeBestCaptureSize(streamConfigurationMap);
                computeBestPreviewAndRecordingSize(streamConfigurationMap, minPreviewSize, captureSize);

                if (cameraPermissionContinuation != null) {
                    result.error("cameraPermission", "Camera permission request ongoing", null);
                }
                cameraPermissionContinuation =
                        new Runnable() {
                            @Override
                            public void run() {
                                cameraPermissionContinuation = null;
                                if (!hasCameraPermission()) {
                                    result.error(
                                            "cameraPermission", "MediaRecorderCamera permission not granted", null);
                                    return;
                                }
                                open(result);
                            }
                        };
                requestingPermission = false;
                if (hasCameraPermission()) {
                    cameraPermissionContinuation.run();
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity != null) {
                        requestingPermission = true;
                        activity.requestPermissions(
                            new String[]{Manifest.permission.CAMERA},
                            CAMERA_REQUEST_ID
                        );
                    }
                }
            } catch (CameraAccessException e) {
                result.error("CameraAccess", e.getMessage(), null);
            } catch (IllegalArgumentException e) {
                result.error("IllegalArgumentException", e.getMessage(), null);
            }
        }

        //
        private void registerEventChannel() {
            new EventChannel(
                    messenger, "fast_qr_reader_view/cameraEvents" + textureEntry.id())
                    .setStreamHandler(
                            new EventChannel.StreamHandler() {
                                @Override
                                public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                                    QrReader.this.eventSink = eventSink;
                                }

                                @Override
                                public void onCancel(Object arguments) {
                                    QrReader.this.eventSink = null;
                                }
                            });
        }

        //
        private boolean hasCameraPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || activity.checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        }

        //
        private void computeBestPreviewAndRecordingSize(
                StreamConfigurationMap streamConfigurationMap, Size minPreviewSize, Size captureSize) {
            Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
            float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();
            List<Size> goodEnough = new ArrayList<>();
            for (Size s : sizes) {
                if ((float) s.getWidth() / s.getHeight() == captureSizeRatio
                        && minPreviewSize.getWidth() < s.getWidth()
                        && minPreviewSize.getHeight() < s.getHeight()) {
                    goodEnough.add(s);
                }
            }

            Collections.sort(goodEnough, new CompareSizesByArea());

            if (goodEnough.isEmpty()) {
                previewSize = sizes[0];
                videoSize = sizes[0];
            } else {
                previewSize = goodEnough.get(0);

                // Video capture size should not be greater than 1080 because MediaRecorder cannot handle higher resolutions.
                videoSize = goodEnough.get(0);
                for (int i = goodEnough.size() - 1; i >= 0; i--) {
                    if (goodEnough.get(i).getHeight() <= 1080) {
                        videoSize = goodEnough.get(i);
                        break;
                    }
                }
            }
        }

        private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
            // For still image captures, we use the largest available size.
            captureSize =
                    Collections.max(
                            Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)),
                            new CompareSizesByArea());
        }

        //
//
        @SuppressLint("MissingPermission")
        private void open(@Nullable final Result result) {
            if (!hasCameraPermission()) {
                if (result != null)
                    result.error("cameraPermission", "Camera permission not granted", null);
            } else {
//                try {
                cameraSource = new CameraSource(activity);
                cameraSource.setFacing(isFrontFacing ? 1 : 0);
                barcodeScanningProcessor = new BarcodeScanningProcessor(reqFormats);
                barcodeScanningProcessor.callback = new OnCodeScanned() {
                    @Override
                    public void onCodeScanned(Barcode barcode) {
                        if (camera.scanning) {
                            Log.w(TAG, "onSuccess: " + barcode.getRawValue());
                            channel.invokeMethod("updateCode", barcode.getRawValue());
                            stopScanning();
                        }
                    }
                };
                cameraSource.setMachineLearningFrameProcessor(barcodeScanningProcessor);
//                    test.shouldThrottle.set(true);
                preview = new CameraSourcePreview(activity, null, textureEntry.surfaceTexture());

                startCameraSource();
                registerEventChannel();

                Map<String, Object> reply = new HashMap<>();
                reply.put("textureId", textureEntry.id());
                reply.put("previewWidth", cameraSource.getPreviewSize().getWidth());
                reply.put("previewHeight", cameraSource.getPreviewSize().getHeight());
                result.success(reply);


//                    imageReader =
//                            ImageReader.newInstance(
//                                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.YUV_420_888, 2);
//                    cameraManager.openCamera(
//                            cameraName,
//                            new CameraDevice.StateCallback() {
//                                @Override
//                                public void onOpened(@NonNull CameraDevice cameraDevice) {
//                                    QrReader.this.cameraDevice = cameraDevice;
//                                    try {
//                                        startPreview();
//                                    } catch (CameraAccessException e) {
//                                        if (result != null)
//                                            result.error("CameraAccess", e.getMessage(), null);
//                                    }
//
//                                    if (result != null) {
//                                        Map<String, Object> reply = new HashMap<>();
//                                        reply.put("textureId", textureEntry.id());
//                                        reply.put("previewWidth", previewSize.getWidth());
//                                        reply.put("previewHeight", previewSize.getHeight());
//                                        result.success(reply);
//                                    }
//                                }
//
//                                @Override
//                                public void onClosed(@NonNull CameraDevice camera) {
//                                    if (eventSink != null) {
//                                        Map<String, String> event = new HashMap<>();
//                                        event.put("eventType", "cameraClosing");
//                                        eventSink.success(event);
//                                    }
//                                    super.onClosed(camera);
//                                }
//
//                                @Override
//                                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
//                                    cameraDevice.close();
//                                    QrReader.this.cameraDevice = null;
//                                    sendErrorEvent("The camera was disconnected.");
//                                }
//
//                                @Override
//                                public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
//                                    cameraDevice.close();
//                                    QrReader.this.cameraDevice = null;
//                                    String errorDescription;
//                                    switch (errorCode) {
//                                        case ERROR_CAMERA_IN_USE:
//                                            errorDescription = "The camera device is in use already.";
//                                            break;
//                                        case ERROR_MAX_CAMERAS_IN_USE:
//                                            errorDescription = "Max cameras in use";
//                                            break;
//                                        case ERROR_CAMERA_DISABLED:
//                                            errorDescription =
//                                                    "The camera device could not be opened due to a device policy.";
//                                            break;
//                                        case ERROR_CAMERA_DEVICE:
//                                            errorDescription = "The camera device has encountered a fatal error";
//                                            break;
//                                        case ERROR_CAMERA_SERVICE:
//                                            errorDescription = "The camera service has encountered a fatal error.";
//                                            break;
//                                        default:
//                                            errorDescription = "Unknown camera error";
//                                    }
//                                    sendErrorEvent(errorDescription);
//                                }
//                            },
//                            null);
//                } catch (CameraAccessException e) {
//                    if (result != null) result.error("cameraAccess", e.getMessage(), null);
//                }
            }
        }

        //
//
//        private void startPreview() throws CameraAccessException {
//
//
////            FirebaseVisionBarcodeDetectorOptions options = new FirebaseVisionBarcodeDetectorOptions.Builder()
////                    .setBarcodeFormats(FirebaseVisionBarcode.FORMAT_QR_CODE)
////                    .build();
////
////            FirebaseVisionBarcodeDetector detector = FirebaseVision.getInstance()
////                    .getVisionBarcodeDetector(options);
//////detector.detectInImage(FirebaseVisionImage.)
//////        detector.detectInImage(FirebaseVisionImage.fromByteBuffer())
////            closeCaptureSession();
//
//            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
//            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
//            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//
//            List<Surface> surfaces = new ArrayList<>();
//
//
//            Surface previewSurface = new Surface(surfaceTexture);
//
//            surfaces.add(previewSurface);
//            surfaces.add(imageReader.getSurface());
//            captureRequestBuilder.addTarget(previewSurface);
//            captureRequestBuilder.addTarget(imageReader.getSurface());
//
//
//            camera.
//                    cameraDevice.createCaptureSession(
//                    surfaces,
//                    new CameraCaptureSession.StateCallback() {
//
//                        @Override
//                        public void onConfigured(@NonNull CameraCaptureSession session) {
//                            if (cameraDevice == null) {
//                                sendErrorEvent("The camera was closed during configuration.");
//                                return;
//                            }
//                            try {
//                                cameraCaptureSession = session;
//                                captureRequestBuilder.set(
//                                        CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
//                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
//                                    @Override
//                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
//                                        super.onCaptureCompleted(session, request, result);
//                                    }
//                                }, null);
//                            } catch (CameraAccessException e) {
//                                sendErrorEvent(e.getMessage());
//                            }
//                        }
//
//                        @Override
//                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
//                            sendErrorEvent("Failed to configure the camera for preview.");
//                        }
//                    },
//                    null);
//        }
//
        private void sendErrorEvent(String errorDescription) {
            if (eventSink != null) {
                Map<String, String> event = new HashMap<>();
                event.put("eventType", "error");
                event.put("errorDescription", errorDescription);
                eventSink.success(event);
            }
        }
//
//        private void closeCaptureSession() {
//            if (cameraCaptureSession != null) {
//                cameraCaptureSession.close();
//                cameraCaptureSession = null;
//            }
//        }
//
//        private void close() {
//            closeCaptureSession();
//
//            if (cameraDevice != null) {
//                cameraDevice.close();
//                cameraDevice = null;
//            }
//            if (imageReader != null) {
//                imageReader.close();
//                imageReader = null;
//            }
//        }

        private void close() {
            if (preview != null) {
                preview.stop();
            }

            if (cameraSource != null) {
                cameraSource.release();
            }

            camera = null;

        }

        private void dispose() {
//            close();
            textureEntry.release();
//            if (camera != null) {
            if (preview != null) {
                preview.stop();
            }

            if (cameraSource != null) {
                cameraSource.release();
            }
//            }
        }
    }
}


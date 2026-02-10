package com.pictureuploader.camera

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * CameraXを使った撮影ヘルパー。
 * JPEG保存、ファイル名規則、高速起動を実現する。
 */
class CameraHelper(private val context: Context) {

    companion object {
        private const val TAG = "CameraHelper"
        private const val FILENAME_FORMAT = "yyyy-MM-dd_HH-mm-ss"
        private const val FILENAME_SUFFIX = "_picture.jpg"
    }

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * カメラのプレビューを開始する。
     */
    fun startCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 写真を撮影してファイルパスを返す。
     *
     * 保存先: context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
     * ファイル名: YYYY-MM-DD_HH-mm-ss_picture.jpg
     *
     * @return 保存されたファイルの絶対パス
     * @throws ImageCaptureException 撮影に失敗した場合
     * @throws IllegalStateException カメラが初期化されていない場合
     */
    suspend fun takePhoto(): String = suspendCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resumeWithException(
                IllegalStateException("Camera not initialized")
            )
            return@suspendCoroutine
        }

        val photoFile = createPhotoFile()

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedPath = photoFile.absolutePath
                    Log.d(TAG, "Photo saved: $savedPath")
                    continuation.resume(savedPath)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    continuation.resumeWithException(exception)
                }
            }
        )
    }

    /**
     * ファイル名規則に従ったファイルを作成する。
     * YYYY-MM-DD_HH-mm-ss_picture.jpg
     */
    private fun createPhotoFile(): File {
        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IllegalStateException("External files directory not available")

        if (!picturesDir.exists()) {
            picturesDir.mkdirs()
        }

        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())
        val fileName = "$timestamp$FILENAME_SUFFIX"

        return File(picturesDir, fileName)
    }

    /**
     * カメラリソースを解放する。
     */
    fun shutdown() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
    }
}

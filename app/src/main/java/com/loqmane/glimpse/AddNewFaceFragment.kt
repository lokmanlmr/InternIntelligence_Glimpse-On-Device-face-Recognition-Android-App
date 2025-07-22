package com.loqmane.glimpse

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix // Keep for rotateBitmapIfNeeded
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController // For navigation
import androidx.navigation.fragment.navArgs
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.loqmane.glimpse.databinding.FragmentAddNewFaceBinding
import com.loqmane.glimpse.roomdb.FaceEntity
import com.loqmane.glimpse.roomdb.FaceViewModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class AddNewFaceFragment : Fragment() {
    private val args: AddNewFaceFragmentArgs by navArgs()
    private var _binding: FragmentAddNewFaceBinding? = null
    private val binding get() = _binding!!
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private val faceViewModel: FaceViewModel by viewModels()
    private var cameraSelectorOption = CameraSelector.LENS_FACING_BACK // To track current camera
    private var cameraProvider: ProcessCameraProvider? = null // To hold camera provider instance
    private lateinit var embeddingHelper: FaceEmbeddingHelper // Declare helper
    private var faceDetector: FaceDetector? = null


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddNewFaceBinding.inflate(inflater, container, false)

        // Initialize EmbeddingHelper
        embeddingHelper = FaceEmbeddingHelper(requireContext()) // Ensure model details are correct

        // Initialize Face Detector - Aligned with HomeFragment for consistency
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE) // Use accurate for enrollment
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE) // Explicitly set to NONE
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE) // Explicitly set to NONE
            .build()
        faceDetector = FaceDetection.getClient(highAccuracyOpts)

        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            val permissions = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            ActivityCompat.requestPermissions(
                requireActivity(), permissions.toTypedArray(), REQUEST_CODE_PERMISSIONS
            )
        }
        binding.btnCapture.setOnClickListener {
            captureAndSaveFace()
        }

        // Add double-tap gesture listener to switch camera
        val gestureDetector =
            GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    switchCamera()
                    return true
                }
            })

        binding.previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    private fun allPermissionsGranted(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val storageGranted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No explicit storage permission needed for MediaStore on Q+
        }
        return cameraGranted && storageGranted
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Get display rotation
            val display = binding.previewView.display
            if (display == null) {
                Log.e(TAG, "PreviewView display is null. Cannot get rotation for ImageCapture.")
                Toast.makeText(requireContext(), "Error setting up camera.", Toast.LENGTH_SHORT)
                    .show()
                return@addListener
            }
            val rotation = display.rotation

            val preview = Preview
                .Builder()
                .setTargetRotation(rotation)
                .build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(rotation) // Set target rotation
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrameForBox(imageProxy)
                    }
                }

            val cameraSelector =
                CameraSelector.Builder().requireLensFacing(cameraSelectorOption).build()
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageCapture, imageAnalyzer
                )
                Log.d(TAG, "Camera bound for AddNewFaceFragment")
            } catch (exc: Exception) {
                Log.e(TAG, "Camera binding failed", exc)
                Toast.makeText(requireContext(), "Could not start camera.", Toast.LENGTH_SHORT)
                    .show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun switchCamera() {
        if (cameraProvider == null) {
            Log.w(TAG, "Camera provider not available, cannot switch camera.")
            Toast.makeText(requireContext(), "Camera not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        cameraSelectorOption = if (cameraSelectorOption == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        Log.d(
            TAG,
            "Switching camera to: ${if (cameraSelectorOption == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK"}"
        )
        startCamera() // Re-bind camera use cases
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrameForBox(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        faceDetector?.process(inputImage)
            ?.addOnSuccessListener { detectedFaces ->
                val currentBinding = _binding ?: return@addOnSuccessListener
                currentBinding.graphicOverlay.clear()

                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val imageWidthForOverlay =
                    if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.height else imageProxy.width
                val imageHeightForOverlay =
                    if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.width else imageProxy.height

                currentBinding.graphicOverlay.setCameraInfo(
                    imageWidthForOverlay,
                    imageHeightForOverlay,
                    cameraSelectorOption == CameraSelector.LENS_FACING_FRONT
                )

                for (face in detectedFaces) {
                    // Use the name passed via navigation arguments as the label for the box
                    val faceGraphic =
                        FaceGraphic(currentBinding.graphicOverlay, face, args.faceName)
                    currentBinding.graphicOverlay.add(faceGraphic)
                }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Real-time face detection failed", e)
            }
            ?.addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun captureAndSaveFace() {
        val imageCapture = this.imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized.")
            Toast.makeText(requireContext(), "Camera not ready.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCapture.isEnabled = false // Disable button to prevent multiple clicks

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                @SuppressLint("UnsafeOptInUsageError")
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val capturedBitmap =
                        imageProxy.toBitmap() // This bitmap might not be rotated yet

                    // Rotate the bitmap if necessary
                    val correctlyRotatedBitmap = if (rotationDegrees != 0) {
                        val matrix = Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())
                        Bitmap.createBitmap(
                            capturedBitmap,
                            0,
                            0,
                            capturedBitmap.width,
                            capturedBitmap.height,
                            matrix,
                            true
                        )
                    } else {
                        capturedBitmap
                    }
                    imageProxy.close() // Don't forget to close!
                    processCapturedBitmap(correctlyRotatedBitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                    Toast.makeText(
                        requireContext(),
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.btnCapture.isEnabled = true
                }
            }
        )
    }


    private fun processCapturedBitmap(capturedBitmap: Bitmap) {
        val inputImage =
            InputImage.fromBitmap(capturedBitmap, 0) // Rotation is 0 as bitmap is already rotated

        faceDetector?.process(inputImage)
            ?.addOnSuccessListener { detectedFaces ->
                if (detectedFaces.isEmpty()) {
                    Log.w(TAG, "No faces detected in the captured image.")
                    Toast.makeText(
                        requireContext(),
                        "No face detected. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.btnCapture.isEnabled = true
                    return@addOnSuccessListener
                }

                // Assuming we use the first detected face for enrollment
                val face = detectedFaces[0]
                val faceBoundingBox = face.boundingBox

                // 1. Crop the detected face from the captured bitmap
                val croppedFaceBitmap = cropFaceBitmap(capturedBitmap, faceBoundingBox)

                // 2. Generate embedding for the cropped face
                val embedding = embeddingHelper.getFaceEmbedding(croppedFaceBitmap)

                if (embedding == null) {
                    Log.e(TAG, "Failed to generate face embedding.")
                    Toast.makeText(
                        requireContext(),
                        "Could not process face features. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.btnCapture.isEnabled = true
                    return@addOnSuccessListener
                }

                // Log embedding stats for debugging during enrollment
                if (embedding != null) {
                    val embeddingSum = embedding.sum()
                    val embeddingMagnitude = sqrt(embedding.fold(0f) { acc, v -> acc + v * v })
                    Log.d(
                        TAG,
                        "Enrolled embedding - Length: ${embedding.size}, Sum: $embeddingSum, Magnitude: $embeddingMagnitude (should be ~1.0 if normalized)"
                    )
                }

                // 3. Convert FloatArray embedding to ByteArray
                val faceFeaturesByteArray = floatArrayToByteArray(embedding)

                // 4. Save the original captured image (or the cropped face) to gallery
                val imagePath = saveBitmapToGallery(
                    capturedBitmap,
                    "face_${args.faceName}_${System.currentTimeMillis()}.jpg"
                )
                if (imagePath.isNullOrEmpty()) {
                    Log.e(TAG, "Failed to save image to gallery.")
                }

                // 5. Save to Room DB
                val faceEntity = FaceEntity(
                    name = args.faceName,
                    imagePath = imagePath ?: "", // Store path to the saved image
                    boundingBoxLeft = faceBoundingBox.left.toFloat(),
                    boundingBoxTop = faceBoundingBox.top.toFloat(),
                    boundingBoxRight = faceBoundingBox.right.toFloat(),
                    boundingBoxBottom = faceBoundingBox.bottom.toFloat(),
                    features = faceFeaturesByteArray // Store the actual embedding
                )
                faceViewModel.insert(faceEntity)
                Log.i(TAG, "Face '${args.faceName}' saved to database with embedding.")
                Toast.makeText(
                    requireContext(),
                    "Face '${args.faceName}' saved!",
                    Toast.LENGTH_SHORT
                ).show()

                // Navigate back or to a success screen
                findNavController().popBackStack()

            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed on captured image", e)
                Toast.makeText(
                    requireContext(),
                    "Face detection failed. Try again.",
                    Toast.LENGTH_LONG
                ).show()
                binding.btnCapture.isEnabled = true
            }
            ?.addOnCompleteListener {
                Log.d(TAG, "Face detection process complete.")
            }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, displayName: String): String? {
        val imageFileName = displayName
        var imagePathUri: String? = null
        val contentResolver = requireContext().contentResolver

        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Glimpse") // Your app's album
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(imageCollection, values)
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                imagePathUri = uri.toString()
                Log.d(TAG, "Image saved to gallery: $imagePathUri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image to gallery", e)
                contentResolver.delete(uri, null, null)
                imagePathUri = null
            }
        } else {
            Log.e(TAG, "MediaStore insert returned null URI.")
        }
        return imagePathUri
    }

    // Utility to convert FloatArray to ByteArray
    private fun floatArrayToByteArray(floatArray: FloatArray): ByteArray {
        if (floatArray.isEmpty()) {
            Log.e("AddNewFaceFragment", "Input floatArray is empty in floatArrayToByteArray")
            return ByteArray(0)
        }
        val byteBuffer = ByteBuffer.allocate(floatArray.size * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val floatBuffer = byteBuffer.asFloatBuffer()
        floatBuffer.put(floatArray)
        val byteArray = byteBuffer.array()
        return byteArray
    }


    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        faceDetector?.close() // Close face detector
        embeddingHelper.close() // Close embedding helper
        _binding = null
        Log.d(TAG, "AddNewFaceFragment onDestroyView")
    }

    companion object {
        private const val TAG = "AddNewFaceFragment"
        private const val REQUEST_CODE_PERMISSIONS = 101
    }
}

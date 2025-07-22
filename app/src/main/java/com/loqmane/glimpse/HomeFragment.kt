package com.loqmane.glimpse

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap // Added for Bitmap
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy // Added for ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider // For ViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.loqmane.glimpse.databinding.FragmentHomeBinding
import com.loqmane.glimpse.roomdb.FaceEntity
import com.loqmane.glimpse.roomdb.FaceViewModel
import java.nio.ByteBuffer
import java.nio.ByteOrder // Important for consistent ByteArray <-> FloatArray conversion
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.view.GestureDetector
import android.view.MotionEvent


class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var cameraSelectorOption = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var faceDetector: FaceDetector? = null

    private lateinit var embeddingHelper: FaceEmbeddingHelper
    private lateinit var faceViewModel: FaceViewModel

    @Volatile // Ensure visibility across threads
    private var savedFaces: List<FaceEntity> = emptyList()

    companion object {
        private const val TAG = "HomeFragment"

        // Set threshold to 60% as requested.
        private const val RECOGNITION_THRESHOLD = 0.6f
    }


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Camera permission is required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        cameraExecutor = Executors.newSingleThreadExecutor()

        embeddingHelper = FaceEmbeddingHelper(requireContext())
        faceViewModel = ViewModelProvider(this)[FaceViewModel::class.java]

        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestCameraPermission()
        }

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

        faceViewModel.allFaces.observe(viewLifecycleOwner) { faces ->
            savedFaces = faces // This is now thread-safe due to @Volatile
            Log.d(
                TAG,
                "Loaded ${faces.size} faces from database. Names: ${faces.joinToString { it.name }}"
            )
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val currentCameraProvider = cameraProvider ?: run {
            Log.e(TAG, "Camera provider not initialized.")
            return
        }

        val display = binding.previewView.display
        if (display == null) {
            Log.e(TAG, "PreviewView display is null. Cannot get rotation.")
            return
        }
        val rotation = display.rotation

        val faceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            //.setMinFaceSize(0.15f) // Optional: detect faces that are at least 15% of the image width
            .build()
        faceDetector = FaceDetection.getClient(faceDetectorOptions)

        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        try {
            currentCameraProvider.unbindAll()
            val cameraSelector =
                CameraSelector.Builder().requireLensFacing(cameraSelectorOption).build()
            currentCameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            Log.d(TAG, "Camera use cases bound successfully.")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Create InputImage from mediaImage, providing the rotation degrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        faceDetector?.process(inputImage)
            ?.addOnSuccessListener { detectedFaces ->
                val bitmapFromProxy =
                    imageProxy.toBitmap() // This bitmap is NOT yet rotated based on rotationDegrees

                if (bitmapFromProxy != null) {
                    // Rotate the bitmap to make it upright for consistent cropping
                    val correctlyRotatedFullBitmap = if (rotationDegrees != 0) {
                        Log.d(
                            TAG,
                            "HomeFragment: Applying rotation to fullBitmap: $rotationDegrees degrees"
                        )
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())
                        Bitmap.createBitmap(
                            bitmapFromProxy, 0, 0,
                            bitmapFromProxy.width, bitmapFromProxy.height,
                            matrix, true
                        )
                    } else {
                        Log.d(
                            TAG,
                            "HomeFragment: No rotation needed for fullBitmap (rotationDegrees is 0)."
                        )
                        bitmapFromProxy
                    }
                    handleDetectedFaces(
                        detectedFaces,
                        correctlyRotatedFullBitmap,
                        imageProxy
                    ) // Pass the rotated bitmap
                } else {
                    Log.e(TAG, "HomeFragment: Failed to convert ImageProxy to Bitmap.")
                }
                imageProxy.close() // Close here after all processing of imageProxy is done
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "HomeFragment: Face detection failed", e)
                imageProxy.close() // Ensure closure on failure too
            }
    }


    private fun handleDetectedFaces(
        faces: List<Face>,
        fullBitmap: Bitmap,
        imageProxy: ImageProxy
    ) {
        val currentBinding = _binding ?: run {
            Log.w(TAG, "handleDetectedFaces called but binding is null. View likely destroyed.")
            return
        }
        currentBinding.graphicOverlay.clear()

        val imageWidthForOverlay: Int
        val imageHeightForOverlay: Int
        // Get rotation degrees from imageProxy.imageInfo for correct overlay setup
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Adjust width/height for overlay based on rotation
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageWidthForOverlay = imageProxy.height // Swapped
            imageHeightForOverlay = imageProxy.width  // Swapped
        } else {
            imageWidthForOverlay = imageProxy.width
            imageHeightForOverlay = imageProxy.height
        }
        currentBinding.graphicOverlay.setCameraInfo(
            imageWidthForOverlay,
            imageHeightForOverlay,
            cameraSelectorOption == CameraSelector.LENS_FACING_FRONT
        )

        if (faces.isEmpty()) {
            Log.v(TAG, "No faces detected in this frame.") // Verbose log for no faces
            return
        }
        Log.d(TAG, "Detected ${faces.size} faces in this frame.")


        for (face in faces) {
            val faceBitmap = cropFaceBitmap(fullBitmap, face.boundingBox)

            val currentFaceEmbedding = embeddingHelper.getFaceEmbedding(faceBitmap)

            var recognizedName: String = "Unknown" // Default to Unknown, non-nullable
            var bestMatchSimilarity = -1f // For cosine similarity, higher is better (max 1.0)
            val allScores = mutableListOf<Pair<String, Float>>() // Track all scores for debugging

            if (currentFaceEmbedding != null) {
                Log.d("Debugging", "Current embedding : ${currentFaceEmbedding.size}")
                if (currentFaceEmbedding.all { it == 0.0f } && currentFaceEmbedding.isNotEmpty()) {
                    Log.w(TAG, "Current live embedding is all zeros!")
                }
                if (savedFaces.isNotEmpty()) {
                    // Track all similarity scores to help tune the threshold
                    val allScores = mutableListOf<Pair<String, Float>>()

                    for (savedFaceEntity in savedFaces) {
                        if (savedFaceEntity.features.isEmpty()) {
                            Log.w(TAG, "Saved face ${savedFaceEntity.name} has empty features.")
                            continue
                        }
                        val savedEmbedding = byteArrayToFloatArray(savedFaceEntity.features)
                        if (savedEmbedding.isEmpty()) {
                            Log.w(
                                TAG,
                                "Could not convert ByteArray to FloatArray for ${savedFaceEntity.name}"
                            )
                            continue
                        }

                        if (currentFaceEmbedding.size != savedEmbedding.size) {
                            Log.e(
                                TAG,
                                "Embedding size mismatch! Current: ${currentFaceEmbedding.size}, Saved (${savedFaceEntity.name}): ${savedEmbedding.size}"
                            )
                            continue // Skip this comparison
                        }

                        val similarity =
                            calculateCosineSimilarity(currentFaceEmbedding, savedEmbedding)
                        allScores.add(savedFaceEntity.name to similarity)

                        if (similarity > bestMatchSimilarity && similarity >= RECOGNITION_THRESHOLD) {
                            bestMatchSimilarity = similarity
                            recognizedName = savedFaceEntity.name
                        }
                    }

                    val sortedScores = allScores.sortedByDescending { it.second }
                    Log.d(
                        TAG,
                        "All similarity scores: ${
                            sortedScores.joinToString {
                                "${it.first}: ${
                                    String.format(
                                        "%.3f",
                                        it.second
                                    )
                                }"
                            }
                        }"
                    )

                    if (recognizedName != "Unknown") {
                        val message = "Recognized: $recognizedName (Similarity: ${
                            String.format(
                                "%.2f",
                                bestMatchSimilarity
                            )
                        })"
                        Log.i(TAG, message)
                        activity?.runOnUiThread {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    } else if (sortedScores.isNotEmpty()) {
                        Log.i(
                            TAG,
                            "Best match was ${sortedScores[0].first} with score ${
                                String.format(
                                    "%.2f",
                                    sortedScores[0].second
                                )
                            } (threshold: $RECOGNITION_THRESHOLD)"
                        )
                    }
                }
            } else {
                Log.e(TAG, "Could not generate embedding for a detected face.")
            }
            // Draw the graphic with the recognized name
            val faceGraphic =
                FaceGraphic(binding.graphicOverlay, face, recognizedName) // Pass non-nullable name
            currentBinding.graphicOverlay.add(faceGraphic)
        }
    }

    private fun byteArrayToFloatArray(byteArray: ByteArray): FloatArray {
        if (byteArray.isEmpty()) {
            Log.e(TAG, "Empty byteArray in byteArrayToFloatArray")
            return FloatArray(0)
        }

        if (byteArray.size % 4 != 0) {
            Log.e(TAG, "byteArray size ${byteArray.size} is not a multiple of 4")
            return FloatArray(0)
        }

        try {
            val buffer = ByteBuffer.wrap(byteArray)
            buffer.order(ByteOrder.nativeOrder())

            val floatArray = FloatArray(byteArray.size / 4)
            for (i in floatArray.indices) {
                floatArray[i] = buffer.float
            }

            return floatArray
        } catch (e: Exception) {
            Log.e(TAG, "Error converting byteArray to floatArray", e)
            return FloatArray(0)
        }
    }

    /**
     * Calculates the Cosine Similarity between two L2-normalized FloatArray vectors.
     * For normalized vectors, this simplifies to the dot product.
     */
    private fun calculateCosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size || vec1.isEmpty()) {
            Log.e(
                TAG,
                "VECTORS INVALID: Size mismatch or empty. vec1: ${vec1.size}, vec2: ${vec2.size}"
            )
            return -2.0f // Return a value outside the valid range
        }

        var dotProduct = 0.0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
        }

        // Clamp the result to the valid [-1, 1] range to account for potential floating-point inaccuracies.
        val similarity = dotProduct.coerceIn(-1.0f, 1.0f)

        Log.i(TAG, "Cosine Similarity: $similarity")
        return similarity
    }


    private fun switchCamera() {
        if (cameraProvider == null) {
            Log.w(TAG, "Camera provider not available, cannot switch camera.")
            Toast.makeText(requireContext(), "Camera not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        cameraSelectorOption = if (cameraSelectorOption == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        Log.d(
            TAG,
            "Switching camera to: ${if (cameraSelectorOption == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK"}"
        )
        bindCameraUseCases()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        faceDetector?.close()
        embeddingHelper.close()
        _binding = null
        Log.d(TAG, "HomeFragment onDestroyView called, resources released.")
    }
}

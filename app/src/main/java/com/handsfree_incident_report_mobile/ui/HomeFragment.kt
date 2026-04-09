package com.handsfree_incident_report_mobile.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues

import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore

import android.util.Log

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import java.util.concurrent.ExecutorService
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector

import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.CredentialsManager
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.callback.Callback
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import com.google.android.material.snackbar.Snackbar
import com.handsfree_incident_report_mobile.R
import com.handsfree_incident_report_mobile.data.VideoRepository
import com.handsfree_incident_report_mobile.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class HomeFragment : Fragment() {

    private lateinit var account: Auth0
    private lateinit var credentialsManager: CredentialsManager

    private val viewModel: HomeViewModel by viewModels {
        val authClient = AuthenticationAPIClient(Auth0.getInstance(getString(R.string.com_auth0_client_id), getString(R.string.com_auth0_domain)))
        val cm = CredentialsManager(authClient, SharedPreferencesStorage(requireContext()))
        HomeViewModelFactory(VideoRepository(requireContext(), cm))
    }


    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){
        uri -> uri?.let{
        AlertDialog.Builder(requireContext())
            .setTitle("Video Selected")
            .setMessage("What do you want to do with the video?")
            .setPositiveButton("Send"){dialog, _ -> viewModel.sendVideo(it); dialog.dismiss()}
            .setNegativeButton("Cancel"){dialog, _ -> dialog.dismiss()}
            .setCancelable(false)
            .show()
        }
    }

    fun degreesToCardinal(degrees: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        return directions[((degrees + 22.5f) / 45f).toInt() % 8]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        account = Auth0.getInstance(
            getString(R.string.com_auth0_client_id),
            getString(R.string.com_auth0_domain)
        )
        val authClient = AuthenticationAPIClient(account)
        credentialsManager = CredentialsManager(authClient, SharedPreferencesStorage(requireContext()))

        // Observe status to handle UI navigation/messages
        viewModel.uploadStatus.observe(viewLifecycleOwner) { result ->
            result?.let {
                it.onSuccess {
                    showSnackBar("Upload Successful!")
                }.onFailure { error ->
                    if (error is CredentialsManagerException) {
                        findNavController().navigate(R.id.action_home_to_login)
                    } else {
                        showSnackBar("Upload Failed: ${error.message}")
                    }
                }
                viewModel.resetStatus() // Clear status after handling
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.orientation.collect { orientation ->
                    orientation?.let {
                        val degrees = it.headingDegrees
                        val cardinal = degreesToCardinal(degrees)
                        binding.compassValues.text = getString(R.string.compass_display, degrees.toInt(), cardinal)
                    }
                }
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // binding.imageCaptureButton.setOnClickListener { takePhoto() }
        binding.videoCaptureButton.setOnClickListener { captureVideo() }
        binding.testButton.setOnClickListener { callPrivateApi() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.useExisting.setOnClickListener { pickVideoLauncher.launch("video/*")}
    }

    private fun logout() {
        WebAuthProvider
            .logout(account)
            .withScheme(getString(R.string.com_auth0_scheme))
            .start(requireActivity(), object : Callback<Void?, AuthenticationException> {
                override fun onSuccess(result: Void?) {
                    credentialsManager.clearCredentials()
                    findNavController().navigate(R.id.action_home_to_login)
                }
                override fun onFailure(error: AuthenticationException) {
                    Toast.makeText(requireContext(), "Logout failed: ${error.getCode()}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun callPrivateApi() {
        credentialsManager.getCredentials(object : Callback<Credentials, CredentialsManagerException> {
            override fun onSuccess(result: Credentials) {
                val token = result.accessToken

                Thread {
                    try {
                        val url = URL("https://api.hands-free-incident-report.ch/api/private")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("Authorization", "Bearer $token")

                        val responseCode = connection.responseCode
                        val response = if (responseCode in 200..299) {
                            connection.inputStream.bufferedReader().readText()
                        } else {
                            connection.errorStream?.bufferedReader()?.readText() ?: "no error body"
                        }

                        requireActivity().runOnUiThread {
                            if (responseCode == 401){
                                findNavController().navigate(R.id.action_home_to_login)
                            }

                            Log.d(TAG, "Private API response: $response")
                            showSnackBar("$responseCode: $response")
                        }

                    } catch (e: Exception) {
                        requireActivity().runOnUiThread {
                            Log.e(TAG, "Error calling private API", e)
                            showSnackBar("Error: ${e.message}")
                        }
                    }
                }.start()
            }

            override fun onFailure(error: CredentialsManagerException) {
                requireActivity().runOnUiThread {
                    showSnackBar("No valid credentials: ${error.message}")
                    findNavController().navigate(R.id.action_home_to_login)

                }
            }
        })
    }



    // --- Camera (moved from MainActivity unchanged) ---

    private fun takePhoto(){
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.GERMAN)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Incident-Reports")
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(requireContext().contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    showSnackBar("Photo capture succeeded: ${output.savedUri}")
                    Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")
                }
            }
        )
    }
    private fun captureVideo(){
        val videoCapture = this.videoCapture ?: return

        binding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Incident-Reports")
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireContext().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(requireContext(),
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)

                            requireActivity().runOnUiThread {
                                AlertDialog.Builder(requireContext())
                                    .setTitle("Video Recorded")
                                    .setMessage("What do you want to do with the video?")
                                    .setPositiveButton("Send"){ dialog, _ ->
                                        // Encrypt and send the video to server
                                        viewModel.sendVideo(recordEvent.outputResults.outputUri)
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton("Cancel"){ dialog, _ ->
                                        dialog.dismiss()
                                    }
                                    .setCancelable(false)
                                    .show()
                            }




                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        binding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }
    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.viewFinder.surfaceProvider
                }

            imageCapture = ImageCapture.Builder().build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) startCamera()
            else Toast.makeText(requireContext(), "Permission request denied", Toast.LENGTH_SHORT).show()
        }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }

    private fun showSnackBar(text: String) {
        Snackbar
            .make(
                binding.root,
                text,
                Snackbar.LENGTH_LONG
            ).show()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}

package br.com.teste.cameraxteste

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private val grausConhecidos = FloatArray(2)

    var valoresAcelerometer = FloatArray(3)


    lateinit var mSensorManager: SensorManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listener for take photo button
        //camera_capture_button.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager


        initListeners()


        // wait for one second until gyroscope and magnetometer/accelerometer
        // data is initialised then scedule the complementary filter task

        Tap.setOnClickListener {

            grausConhecidos[0] = capturarGrau()
            anguloBase.text = "Base: ${grausConhecidos[0]}º"

        }
        TapTopo.setOnClickListener {
            grausConhecidos[1] = capturarGrau()
            anguloTop.text = "Top: ${grausConhecidos[1]}º"
        }

        clean.setOnClickListener {
            anguloBase.text = "Ângulo Base"
            anguloTop.text = "Ângulo topo"
            alturaCalculada.text = "Altura"
            grausConhecidos[0] = 0.0f
            grausConhecidos[1] = 0.0f
        }
        H.setOnClickListener {
            if (edit_distancia.text.toString() != "") {
                val altura = capturarAltura()
                alturaCalculada.text = altura.toString() + "M"
            }
        }

    }

    override fun onDestroy() {
        mSensorManager.unregisterListener(this)
        cameraExecutor.shutdown()
        super.onDestroy()

    }

    override fun onStop() {
        mSensorManager.unregisterListener(this)
        super.onStop()
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            })

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()


            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                            Log.d(TAG, "Average luminosity: $luma")
                        })
                    }
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))

    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, getString(br.com.teste.cameraxteste.R.string.cameraXteste)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) :
        ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }


    fun initListeners() {
        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            mSensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }

    }


    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            valoresAcelerometer[0] = x
            valoresAcelerometer[1] = y
            valoresAcelerometer[2] = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    fun capturarGrau(): Float {
        val magnitude =
            sqrt(
                (valoresAcelerometer[0] * valoresAcelerometer[0] +
                        valoresAcelerometer[1] * valoresAcelerometer[1]
                        + valoresAcelerometer[2] * valoresAcelerometer[2]).toDouble()
            ).toFloat()

        val cosTheta: Float = valoresAcelerometer[1] / magnitude
        val thetaGraus = (acos(cosTheta.toDouble()) * 180.0 / Math.PI).toFloat()
        //Toast.makeText(this, thetaGraus.toString(), Toast.LENGTH_SHORT).show()
        return thetaGraus
    }

    fun capturarAltura(): Double {
        val distance = edit_distancia.text.toString().toFloat()
        val tangenteTop = Math.toRadians(grausConhecidos[1].toDouble())
        val calculoTop = tangenteTop * distance
        val tangenteBase = Math.toRadians(grausConhecidos[0].toDouble())
        val calculoBase = tangenteBase * distance
        val altura = calculoTop + calculoBase
        return altura
    }
}

typealias LumaListener = (luma: Double) -> Unit
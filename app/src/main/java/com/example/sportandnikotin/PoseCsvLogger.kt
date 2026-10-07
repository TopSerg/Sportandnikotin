package com.example.sportandnikotin

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PoseCsvLogger(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var mediaUri: Uri? = null
    private var targetDescription: String? = null
    private var frameIndex = 0L

    val isRecording: Boolean
        @Synchronized get() = writer != null

    @Synchronized
    fun start(): String {
        stop()

        val fileName = "pose_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            ".csv"

        val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/SportAndNikotin",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("Could not create CSV in Downloads")

            mediaUri = uri
            targetDescription = "Downloads/SportAndNikotin/$fileName"
            context.contentResolver.openOutputStream(uri)
                ?: error("Could not open CSV for writing")
        } else {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            val logDir = File(baseDir, "pose_logs").apply { mkdirs() }
            val file = File(logDir, fileName)

            targetDescription = file.absolutePath
            file.outputStream()
        }

        writer = BufferedWriter(OutputStreamWriter(outputStream))
        frameIndex = 0L
        writer?.write(
            "frame,timestamp_ms,wall_time_epoch_ms,inference_ms,image_width,image_height," +
                "pose_detected,landmark_id,landmark_name,x_norm,y_norm,z_norm,visibility," +
                "presence,x_world,y_world,z_world,world_visibility,world_presence\n",
        )
        writer?.flush()

        return targetDescription ?: fileName
    }

    @Synchronized
    fun log(
        result: PoseLandmarkerResult,
        inputWidth: Int,
        inputHeight: Int,
        inferenceTimeMs: Long,
    ) {
        val currentWriter = writer ?: return
        val frame = frameIndex++
        val wallTime = System.currentTimeMillis()
        val landmarks = result.landmarks().firstOrNull()
        val worldLandmarks = result.worldLandmarks().firstOrNull()

        if (landmarks == null || landmarks.isEmpty()) {
            currentWriter.write(
                "$frame,${result.timestampMs()},$wallTime,$inferenceTimeMs," +
                    "$inputWidth,$inputHeight,false,-1,NO_POSE,,,,,,,,,,\n",
            )
        } else {
            landmarks.forEachIndexed { index, landmark ->
                val world = worldLandmarks?.getOrNull(index)
                val name = LANDMARK_NAMES.getOrElse(index) { "landmark_$index" }

                currentWriter.write(
                    buildString {
                        append(frame)
                        append(',')
                        append(result.timestampMs())
                        append(',')
                        append(wallTime)
                        append(',')
                        append(inferenceTimeMs)
                        append(',')
                        append(inputWidth)
                        append(',')
                        append(inputHeight)
                        append(",true,")
                        append(index)
                        append(',')
                        append(name)
                        append(',')
                        append(landmark.x())
                        append(',')
                        append(landmark.y())
                        append(',')
                        append(landmark.z())
                        append(',')
                        append(optionalValue(landmark.visibility()))
                        append(',')
                        append(optionalValue(landmark.presence()))
                        append(',')
                        append(world?.x() ?: "")
                        append(',')
                        append(world?.y() ?: "")
                        append(',')
                        append(world?.z() ?: "")
                        append(',')
                        append(world?.visibility()?.let(::optionalValue) ?: "")
                        append(',')
                        append(world?.presence()?.let(::optionalValue) ?: "")
                        append('\n')
                    },
                )
            }
        }

        if (frame % 15L == 0L) {
            currentWriter.flush()
        }
    }

    @Synchronized
    fun stop(): String? {
        val previousTarget = targetDescription
        writer?.flush()
        writer?.close()
        writer = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            }
        }

        mediaUri = null
        targetDescription = null
        return previousTarget
    }

    private fun optionalValue(value: java.util.Optional<Float>): String =
        if (value.isPresent) value.get().toString() else ""

    companion object {
        val LANDMARK_NAMES = listOf(
            "nose",
            "left_eye_inner",
            "left_eye",
            "left_eye_outer",
            "right_eye_inner",
            "right_eye",
            "right_eye_outer",
            "left_ear",
            "right_ear",
            "mouth_left",
            "mouth_right",
            "left_shoulder",
            "right_shoulder",
            "left_elbow",
            "right_elbow",
            "left_wrist",
            "right_wrist",
            "left_pinky",
            "right_pinky",
            "left_index",
            "right_index",
            "left_thumb",
            "right_thumb",
            "left_hip",
            "right_hip",
            "left_knee",
            "right_knee",
            "left_ankle",
            "right_ankle",
            "left_heel",
            "right_heel",
            "left_foot_index",
            "right_foot_index",
        )
    }
}

package com.hermitech.hermivision.data.decoder

import com.hermitech.hermivision.domain.decoder.IVideoDecoder

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.hermitech.hermivision.domain.decoder.VideoMetadata
import android.util.Log
import com.hermitech.hermivision.domain.inference.INativePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class VideoDecoder : IVideoDecoder {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_USEC = 10000L
    }

    /**
     * Decode video frames and submit YUV planes directly to C++ FramePool.
     *
     * This is the zero-allocation path:
     *   Image.getPlanes() → ByteBuffer (direct) → JNI → C++ PreProcessor::yuvToRgb()
     *   No Mat, no clone(), no Kotlin-side RGB conversion.
     *
     * @param videoPath Path to video file
     * @param pipeline  NativePipeline with initialized FramePool
     * @param onFrame   Callback with (frameId, totalDecoded) for progress reporting
     */
    override suspend fun decodeToFramePool(videoPath: String, pipeline: INativePipeline, onFrame: ((frameId: Int) -> Unit)?) = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting data source: ${e.message}")
            return@withContext
        }

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0) {
            Log.e(TAG, "No video track found")
            extractor.release()
            return@withContext
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        var isOutputEOS = false
        var frameCount = 0

        while (!isOutputEOS) {
            if (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC)
            when (outIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Format changed: ${decoder.outputFormat}")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outIndex >= 0) {
                        val image = decoder.getOutputImage(outIndex)
                        if (image != null && info.size > 0) {
                            try {
                                // Submit YUV planes directly to C++ (zero Kotlin allocation)
                                val yPlane = image.planes[0]
                                val uvPlane = image.planes[2]  // V plane for NV21

                                (pipeline as? com.hermitech.hermivision.data.inference.NativePipeline)
                                    ?.submitYuvFrameDirect(
                                    yBuffer = yPlane.buffer,
                                    uvBuffer = uvPlane.buffer,
                                    width = image.width,
                                    height = image.height,
                                    yStride = yPlane.rowStride,
                                    uvStride = uvPlane.rowStride,
                                    frameId = frameCount
                                ) ?: error("VideoDecoder requires NativePipeline for direct YUV submission")
                                onFrame?.invoke(frameCount)
                                frameCount++
                            } catch (e: Exception) {
                                Log.e(TAG, "Error submitting YUV frame: ${e.message}")
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                        }
                    }
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()
        Log.i(TAG, "FramePool decode complete. Submitted $frameCount frames.")
    }

    /**
     * [LEGACY] Decode video to Mat channel — kept for backward compatibility.
     */
    suspend fun decodeToMatChannel(videoPath: String, outputChannel: SendChannel<Mat>) = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting data source: ${e.message}")
            outputChannel.close()
            return@withContext
        }

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0) {
            Log.e(TAG, "No video track found")
            extractor.release()
            outputChannel.close()
            return@withContext
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        var isOutputEOS = false
        var frameCount = 0

        while (!isOutputEOS) {
            if (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC)
            when (outIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Format changed: ${decoder.outputFormat}")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outIndex >= 0) {
                        val image = decoder.getOutputImage(outIndex)
                        if (image != null && info.size > 0) {
                            try {
                                val rgbMat = imageToRgbMat(image)
                                outputChannel.send(rgbMat)
                                frameCount++
                            } catch (e: Exception) {
                                Log.e(TAG, "Error preprocessing image: ${e.message}")
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                        }
                    }
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()
        outputChannel.close()
        Log.i(TAG, "Hardware extraction complete. Processed $frameCount frames.")
    }

    // ── Pre-allocated buffers for legacy Mat path ──
    private var nv21Buffer: ByteArray? = null
    private var yuvMat: Mat? = null
    private var rgbMat: Mat? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private fun imageToRgbMat(image: Image): Mat {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val vBuffer = vPlane.buffer
        val ySize = yBuffer.remaining()
        val vSize = vBuffer.remaining()
        val totalSize = ySize + vSize

        if (nv21Buffer == null || nv21Buffer!!.size < totalSize || w != lastWidth || h != lastHeight) {
            nv21Buffer = ByteArray(totalSize)
            yuvMat?.release()
            rgbMat?.release()
            yuvMat = Mat(h + h / 2, w, CvType.CV_8UC1)
            rgbMat = Mat(h, w, CvType.CV_8UC3)
            lastWidth = w
            lastHeight = h
            Log.d(TAG, "Allocated decode buffers: ${w}x${h}")
        }

        val nv21 = nv21Buffer!!

        yBuffer.position(0)
        vBuffer.position(0)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)

        yuvMat!!.put(0, 0, nv21)
        Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)

        return rgbMat!!.clone()
    }

    fun clearBuffer() {
        nv21Buffer = null
        yuvMat?.release()
        yuvMat = null
        rgbMat?.release()
        rgbMat = null
        lastWidth = 0
        lastHeight = 0
    }
    override suspend fun getVideoMetadata(videoPath: String): VideoMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frameCountStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val frameCount = frameCountStr?.toIntOrNull() ?: 0
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val fps = if (durationMs > 0) (frameCount * 1000f / durationMs) else 30f

            VideoMetadata(width, height, fps)
        } finally {
            retriever.release()
        }
    }
}

package com.hermitech.hermivision.domain.decoder

import com.hermitech.hermivision.domain.inference.INativePipeline

interface IVideoDecoder {

    /**
     * Decode video frames and submit YUV planes directly to pipeline FramePool.
     *
     * @param videoPath Absolute path to the video file
     * @param pipeline  Initialized pipeline with active FramePool
     * @param onFrame   Callback invoked after each frame is submitted (frameId)
     */
    suspend fun decodeToFramePool(
        videoPath: String,
        pipeline: INativePipeline,
        onFrame: ((frameId: Int) -> Unit)? = null
    )

    suspend fun getVideoMetadata(videoPath: String): VideoMetadata
}

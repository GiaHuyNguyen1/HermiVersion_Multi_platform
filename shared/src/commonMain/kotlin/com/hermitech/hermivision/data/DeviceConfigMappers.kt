package com.hermitech.hermivision.data

import com.hermitech.hermivision.db.AppDatabase
import com.hermitech.hermivision.db.DeviceConfig
import com.hermitech.hermivision.domain.inference.AIConfig
import com.hermitech.hermivision.domain.inference.DelegateType
import com.hermitech.hermivision.domain.inference.DeviceTier

fun DeviceConfig.toAIConfig(): AIConfig = AIConfig(
    tier = DeviceTier.valueOf(tier),
    tfliteDelegate = DelegateType.valueOf(delegate_),
    yoloModelName = modelName,
    numThreads = numThreads.toInt(),
    deviceSummary = summary,
    nnapiAvgMs = nnapiMs,
    gpuAvgMs = gpuMs,
    cpuAvgMs = cpuMs
)

fun loadAiConfigOrNull(database: AppDatabase = createAppDatabase()): AIConfig? =
    database.deviceConfigQueries.getConfig().executeAsOneOrNull()?.toAIConfig()

fun saveAiConfig(config: AIConfig, database: AppDatabase = createAppDatabase()) {
    database.deviceConfigQueries.insertOrReplace(
        tier = config.tier.name,
        delegate_ = config.tfliteDelegate.name,
        modelName = config.yoloModelName,
        numThreads = config.numThreads.toLong(),
        summary = config.deviceSummary,
        nnapiMs = config.nnapiAvgMs,
        gpuMs = config.gpuAvgMs,
        cpuMs = config.cpuAvgMs
    )
}

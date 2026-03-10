package com.healthchat.app

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HeartRateState(
    val bpm: Long? = null,
    val recentAvg: Long? = null,
    val status: String = "측정 대기",
    val statusEmoji: String = "💤",
    val statusColor: Int = 0xFF888888.toInt(),
    val cardBg: Int = 0xFFF8F8F8.toInt(),
    val lastTime: String = "",
    val isMonitoring: Boolean = false,
    val sampleCount: Int = 0
)

class HeartRateMonitor(private val healthReader: HealthDataReader) {

    companion object {
        private const val TAG = "HeartRateMonitor"
        const val POLL_INTERVAL_MS = 15_000L   // 15초 간격
        const val WINDOW_MINUTES = 3L          // 최근 3분 데이터 참조
    }

    private val _state = MutableStateFlow(HeartRateState())
    val state: StateFlow<HeartRateState> = _state.asStateFlow()

    private var monitorJob: Job? = null

    fun startMonitoring(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        Log.d(TAG, "심박수 모니터링 시작")
        _state.value = _state.value.copy(isMonitoring = true, status = "측정 중...")

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    val sample = healthReader.readLatestHeartRate(WINDOW_MINUTES)
                    val newState = buildState(sample, isMonitoring = true)
                    _state.value = newState
                    Log.d(TAG, "심박수 업데이트: ${sample.bpm}bpm (${sample.sampleCount}개 샘플)")
                } catch (e: Exception) {
                    Log.w(TAG, "심박수 읽기 실패", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        Log.d(TAG, "심박수 모니터링 중지")
        monitorJob?.cancel()
        monitorJob = null
        _state.value = _state.value.copy(
            isMonitoring = false,
            status = "모니터링 중지",
            statusEmoji = "⏸️"
        )
    }

    fun isMonitoring() = monitorJob?.isActive == true

    private fun buildState(sample: HeartRateSample, isMonitoring: Boolean): HeartRateState {
        val bpm = sample.bpm
        val (status, emoji, color, cardBg) = classifyHeartRate(bpm)
        return HeartRateState(
            bpm = bpm,
            recentAvg = sample.recentAvg,
            status = status,
            statusEmoji = emoji,
            statusColor = color,
            cardBg = cardBg,
            lastTime = if (sample.timeStr.isNotEmpty()) sample.timeStr else "데이터 없음",
            isMonitoring = isMonitoring,
            sampleCount = sample.sampleCount
        )
    }

    private data class HrClass(val status: String, val emoji: String, val color: Int, val cardBg: Int)

    private fun classifyHeartRate(bpm: Long?): HrClass = when {
        bpm == null  -> HrClass("데이터 없음", "🔍", 0xFF9E9E9E.toInt(), 0xFFF5F5F5.toInt())
        bpm < 50     -> HrClass("매우 낮음 ⚠️", "🔵", 0xFF1565C0.toInt(), 0xFFE3F2FD.toInt())
        bpm < 60     -> HrClass("안정 (낮음)", "💙", 0xFF1976D2.toInt(), 0xFFE8F4FD.toInt())
        bpm in 60..79 -> HrClass("안정", "💚", 0xFF2E7D32.toInt(), 0xFFE8F5E9.toInt())
        bpm in 80..99 -> HrClass("보통", "💛", 0xFFF57F17.toInt(), 0xFFFFFDE7.toInt())
        bpm in 100..129 -> HrClass("높음 🔥", "🧡", 0xFFE64A19.toInt(), 0xFFFFF3E0.toInt())
        bpm in 130..159 -> HrClass("운동 중 💪", "❤️", 0xFFB71C1C.toInt(), 0xFFFFEBEE.toInt())
        else         -> HrClass("최고 강도 🚨", "🔴", 0xFF880E4F.toInt(), 0xFFFCE4EC.toInt())
    }
}

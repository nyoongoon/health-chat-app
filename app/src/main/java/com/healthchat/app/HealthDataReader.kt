package com.healthchat.app

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Volume
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.*
import java.time.format.DateTimeFormatter

data class HourlyData(
    val hour: Int,
    val steps: Long = 0,
    val heartRateAvg: Long? = null,
    val heartRateMin: Long? = null,
    val heartRateMax: Long? = null
)

data class SleepBlock(
    val startHour: Int,
    val startMin: Int,
    val endHour: Int,
    val endMin: Int,
    val stage: String
)

// 영양 저장 결과 + 로그
data class SaveNutritionResult(
    val success: Boolean,
    val log: String
)

data class NutritionEntry(
    val recordId: String,
    val foodName: String?,
    val calories: Double,
    val carbs: Double?,
    val protein: Double?,
    val fat: Double?,
    val mealType: Int,
    val startTime: java.time.Instant
)

data class WaterEntry(
    val recordId: String,
    val ml: Double,
    val time: java.time.Instant
)

data class WaterIntakeSummary(
    val totalMl: Double,
    val entries: List<WaterEntry>
)

data class WeeklyTrendDay(
    val date: LocalDate,
    val steps: Long?,
    val caloriesBurned: Double?,
    val sleepHours: Double?,
    val heartRateAvg: Long?,
    val waterMl: Double?,
    val nutritionCalories: Double?
)

data class HealthSummary(
    val steps: Long?,
    val heartRateAvg: Long?,
    val heartRateMin: Long?,
    val heartRateMax: Long?,
    val sleepHours: Double?,
    val caloriesBurned: Double?,
    val distance: Double?,
    val oxygenSaturation: Double?,
    val weight: Double?,
    val exercises: List<String>,
    val timestamp: String,
    val hourlyData: List<HourlyData> = emptyList(),
    val sleepBlocks: List<SleepBlock> = emptyList(),
    val lastUpdated: String = "",
    // 체성분
    val bodyFat: Double? = null,
    val leanBodyMass: Double? = null,
    val bmi: Double? = null,
    val basalMetabolicRate: Int? = null,
    val height: Double? = null
) {
    fun toDisplayText(): String {
        val sb = StringBuilder()
        sb.appendLine("[ " + lastUpdated + " 기준 ]")
        sb.appendLine()
        steps?.let { s -> sb.appendLine("걸음수: " + "%,d".format(s) + "보") }
        heartRateAvg?.let { avg ->
            sb.append("심박수: 평균 " + avg + "bpm")
            heartRateMin?.let { mn -> sb.append(" (최저 " + mn) }
            heartRateMax?.let { mx -> sb.append(" / 최고 " + mx + ")") }
            sb.appendLine()
        }
        sleepHours?.let { h -> sb.appendLine("수면: " + "%.1f".format(h) + "시간") }
        caloriesBurned?.let { c -> sb.appendLine("칼로리: " + "%.0f".format(c) + "kcal") }
        distance?.let { d -> sb.appendLine("이동거리: " + "%.1f".format(d / 1000) + "km") }
        oxygenSaturation?.let { o -> sb.appendLine("산소포화도: " + "%.1f".format(o) + "%") }
        weight?.let { w -> sb.appendLine("체중: " + "%.1f".format(w) + "kg") }
        bodyFat?.let { v -> sb.appendLine("체지방률: " + "%.1f".format(v) + "%") }
        leanBodyMass?.let { v -> sb.appendLine("골격근량: " + "%.1f".format(v) + "kg") }
        bmi?.let { v -> sb.appendLine("BMI: " + "%.1f".format(v)) }
        basalMetabolicRate?.let { v -> sb.appendLine("기초대사량: " + v + "kcal") }
        if (exercises.isNotEmpty()) {
            sb.appendLine("운동: " + exercises.joinToString(", "))
        }

        // Hourly steps chart
        val stepsHours = hourlyData.filter { it.steps > 0 }
        if (stepsHours.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- 시간대별 걸음수 ---")
            val maxSteps = stepsHours.maxOf { it.steps }.coerceAtLeast(1)
            for (h in stepsHours) {
                val barLen = ((h.steps.toDouble() / maxSteps) * 10).toInt().coerceAtLeast(1)
                val bar = "\u2588".repeat(barLen)
                val hourStr = "%02d".format(h.hour)
                sb.appendLine(hourStr + "시 " + bar + " " + h.steps)
            }
        }

        // Hourly heart rate chart
        val hrHours = hourlyData.filter { it.heartRateAvg != null }
        if (hrHours.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- 시간대별 심박수 ---")
            val minHr = hrHours.mapNotNull { it.heartRateMin }.minOrNull() ?: 40
            val maxHr = hrHours.mapNotNull { it.heartRateMax }.maxOrNull() ?: 120
            val range = (maxHr - minHr).coerceAtLeast(1)
            for (h in hrHours) {
                val avg = h.heartRateAvg ?: continue
                val barLen = (((avg - minHr).toDouble() / range) * 10).toInt().coerceIn(1, 10)
                val bar = "\u2665".repeat(barLen)
                val hourStr = "%02d".format(h.hour)
                val detail = if (h.heartRateMin != null && h.heartRateMax != null) {
                    " " + h.heartRateMin + "-" + h.heartRateMax
                } else ""
                sb.appendLine(hourStr + "시 " + bar + " " + avg + "bpm" + detail)
            }
        }

        // Sleep timeline
        if (sleepBlocks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- 수면 타임라인 ---")
            for (block in sleepBlocks) {
                val startStr = "%02d:%02d".format(block.startHour, block.startMin)
                val endStr = "%02d:%02d".format(block.endHour, block.endMin)
                val icon = when {
                    block.stage.contains("deep", true) -> "\u2B24\u2B24\u2B24"
                    block.stage.contains("light", true) -> "\u25CB\u25CB\u25CB"
                    block.stage.contains("rem", true) -> "\u2734\u2734\u2734"
                    block.stage.contains("awake", true) -> "\u2502\u2502\u2502"
                    else -> "\u25A0\u25A0\u25A0"
                }
                sb.appendLine(startStr + "-" + endStr + " " + icon + " " + block.stage)
            }
            sb.appendLine("\u2B24=깊은 \u25CB=얕은 \u2734=REM \u2502=깸")
        }

        return if (sb.length <= 20) "데이터 없음" else sb.toString().trimEnd()
    }

    fun toContextString(): String {
        val sb = StringBuilder()
        sb.appendLine("날짜: " + timestamp + " (마지막 갱신: " + lastUpdated + ")")
        steps?.let { s -> sb.appendLine("총 걸음수: " + s + "보") }
        heartRateAvg?.let { v -> sb.appendLine("평균 심박수: " + v + "bpm") }
        heartRateMin?.let { v -> sb.appendLine("최저 심박수: " + v + "bpm") }
        heartRateMax?.let { v -> sb.appendLine("최고 심박수: " + v + "bpm") }
        sleepHours?.let { v -> sb.appendLine("수면 시간: " + "%.1f".format(v) + "시간") }
        caloriesBurned?.let { v -> sb.appendLine("소모 칼로리: " + "%.0f".format(v) + "kcal") }
        distance?.let { v -> sb.appendLine("이동 거리: " + "%.0f".format(v) + "m (" + "%.1f".format(v/1000) + "km)") }
        oxygenSaturation?.let { v -> sb.appendLine("산소포화도: " + "%.1f".format(v) + "%") }
        weight?.let { v -> sb.appendLine("체중: " + "%.1f".format(v) + "kg") }
        height?.let { v -> sb.appendLine("신장: " + "%.0f".format(v * 100) + "cm") }
        bodyFat?.let { v -> sb.appendLine("체지방률: " + "%.1f".format(v) + "%") }
        leanBodyMass?.let { v -> sb.appendLine("골격근량: " + "%.1f".format(v) + "kg") }
        bmi?.let { v -> sb.appendLine("BMI: " + "%.1f".format(v)) }
        basalMetabolicRate?.let { v -> sb.appendLine("기초대사량: " + v + "kcal/일") }
        if (exercises.isNotEmpty()) {
            sb.appendLine("오늘 운동: " + exercises.joinToString(", "))
        }

        // Include hourly breakdown for Claude context
        val stepsHours = hourlyData.filter { it.steps > 0 }
        if (stepsHours.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("시간대별 걸음수:")
            for (h in stepsHours) {
                sb.appendLine("  " + "%02d".format(h.hour) + "시: " + h.steps + "보")
            }
        }

        val hrHours = hourlyData.filter { it.heartRateAvg != null }
        if (hrHours.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("시간대별 심박수:")
            for (h in hrHours) {
                val detail = if (h.heartRateMin != null && h.heartRateMax != null) {
                    " (범위: " + h.heartRateMin + "-" + h.heartRateMax + ")"
                } else ""
                sb.appendLine("  " + "%02d".format(h.hour) + "시: 평균 " + h.heartRateAvg + "bpm" + detail)
            }
        }

        if (sleepBlocks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("수면 단계:")
            for (block in sleepBlocks) {
                val startStr = "%02d:%02d".format(block.startHour, block.startMin)
                val endStr = "%02d:%02d".format(block.endHour, block.endMin)
                sb.appendLine("  " + startStr + "-" + endStr + " " + block.stage)
            }
        }

        return sb.toString().trimEnd()
    }

    fun toShortSummary(): String {
        val parts = mutableListOf<String>()
        steps?.let { s -> parts.add("%,d".format(s) + "보") }
        heartRateAvg?.let { v -> parts.add("\u2665" + v + "bpm") }
        sleepHours?.let { v -> parts.add("%,.1f".format(v) + "h수면") }
        caloriesBurned?.let { v -> parts.add("%,.0f".format(v) + "kcal") }
        val timeStr = if (lastUpdated.isNotEmpty()) " (" + lastUpdated + ")" else ""
        return if (parts.isEmpty()) "건강 데이터 없음" else parts.joinToString(" | ") + timeStr
    }
}

class HealthDataReader(private val context: Context) {

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        )

        private const val TAG = "HealthDataReader"
    }

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    suspend fun readTodayData(): HealthSummary {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val timeRange = TimeRangeFilter.between(startOfDay, now)
        val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val currentHour = LocalTime.now().hour

        // Read yesterday sleep (sleep happens overnight)
        val yesterdayStart = LocalDate.now().minusDays(1).atTime(18, 0)
            .atZone(zone).toInstant()
        val sleepTimeRange = TimeRangeFilter.between(yesterdayStart, now)

        var steps: Long? = null
        var heartRateAvg: Long? = null
        var heartRateMin: Long? = null
        var heartRateMax: Long? = null
        var sleepHours: Double? = null
        var calories: Double? = null
        var distance: Double? = null
        var oxygenSat: Double? = null
        var weight: Double? = null
        var bodyFat: Double? = null
        var leanBodyMass: Double? = null
        var bmi: Double? = null
        var basalMetabolicRate: Int? = null
        var height: Double? = null
        val exercises = mutableListOf<String>()

        // Hourly data maps
        val hourlySteps = mutableMapOf<Int, Long>()
        val hourlyHrSamples = mutableMapOf<Int, MutableList<Long>>()
        val sleepBlockList = mutableListOf<SleepBlock>()

        try {
            // Steps - with hourly breakdown
            val stepsRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(StepsRecord::class, timeRange)
            ).records
            val totalSteps = stepsRecords.sumOf { it.count }
            if (totalSteps > 0) steps = totalSteps
            for (rec in stepsRecords) {
                val hour = rec.startTime.atZone(zone).hour
                hourlySteps[hour] = (hourlySteps[hour] ?: 0L) + rec.count
            }
        } catch (e: Exception) { Log.w(TAG, "Steps read failed", e) }

        try {
            // Heart Rate - with hourly breakdown
            val hrRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, timeRange)
            ).records
            val allSamples = hrRecords.flatMap { it.samples }
            if (allSamples.isNotEmpty()) {
                heartRateAvg = allSamples.map { it.beatsPerMinute }.average().toLong()
                heartRateMin = allSamples.minOf { it.beatsPerMinute }
                heartRateMax = allSamples.maxOf { it.beatsPerMinute }
            }
            for (rec in hrRecords) {
                for (sample in rec.samples) {
                    val hour = sample.time.atZone(zone).hour
                    hourlyHrSamples.getOrPut(hour) { mutableListOf() }.add(sample.beatsPerMinute)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "HR read failed", e) }

        try {
            // Sleep with stages
            val sleepRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, sleepTimeRange)
            ).records
            if (sleepRecords.isNotEmpty()) {
                val totalMs = sleepRecords.sumOf {
                    Duration.between(it.startTime, it.endTime).toMillis()
                }
                sleepHours = totalMs / 3600000.0

                for (session in sleepRecords) {
                    if (session.stages.isNotEmpty()) {
                        for (stage in session.stages) {
                            val stZoned = stage.startTime.atZone(zone)
                            val enZoned = stage.endTime.atZone(zone)
                            val stageName = when (stage.stage) {
                                SleepSessionRecord.STAGE_TYPE_DEEP -> "깊은수면"
                                SleepSessionRecord.STAGE_TYPE_LIGHT -> "얕은수면"
                                SleepSessionRecord.STAGE_TYPE_REM -> "REM"
                                SleepSessionRecord.STAGE_TYPE_AWAKE -> "깸"
                                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "침대깸"
                                else -> "수면"
                            }
                            sleepBlockList.add(SleepBlock(
                                stZoned.hour, stZoned.minute,
                                enZoned.hour, enZoned.minute,
                                stageName
                            ))
                        }
                    } else {
                        val stZoned = session.startTime.atZone(zone)
                        val enZoned = session.endTime.atZone(zone)
                        sleepBlockList.add(SleepBlock(
                            stZoned.hour, stZoned.minute,
                            enZoned.hour, enZoned.minute,
                            "수면"
                        ))
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Sleep read failed", e) }

        try {
            val calRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRange)
            ).records
            val totalCal = calRecords.sumOf { it.energy.inKilocalories }
            if (totalCal > 0) calories = totalCal
        } catch (e: Exception) { Log.w(TAG, "Calories read failed", e) }

        try {
            val distRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(DistanceRecord::class, timeRange)
            ).records
            val totalDist = distRecords.sumOf { it.distance.inMeters }
            if (totalDist > 0) distance = totalDist
        } catch (e: Exception) { Log.w(TAG, "Distance read failed", e) }

        try {
            val spo2Records = healthConnectClient.readRecords(
                ReadRecordsRequest(OxygenSaturationRecord::class, timeRange)
            ).records
            if (spo2Records.isNotEmpty()) {
                oxygenSat = spo2Records.last().percentage.value
            }
        } catch (e: Exception) { Log.w(TAG, "SpO2 read failed", e) }

        // 체성분은 최근 30일 기준 (삼성 헬스가 자주 측정하지 않으므로)
        val monthAgo = LocalDate.now().minusDays(30).atStartOfDay(zone).toInstant()
        val monthRange = TimeRangeFilter.between(monthAgo, now)

        try {
            val weekAgo = LocalDate.now().minusDays(7).atStartOfDay(zone).toInstant()
            val weightRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(weekAgo, now))
            ).records
            if (weightRecords.isNotEmpty()) {
                weight = weightRecords.last().weight.inKilograms
            }
        } catch (e: Exception) { Log.w(TAG, "Weight read failed", e) }

        try {
            val heightRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(HeightRecord::class, monthRange)
            ).records
            Log.d(TAG, "Height records: ${heightRecords.size}")
            if (heightRecords.isNotEmpty()) {
                height = heightRecords.last().height.inMeters
                Log.d(TAG, "Height: $height m")
            }
        } catch (e: Exception) { Log.w(TAG, "Height read failed: ${e.message}") }

        try {
            val bodyFatRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(BodyFatRecord::class, monthRange)
            ).records
            Log.d(TAG, "BodyFat records: ${bodyFatRecords.size}")
            if (bodyFatRecords.isNotEmpty()) {
                bodyFat = bodyFatRecords.last().percentage.value
                Log.d(TAG, "BodyFat: $bodyFat %")
            }
        } catch (e: Exception) { Log.w(TAG, "BodyFat read failed: ${e.message}") }

        try {
            val lbmRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(LeanBodyMassRecord::class, monthRange)
            ).records
            Log.d(TAG, "LeanBodyMass records: ${lbmRecords.size}")
            if (lbmRecords.isNotEmpty()) {
                leanBodyMass = lbmRecords.last().mass.inKilograms
                Log.d(TAG, "LeanBodyMass: $leanBodyMass kg")
            }
        } catch (e: Exception) { Log.w(TAG, "LeanBodyMass read failed: ${e.message}") }

        try {
            val bmrRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(BasalMetabolicRateRecord::class, monthRange)
            ).records
            Log.d(TAG, "BMR records: ${bmrRecords.size}")
            if (bmrRecords.isNotEmpty()) {
                basalMetabolicRate = bmrRecords.last().basalMetabolicRate.inKilocaloriesPerDay.toInt()
                Log.d(TAG, "BMR: $basalMetabolicRate kcal/day")
            }
        } catch (e: Exception) { Log.w(TAG, "BMR read failed: ${e.message}") }

        // BMI 계산 (체중 + 신장)
        if (weight != null && height != null && height!! > 0) {
            bmi = weight!! / (height!! * height!!)
            Log.d(TAG, "BMI calculated: $bmi (weight=$weight, height=$height)")
        }

        try {
            // 최근 7일치 운동 데이터 읽기
            val weekAgoExercise = LocalDate.now().minusDays(6).atStartOfDay(zone).toInstant()
            val exRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(weekAgoExercise, now))
            ).records
            Log.d(TAG, "Exercise records found: ${exRecords.size}")
            for (ex in exRecords) {
                val duration = Duration.between(ex.startTime, ex.endTime).toMinutes()
                val type = exerciseTypeName(ex.exerciseType)
                val exZoned = ex.startTime.atZone(zone)
                val today = LocalDate.now()
                val exDate = exZoned.toLocalDate()
                val dateLabel = when {
                    exDate == today -> ""
                    exDate == today.minusDays(1) -> "어제 "
                    else -> exDate.format(DateTimeFormatter.ofPattern("M/d")) + " "
                }
                val startTime = exZoned.format(DateTimeFormatter.ofPattern("HH:mm"))
                exercises.add(dateLabel + type + " " + duration + "분 (" + startTime + ")")
            }
        } catch (e: Exception) { Log.w(TAG, "Exercise read failed", e) }

        // Build hourly data list
        val allHours = (hourlySteps.keys + hourlyHrSamples.keys).toSortedSet()
        val hourlyList = allHours.map { hour ->
            val hrSamples = hourlyHrSamples[hour]
            HourlyData(
                hour = hour,
                steps = hourlySteps[hour] ?: 0,
                heartRateAvg = hrSamples?.map { it }?.average()?.toLong(),
                heartRateMin = hrSamples?.minOrNull(),
                heartRateMax = hrSamples?.maxOrNull()
            )
        }

        return HealthSummary(
            steps = steps,
            heartRateAvg = heartRateAvg,
            heartRateMin = heartRateMin,
            heartRateMax = heartRateMax,
            sleepHours = sleepHours,
            caloriesBurned = calories,
            distance = distance,
            oxygenSaturation = oxygenSat,
            weight = weight,
            exercises = exercises,
            timestamp = dateStr,
            hourlyData = hourlyList,
            sleepBlocks = sleepBlockList,
            lastUpdated = timeStr,
            bodyFat = bodyFat,
            leanBodyMass = leanBodyMass,
            bmi = bmi,
            basalMetabolicRate = basalMetabolicRate,
            height = height
        )
    }

    // Health Connect용 음식명 정제 (특수문자, 너무 긴 이름 제거)
    private fun sanitizeFoodName(raw: String): String {
        return raw
            .split("+", "＋").first()   // '카레라이스 2인분 + 김치' → '카레라이스 2인분'
            .replace(Regex("[^가-힣a-zA-Z0-9 ()]"), "")  // 특수문자 제거
            .trim()
            .take(40)  // 최대 40자
            .ifBlank { "음식" }
    }

    suspend fun saveNutritionWithLog(
        foodName: String,
        calories: Double,
        carbs: Double?,
        protein: Double?,
        fat: Double?,
        mealTypeStr: String?
    ): SaveNutritionResult {
        val logs = StringBuilder()
        logs.appendLine("▶▶ Health Connect insertRecords 호출 (HC 1.1.0 / API36)")
        try {
            // 1단계: WRITE_NUTRITION 권한 실제 부여 여부 확인
            val grantedPerms = healthConnectClient.permissionController.getGrantedPermissions()
            val writeNutritionPerm = HealthPermission.getWritePermission(NutritionRecord::class)
            val hasWritePerm = grantedPerms.contains(writeNutritionPerm)
            logs.appendLine("  • WRITE_NUTRITION 권한: ${if (hasWritePerm) "✅ 부여됨" else "❌ 미부여"}")
            logs.appendLine("  • 부여된 전체 권한 수: ${grantedPerms.size}")
            if (!hasWritePerm) {
                logs.appendLine("  ❌ WRITE_NUTRITION 권한 없음 - Health Connect 앱에서 허용 필요")
                return SaveNutritionResult(false, logs.toString().trimEnd())
            }

            // 과거 시간 사용 (Health Connect는 미래 시간 거부)
            val endTime = Instant.now()
            val startTime = endTime.minusSeconds(300)  // 5분 전
            val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(endTime)
            logs.appendLine("  • 시간: $startTime ~ $endTime / 타임존: $zoneOffset")
            logs.appendLine("  • 칼로리: ${calories}kcal, 탄: ${carbs}g, 단: ${protein}g, 지: ${fat}g")

            // mealTypeStr → MealType 변환 (사용자 선택 우선, 없으면 시간대 기반)
            val resolvedMealType = when (mealTypeStr?.lowercase()) {
                "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
                "lunch" -> MealType.MEAL_TYPE_LUNCH
                "dinner" -> MealType.MEAL_TYPE_DINNER
                "snack" -> MealType.MEAL_TYPE_SNACK
                else -> {
                    val hour = java.time.LocalTime.now().hour
                    when {
                        hour in 6..10 -> MealType.MEAL_TYPE_BREAKFAST
                        hour in 11..14 -> MealType.MEAL_TYPE_LUNCH
                        hour in 17..21 -> MealType.MEAL_TYPE_DINNER
                        else -> MealType.MEAL_TYPE_SNACK
                    }
                }
            }
            logs.appendLine("  • 식사 타입: ${mealTypeStr ?: "자동"} → $resolvedMealType")

            // 시도1: ZoneOffset 명시 + 전체 필드 (삼성헬스 호환 최적 형식)
            logs.appendLine("  • 시도1: ZoneOffset=${zoneOffset}, 전체 필드, mealType=${resolvedMealType}...")
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val fullRecord = NutritionRecord(
                        startTime = startTime,
                        endTime = endTime,
                        startZoneOffset = zoneOffset,
                        endZoneOffset = zoneOffset,
                        energy = Energy.kilocalories(calories.coerceAtLeast(1.0)),
                        totalCarbohydrate = carbs?.coerceAtLeast(0.0)?.let { Mass.grams(it) },
                        protein = protein?.coerceAtLeast(0.0)?.let { Mass.grams(it) },
                        totalFat = fat?.coerceAtLeast(0.0)?.let { Mass.grams(it) },
                        name = sanitizeFoodName(foodName).ifBlank { null },
                        mealType = resolvedMealType
                    )
                    healthConnectClient.insertRecords(listOf(fullRecord))
                }
                logs.appendLine("  ✅ 시도1 성공!")
                return SaveNutritionResult(true, logs.toString().trimEnd())
            } catch (e1: Exception) {
                logs.appendLine("  ❌ 시도1 실패: ${e1.javaClass.simpleName}: ${e1.message?.take(150)}")
                Log.e(TAG, "시도1 실패", e1)
            }

            // 시도2: null ZoneOffset + energy 전용 (폴백)
            logs.appendLine("  • 시도2: null ZoneOffset, energy 전용...")
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val minRecord = NutritionRecord(
                        startTime = startTime,
                        endTime = endTime,
                        startZoneOffset = null,
                        endZoneOffset = null,
                        energy = Energy.kilocalories(calories.coerceAtLeast(1.0))
                    )
                    healthConnectClient.insertRecords(listOf(minRecord))
                }
                logs.appendLine("  ✅ 시도2 성공!")
                return SaveNutritionResult(true, logs.toString().trimEnd())
            } catch (e2: Exception) {
                logs.appendLine("  ❌ 시도2 실패: ${e2.javaClass.simpleName}: ${e2.message?.take(150)}")
                Log.e(TAG, "시도2 실패", e2)
            }

            // 시도3: Energy.joules() 단위 변환
            logs.appendLine("  • 시도3: Energy.joules() 단위...")
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val joulesRecord = NutritionRecord(
                        startTime = startTime,
                        endTime = endTime,
                        startZoneOffset = null,
                        endZoneOffset = null,
                        energy = Energy.joules(calories.coerceAtLeast(1.0) * 4184.0)
                    )
                    healthConnectClient.insertRecords(listOf(joulesRecord))
                }
                logs.appendLine("  ✅ 시도3 성공!")
                return SaveNutritionResult(true, logs.toString().trimEnd())
            } catch (e3: Exception) {
                logs.appendLine("  ❌ 시도3 실패: ${e3.javaClass.simpleName}: ${e3.message?.take(150)}")
                Log.e(TAG, "시도3 실패", e3)
            }

            // 시도4: Samsung Health SDK 서비스 바인딩 (IHealthDataStore)
            logs.appendLine("  • 시도4: Samsung Health SDK 서비스 바인딩...")
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    trySamsungHealthSDK(foodName, calories, carbs, protein, fat, logs)
                }
                if (result) {
                    logs.appendLine("  ✅ 시도4 Samsung Health SDK 성공!")
                    return SaveNutritionResult(true, logs.toString().trimEnd())
                }
            } catch (e4: Exception) {
                logs.appendLine("  ❌ 시도4 실패: ${e4.javaClass.simpleName}: ${e4.message?.take(150)}")
                Log.e(TAG, "시도4 실패", e4)
            }

            // 시도5: 1시간 전 시간으로 재시도
            logs.appendLine("  • 시도5: 1시간 전 시간, 최소 필드...")
            try {
                val endTime2 = Instant.now().minusSeconds(3600)
                val startTime2 = endTime2.minusSeconds(300)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val record = NutritionRecord(
                        startTime = startTime2,
                        endTime = endTime2,
                        startZoneOffset = null,
                        endZoneOffset = null,
                        energy = Energy.kilocalories(calories.coerceAtLeast(1.0))
                    )
                    healthConnectClient.insertRecords(listOf(record))
                }
                logs.appendLine("  ✅ 시도5 성공!")
                return SaveNutritionResult(true, logs.toString().trimEnd())
            } catch (e5: Exception) {
                logs.appendLine("  ❌ 시도5 실패: ${e5.javaClass.simpleName}: ${e5.message?.take(150)}")
            }

            logs.appendLine("  ❌ 모든 시도 실패")
            return SaveNutritionResult(false, logs.toString().trimEnd())

        } catch (e: SecurityException) {
            logs.appendLine("  ❌ SecurityException: 권한 부족")
            logs.appendLine("     ${e.message}")
            Log.e(TAG, "NutritionRecord save failed - SecurityException", e)
            return SaveNutritionResult(false, logs.toString().trimEnd())

        } catch (e: IllegalArgumentException) {
            logs.appendLine("  ❌ IllegalArgumentException: ${e.message?.take(150)}")
            Log.e(TAG, "NutritionRecord save failed - IllegalArgumentException", e)
            return SaveNutritionResult(false, logs.toString().trimEnd())

        } catch (e: Exception) {
            logs.appendLine("  ❌ ${e.javaClass.simpleName}: ${e.message}")
            logs.appendLine("     StackTrace: ${e.stackTraceToString().take(300)}")
            Log.e(TAG, "NutritionRecord save failed", e)
            return SaveNutritionResult(false, logs.toString().trimEnd())
        }
    }

    // Samsung Health SDK 직접 바인딩 시도
    private suspend fun trySamsungHealthSDK(
        foodName: String,
        calories: Double,
        carbs: Double?,
        protein: Double?,
        fat: Double?,
        logs: StringBuilder
    ): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val intent = android.content.Intent("com.samsung.android.sdk.healthdata.IHealthDataStore")
            intent.setPackage("com.sec.android.app.shealth")

            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    logs.appendLine("    Samsung Health 서비스 연결됨: $name")
                    try {
                        context.unbindService(this)
                    } catch (e: Exception) {}
                    // 연결 확인만 - 실제 AIDL 없이는 데이터 삽입 불가
                    cont.resume(false) {}
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    logs.appendLine("    Samsung Health 서비스 연결 끊김")
                    if (cont.isActive) cont.resume(false) {}
                }
            }
            val bound = try {
                context.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                logs.appendLine("    bindService 실패: ${e.message}")
                false
            }
            if (!bound) {
                logs.appendLine("    bindService 반환 false (서비스 없음)")
                cont.resume(false) {}
            }
        }
    }

    // 하위 호환성을 위한 기존 함수
    suspend fun saveNutrition(
        foodName: String,
        calories: Double,
        carbs: Double?,
        protein: Double?,
        fat: Double?,
        mealTypeStr: String?
    ): Boolean {
        val result = saveNutritionWithLog(foodName, calories, carbs, protein, fat, mealTypeStr)
        return result.success
    }

    // ==================== 식사 기록 읽기 / 삭제 ====================

    suspend fun readTodayNutrition(): List<NutritionEntry> {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val now = java.time.Instant.now()
        return try {
            healthConnectClient.readRecords(
                ReadRecordsRequest(NutritionRecord::class, TimeRangeFilter.between(startOfDay, now))
            ).records.map { rec ->
                NutritionEntry(
                    recordId = rec.metadata.id,
                    foodName = rec.name,
                    calories = rec.energy?.inKilocalories ?: 0.0,
                    carbs = rec.totalCarbohydrate?.inGrams,
                    protein = rec.protein?.inGrams,
                    fat = rec.totalFat?.inGrams,
                    mealType = rec.mealType,
                    startTime = rec.startTime
                )
            }.sortedBy { it.startTime }
        } catch (e: Exception) {
            Log.w(TAG, "readTodayNutrition failed", e)
            emptyList()
        }
    }

    suspend fun deleteNutritionRecord(recordId: String): Boolean {
        return try {
            healthConnectClient.deleteRecords(
                NutritionRecord::class,
                listOf(recordId),
                emptyList()
            )
            Log.d(TAG, "deleteNutritionRecord success: $recordId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteNutritionRecord failed", e)
            false
        }
    }

    // ==================== 수분 섭취 ====================

    suspend fun readTodayWater(): WaterIntakeSummary {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val now = java.time.Instant.now()
        return try {
            val records = healthConnectClient.readRecords(
                ReadRecordsRequest(HydrationRecord::class, TimeRangeFilter.between(startOfDay, now))
            ).records
            val entries = records.map { rec ->
                WaterEntry(
                    recordId = rec.metadata.id,
                    ml = rec.volume.inLiters * 1000.0,
                    time = rec.startTime
                )
            }
            WaterIntakeSummary(totalMl = entries.sumOf { it.ml }, entries = entries)
        } catch (e: Exception) {
            Log.w(TAG, "readTodayWater failed", e)
            WaterIntakeSummary(0.0, emptyList())
        }
    }

    suspend fun saveWaterIntake(ml: Double): Boolean {
        return try {
            val now = java.time.Instant.now()
            val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(now)
            healthConnectClient.insertRecords(listOf(
                HydrationRecord(
                    startTime = now.minusSeconds(60),
                    endTime = now,
                    startZoneOffset = zoneOffset,
                    endZoneOffset = zoneOffset,
                    volume = Volume.liters(ml / 1000.0)
                )
            ))
            Log.d(TAG, "saveWaterIntake success: ${ml}ml")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveWaterIntake failed", e)
            false
        }
    }

    // ==================== HRV ====================

    suspend fun readTodayHrv(): Double? {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val now = java.time.Instant.now()
        return try {
            val records = healthConnectClient.readRecords(
                ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(startOfDay, now))
            ).records
            if (records.isNotEmpty()) records.last().heartRateVariabilityMillis else null
        } catch (e: Exception) {
            Log.w(TAG, "readTodayHrv failed", e)
            null
        }
    }

    // ==================== 주간 트렌드 ====================

    suspend fun readWeeklyTrends(): List<WeeklyTrendDay> {
        val zone = ZoneId.systemDefault()
        val now = java.time.Instant.now()
        val sevenDaysAgo = LocalDate.now().minusDays(6).atStartOfDay(zone).toInstant()
        val timeRange = TimeRangeFilter.between(sevenDaysAgo, now)
        val sleepRange = TimeRangeFilter.between(LocalDate.now().minusDays(7).atTime(18, 0).atZone(zone).toInstant(), now)

        val stepsRecs = try { healthConnectClient.readRecords(ReadRecordsRequest(StepsRecord::class, timeRange)).records } catch (e: Exception) { emptyList() }
        val calRecs = try { healthConnectClient.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRange)).records } catch (e: Exception) { emptyList() }
        val sleepRecs = try { healthConnectClient.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepRange)).records } catch (e: Exception) { emptyList() }
        val hrRecs = try { healthConnectClient.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeRange)).records } catch (e: Exception) { emptyList() }
        val waterRecs = try { healthConnectClient.readRecords(ReadRecordsRequest(HydrationRecord::class, timeRange)).records } catch (e: Exception) { emptyList() }
        val nutritionRecs = try { healthConnectClient.readRecords(ReadRecordsRequest(NutritionRecord::class, timeRange)).records } catch (e: Exception) { emptyList() }

        val stepsByDay = stepsRecs.groupBy { it.startTime.atZone(zone).toLocalDate() }
        val calByDay = calRecs.groupBy { it.startTime.atZone(zone).toLocalDate() }
        val hrByDay = hrRecs.groupBy { it.startTime.atZone(zone).toLocalDate() }
        val waterByDay = waterRecs.groupBy { it.startTime.atZone(zone).toLocalDate() }
        val nutritionByDay = nutritionRecs.groupBy { it.startTime.atZone(zone).toLocalDate() }

        val sleepByDay = mutableMapOf<LocalDate, Double>()
        for (s in sleepRecs) {
            val wakeDate = s.endTime.atZone(zone).toLocalDate()
            sleepByDay[wakeDate] = (sleepByDay[wakeDate] ?: 0.0) +
                Duration.between(s.startTime, s.endTime).toMillis() / 3600000.0
        }

        return (6 downTo 0).map { i ->
            val date = LocalDate.now().minusDays(i.toLong())
            val hrSamples = hrByDay[date]?.flatMap { it.samples }
            WeeklyTrendDay(
                date = date,
                steps = stepsByDay[date]?.sumOf { it.count }?.takeIf { it > 0 },
                caloriesBurned = calByDay[date]?.sumOf { it.energy.inKilocalories }?.takeIf { it > 0 },
                sleepHours = sleepByDay[date],
                heartRateAvg = if (hrSamples != null && hrSamples.isNotEmpty()) hrSamples.map { it.beatsPerMinute }.average().toLong() else null,
                waterMl = waterByDay[date]?.sumOf { it.volume.inLiters * 1000 }?.takeIf { it > 0 },
                nutritionCalories = nutritionByDay[date]?.sumOf { it.energy?.inKilocalories ?: 0.0 }?.takeIf { it > 0 }
            )
        }
    }

    // ==================== AI 코치 종합 컨텍스트 ====================

    suspend fun buildComprehensiveAiCoachContext(): String {
        val sb = StringBuilder()
        try {
            val summary = readTodayData()
            sb.appendLine("=== 오늘 건강 데이터 ===")
            sb.appendLine(summary.toContextString())
        } catch (e: Exception) { Log.w(TAG, "coach: health data failed", e) }

        try {
            val nutrition = readTodayNutrition()
            if (nutrition.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("=== 오늘 식사 기록 ===")
                sb.appendLine("총 섭취 칼로리: ${"%.0f".format(nutrition.sumOf { it.calories })}kcal")
                for (n in nutrition) {
                    val mealStr = mealTypeToKorean(n.mealType)
                    sb.append("$mealStr: ${n.foodName ?: "음식"} ${"%.0f".format(n.calories)}kcal")
                    val nutrients = listOfNotNull(
                        n.carbs?.let { "탄${"%.1f".format(it)}g" },
                        n.protein?.let { "단${"%.1f".format(it)}g" },
                        n.fat?.let { "지${"%.1f".format(it)}g" }
                    ).joinToString(" ")
                    if (nutrients.isNotEmpty()) sb.append(" ($nutrients)")
                    sb.appendLine()
                }
            }
        } catch (e: Exception) { Log.w(TAG, "coach: nutrition failed", e) }

        try {
            val water = readTodayWater()
            if (water.totalMl > 0) {
                sb.appendLine()
                sb.appendLine("=== 수분 섭취 ===")
                sb.appendLine("오늘 수분: ${"%.0f".format(water.totalMl)}ml / 목표 2000ml")
            }
        } catch (e: Exception) { Log.w(TAG, "coach: water failed", e) }

        try {
            readTodayHrv()?.let { sb.appendLine("HRV: ${"%.1f".format(it)}ms") }
        } catch (e: Exception) {}

        try {
            val trends = readWeeklyTrends()
            if (trends.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("=== 주간 트렌드 (7일) ===")
                for (day in trends) {
                    val dayStr = day.date.format(DateTimeFormatter.ofPattern("M/d(E)", java.util.Locale.KOREAN))
                    val parts = mutableListOf<String>()
                    day.steps?.let { parts.add("${"%,d".format(it)}보") }
                    day.caloriesBurned?.let { parts.add("소모${"%.0f".format(it)}kcal") }
                    day.sleepHours?.let { parts.add("수면${"%.1f".format(it)}h") }
                    day.nutritionCalories?.let { parts.add("섭취${"%.0f".format(it)}kcal") }
                    sb.appendLine("$dayStr: ${if (parts.isEmpty()) "데이터없음" else parts.joinToString(", ")}")
                }
            }
        } catch (e: Exception) { Log.w(TAG, "coach: weekly trends failed", e) }

        return sb.toString().trimEnd()
    }

    fun mealTypeToKorean(mealType: Int): String = when (mealType) {
        MealType.MEAL_TYPE_BREAKFAST -> "🌅 아침"
        MealType.MEAL_TYPE_LUNCH -> "☀️ 점심"
        MealType.MEAL_TYPE_DINNER -> "🌙 저녁"
        MealType.MEAL_TYPE_SNACK -> "🍪 간식"
        else -> "🍽️ 식사"
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "달리기"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "러닝머신"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "걷기"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "자전거"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "실내자전거"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "오픈워터수영"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "수영"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "등산"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "요가"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "웨이트"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "근력운동"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "맨몸운동"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "댄스"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "일립티컬"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "로잉머신"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "계단오르기"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "스텝퍼"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "필라테스"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "스트레칭"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "배드민턴"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "농구"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "미식축구"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> "풋살"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "축구"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "테니스"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "탁구"
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "배구"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "골프"
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> "체조"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "무술"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "복싱"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "스키"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "스노보드"
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "스케이팅"
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "암벽등반"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "조정"
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "서핑"
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> "호흡운동"
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "운동수업"
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "크리켓"
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> "야구"
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL -> "소프트볼"
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> "럭비"
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC -> "프리스비"
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "기타운동"
        else -> "운동(#$type)"
    }
}

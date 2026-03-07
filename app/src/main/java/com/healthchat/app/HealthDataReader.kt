package com.healthchat.app

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.units.Power
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

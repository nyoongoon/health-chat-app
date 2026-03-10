package com.healthchat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.setPadding
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var healthReader: HealthDataReader
    private lateinit var chatClient: ChatApiClient

    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: View
    private lateinit var btnCamera: View
    private lateinit var btnHealthMenu: View
    private lateinit var btnSessionMenu: View
    private lateinit var btnDebug: View  // 디버그 버튼

    // 디버그 로그 버퍼
    private val debugLogs = StringBuilder()
    private lateinit var healthCategoryScroll: HorizontalScrollView
    private lateinit var healthCategoryContainer: LinearLayout
    private lateinit var sessionsPanelContainer: LinearLayout
    private lateinit var sessionsRecyclerView: RecyclerView
    private lateinit var btnNewSession: Button
    private lateinit var btnClosePanel: ImageView
    private lateinit var tvSessionSubtitle: TextView

    private var currentHealthContext: String? = null
    private var currentSummary: HealthSummary? = null
    private var categoryMenuOpen = false
    private var sessionsPanelOpen = false

    // 실시간 심박수 모니터
    private lateinit var heartRateMonitor: HeartRateMonitor
    private lateinit var tvLiveHeartRate: TextView
    private lateinit var tvHeartRateStatus: TextView
    private lateinit var tvHeartRateTime: TextView
    private lateinit var tvHeartBeatIcon: TextView
    private lateinit var btnToggleHrMonitor: TextView
    private lateinit var tvHrPollLabel: TextView
    private lateinit var cardLiveHeartRate: android.view.ViewGroup
    private var heartBeatAnimator: android.animation.ObjectAnimator? = null

    // Tab navigation
    private lateinit var tabHome: View
    private lateinit var tabChat: View
    private lateinit var tabRecords: View
    private lateinit var tabProfile: View
    private var currentTab = "home"

    // Dashboard views
    private lateinit var metricsContainer: LinearLayout
    private lateinit var tvHealthScoreValue: TextView
    private lateinit var tvHealthScoreGrade: TextView
    private lateinit var pbHealthScore: ProgressBar
    private lateinit var tvSleepScoreSmall: TextView
    private lateinit var tvStressLevelSmall: TextView
    private lateinit var tvHealthScoreBadge: TextView
    private lateinit var tvTopBarDate: TextView
    private lateinit var tvTopBarTitle: TextView
    private lateinit var tvSleepSummary: TextView
    private lateinit var tvNutritionSummary: TextView
    private lateinit var tvExerciseSummary: TextView
    private lateinit var recordsContent: LinearLayout
    private lateinit var profileContent: LinearLayout
    private lateinit var btnBell: View

    // Bottom nav
    private lateinit var navHomeIcon: ImageView
    private lateinit var navChatIcon: ImageView
    private lateinit var navRecordsIcon: ImageView
    private lateinit var navProfileIcon: ImageView
    private lateinit var navHomeLabel: TextView
    private lateinit var navChatLabel: TextView
    private lateinit var navRecordsLabel: TextView
    private lateinit var navProfileLabel: TextView

    private var currentSessionId: String? = null
    private var userMessageCount = 0

    // Typing indicator
    private var typingView: View? = null
    private val typingHandler = Handler(Looper.getMainLooper())
    private var typingRunnable: Runnable? = null
    private var typingDotCount = 1

    private val panelWidth by lazy { dpToPx(260) }

    // Camera/Gallery
    private var cameraImageUri: Uri? = null
    private var selectedImageUri: Uri? = null
    private var selectedImageMimeType: String? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraImageUri?.let { uri -> selectImage(uri, "image/jpeg") }
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectImage(it, "image/jpeg") }
    }

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
    }

    data class HealthCategory(val emoji: String, val label: String, val key: String)

    private val categories = listOf(
        HealthCategory("🍽️", "식사기록", "food_log"),
        HealthCategory("💧", "수분", "water"),
        HealthCategory("🤖", "AI코치", "ai_coach"),
        HealthCategory("📊", "주간트렌드", "weekly"),
        HealthCategory("\uD83D\uDC63", "걸음수", "steps"),
        HealthCategory("\u2665", "심박수", "heartrate"),
        HealthCategory("\uD83D\uDCA4", "수면", "sleep"),
        HealthCategory("\uD83D\uDD25", "칼로리", "calories"),
        HealthCategory("\uD83D\uDCCF", "이동거리", "distance"),
        HealthCategory("\uD83E\uDE78", "산소포화도", "spo2"),
        HealthCategory("\u2696", "체중", "weight"),
        HealthCategory("\uD83C\uDFCB", "운동", "exercise"),
        HealthCategory("\uD83E\uDDEC", "체성분", "body"),
        HealthCategory("\uD83D\uDCCA", "전체요약", "all"),
        HealthCategory("🩺", "혈압", "blood_pressure"),
        HealthCategory("🩸", "혈당", "blood_glucose"),
        HealthCategory("🌬️", "호흡수", "respiratory"),
        HealthCategory("🏃", "VO2Max", "vo2max"),
        HealthCategory("🏢", "층계", "floors"),
        HealthCategory("🔥", "활동칼로리", "active_calories"),
        HealthCategory("🧬", "건강점수", "health_score"),
        HealthCategory("💤", "수면점수", "sleep_score"),
        HealthCategory("🥗", "영양분석", "nutrition_analysis"),
        HealthCategory("😤", "스트레스", "stress"),
    )

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthDataReader.PERMISSIONS)) {
            loadHealthData()
        } else {
            addSystemMessage("건강 권한이 필요합니다. 설정에서 허용해주세요.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        healthReader = HealthDataReader(this)
        chatClient = ChatApiClient()
        heartRateMonitor = HeartRateMonitor(healthReader)

        chatContainer = findViewById(R.id.chatContainer)
        scrollView = findViewById(R.id.scrollView)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnCamera = findViewById(R.id.btnCamera)
        btnDebug = findViewById(R.id.btnDebug)
        btnHealthMenu = findViewById(R.id.btnHealthMenu)
        btnSessionMenu = findViewById(R.id.btnSessionMenu)
        healthCategoryScroll = findViewById(R.id.healthCategoryScroll)
        healthCategoryContainer = findViewById(R.id.healthCategoryContainer)
        sessionsPanelContainer = findViewById(R.id.sessionsPanelContainer)
        sessionsRecyclerView = findViewById(R.id.sessionsRecyclerView)
        btnNewSession = findViewById(R.id.btnNewSession)
        btnClosePanel = findViewById(R.id.btnClosePanel)
        tvSessionSubtitle = findViewById(R.id.tvSessionSubtitle)

        btnSend.setOnClickListener { sendMessage() }

        btnCamera.setOnClickListener { showImagePickerDialog() }

        btnDebug.setOnClickListener { showDebugLog() }

        btnHealthMenu.setOnClickListener {
            categoryMenuOpen = !categoryMenuOpen
            healthCategoryScroll.visibility = if (categoryMenuOpen) View.VISIBLE else View.GONE
        }

        btnSessionMenu.setOnClickListener {
            if (sessionsPanelOpen) closeSessionPanel() else openSessionPanel()
        }

        btnClosePanel.setOnClickListener { closeSessionPanel() }
        btnNewSession.setOnClickListener { newChatSession() }

        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        // Tab navigation setup
        tabHome = findViewById(R.id.tabHome)
        tabChat = findViewById(R.id.tabChat)
        tabRecords = findViewById(R.id.tabRecords)
        tabProfile = findViewById(R.id.tabProfile)
        metricsContainer = findViewById(R.id.metricsContainer)
        tvHealthScoreValue = findViewById(R.id.tvHealthScoreValue)
        tvHealthScoreGrade = findViewById(R.id.tvHealthScoreGrade)
        pbHealthScore = findViewById(R.id.pbHealthScore)
        tvSleepScoreSmall = findViewById(R.id.tvSleepScoreSmall)
        tvStressLevelSmall = findViewById(R.id.tvStressLevelSmall)
        tvHealthScoreBadge = findViewById(R.id.tvHealthScoreBadge)
        tvTopBarDate = findViewById(R.id.tvTopBarDate)
        tvTopBarTitle = findViewById(R.id.tvTopBarTitle)
        tvSleepSummary = findViewById(R.id.tvSleepSummary)
        tvNutritionSummary = findViewById(R.id.tvNutritionSummary)
        tvExerciseSummary = findViewById(R.id.tvExerciseSummary)
        recordsContent = findViewById(R.id.recordsContent)
        profileContent = findViewById(R.id.profileContent)
        btnBell = findViewById(R.id.btnBell)

        navHomeIcon = findViewById(R.id.navHomeIcon)
        navChatIcon = findViewById(R.id.navChatIcon)
        navRecordsIcon = findViewById(R.id.navRecordsIcon)
        navProfileIcon = findViewById(R.id.navProfileIcon)
        navHomeLabel = findViewById(R.id.navHomeLabel)
        navChatLabel = findViewById(R.id.navChatLabel)
        navRecordsLabel = findViewById(R.id.navRecordsLabel)
        navProfileLabel = findViewById(R.id.navProfileLabel)

        // 실시간 심박수 뷰 바인딩
        tvLiveHeartRate = findViewById(R.id.tvLiveHeartRate)
        tvHeartRateStatus = findViewById(R.id.tvHeartRateStatus)
        tvHeartRateTime = findViewById(R.id.tvHeartRateTime)
        tvHeartBeatIcon = findViewById(R.id.tvHeartBeatIcon)
        btnToggleHrMonitor = findViewById(R.id.btnToggleHrMonitor)
        tvHrPollLabel = findViewById(R.id.tvHrPollLabel)
        cardLiveHeartRate = findViewById(R.id.cardLiveHeartRate)
        setupHeartRateMonitor()

        // Bottom tab listeners
        findViewById<View>(R.id.btnTabHome).setOnClickListener { switchTab("home") }
        findViewById<View>(R.id.btnTabChat).setOnClickListener { switchTab("chat") }
        findViewById<View>(R.id.btnTabRecords).setOnClickListener { switchTab("records") }
        findViewById<View>(R.id.btnTabProfile).setOnClickListener { switchTab("profile") }

        // Date in top bar
        val today = java.time.LocalDate.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("M월 d일 (E)", java.util.Locale.KOREAN)
        tvTopBarDate.text = today.format(formatter)

        switchTab("home")
        buildProfileTab()

        buildCategoryChips()

        if (!healthReader.isAvailable()) {
            addSystemMessage("Health Connect가 필요합니다. 삼성 헬스 데이터를 읽으려면 Health Connect를 설치해주세요.")
        } else {
            lifecycleScope.launch {
                if (healthReader.hasPermissions()) loadHealthData()
                else requestPermissions.launch(HealthDataReader.PERMISSIONS)
            }
        }

        lifecycleScope.launch {
            val connected = chatClient.checkHealth()
            if (connected) {
                val (_, current) = chatClient.getSessions()
                currentSessionId = current
                val history = chatClient.getHistory()
                if (history.isNotEmpty()) {
                    addSystemMessage("이전 대화 ${history.size}개")
                    for (msg in history) {
                        addChatBubble(msg.content, isUser = msg.role == "user", timeLabel = msg.time)
                    }
                    addSystemMessage("여기서부터 새 대화")
                } else {
                    addSystemMessage("Claude 프록시 서버 연결됨\n[건강데이터] 버튼으로 건강 정보를 확인하세요 💜")
                }
            } else {
                addSystemMessage("프록시 서버에 연결할 수 없습니다.\n(192.168.45.207:8787)\n맥북과 같은 WiFi인지 확인하세요.")
            }
        }
    }

    // ==================== Session Panel ====================

    private fun openSessionPanel() {
        sessionsPanelOpen = true
        sessionsPanelContainer.visibility = View.VISIBLE

        val params = sessionsPanelContainer.layoutParams as LinearLayout.LayoutParams
        params.width = panelWidth
        params.weight = 0f

        sessionsPanelContainer.animate()
            .alpha(1f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .withStartAction {
                sessionsPanelContainer.layoutParams = params
            }
            .start()

        loadSessionsList()
    }

    private fun closeSessionPanel() {
        sessionsPanelOpen = false

        sessionsPanelContainer.animate()
            .alpha(0.7f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                sessionsPanelContainer.visibility = View.GONE
                val params = sessionsPanelContainer.layoutParams as LinearLayout.LayoutParams
                params.width = 0
                params.weight = 0f
                sessionsPanelContainer.layoutParams = params
            }
            .start()
    }

    // ==================== Category Chips ====================

    private fun buildCategoryChips() {
        healthCategoryContainer.removeAllViews()
        for (cat in categories) {
            val chip = TextView(this).apply {
                text = cat.emoji + " " + cat.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF1C1B1F.toInt())
                setBackgroundResource(R.drawable.category_chip_bg)
                setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(8) }
                setOnClickListener { onCategoryClick(cat) }
            }
            healthCategoryContainer.addView(chip)
        }
    }

    private fun onCategoryClick(cat: HealthCategory) {
        categoryMenuOpen = false
        healthCategoryScroll.visibility = View.GONE

        // 헬스 요약이 필요 없는 특수 카테고리 먼저 처리
        when (cat.key) {
            "food_log" -> { showFoodLogBubble(); return }
            "water" -> { showWaterBubble(); return }
            "ai_coach" -> { triggerAiCoach(); return }
            "weekly" -> { showWeeklyTrendBubble(); return }
        }

        val summary = currentSummary
        if (summary == null) {
            addSystemMessage("건강 데이터를 아직 불러오지 못했습니다. 잠시 후 다시 시도해주세요.")
            return
        }

        val isMissing = when (cat.key) {
            "steps" -> summary.steps == null
            "heartrate" -> summary.heartRateAvg == null
            "sleep" -> summary.sleepHours == null
            "calories" -> summary.caloriesBurned == null
            "distance" -> summary.distance == null
            "spo2" -> summary.oxygenSaturation == null
            "weight" -> summary.weight == null
            "exercise" -> summary.exercises.isEmpty()
            "body" -> summary.bodyFat == null && summary.leanBodyMass == null && summary.bmi == null && summary.basalMetabolicRate == null
            else -> false
        }

        if (isMissing && cat.key != "all") { showLinkDialog(cat); return }

        val text = when (cat.key) {
            "steps" -> buildStepsText(summary)
            "heartrate" -> buildHeartRateText(summary)
            "sleep" -> buildSleepText(summary)
            "calories" -> buildCaloriesText(summary)
            "distance" -> buildDistanceText(summary)
            "spo2" -> buildSpo2Text(summary)
            "weight" -> buildWeightText(summary)
            "exercise" -> buildExerciseText(summary)
            "body" -> buildBodyCompositionText(summary)
            "all" -> buildAllText(summary)
            "blood_pressure" -> { addHealthBubble(cat.emoji + " " + cat.label, buildBloodPressureText(summary)); return }
            "blood_glucose" -> { addHealthBubble(cat.emoji + " " + cat.label, buildBloodGlucoseText(summary)); return }
            "respiratory" -> { addHealthBubble(cat.emoji + " " + cat.label, buildRespiratoryText(summary)); return }
            "vo2max" -> { addHealthBubble(cat.emoji + " " + cat.label, buildVo2MaxText(summary)); return }
            "floors" -> { addHealthBubble(cat.emoji + " " + cat.label, buildFloorsText(summary)); return }
            "active_calories" -> { addHealthBubble(cat.emoji + " " + cat.label, buildActiveCaloriesText(summary)); return }
            "health_score" -> { addHealthBubble(cat.emoji + " " + cat.label, buildHealthScoreText(summary)); return }
            "sleep_score" -> { addHealthBubble(cat.emoji + " " + cat.label, buildSleepScoreText(summary)); return }
            "nutrition_analysis" -> { addHealthBubble(cat.emoji + " " + cat.label, buildNutritionAnalysisText(summary)); return }
            "stress" -> { addHealthBubble(cat.emoji + " " + cat.label, buildStressText(summary)); return }
            else -> "데이터 없음"
        }
        addHealthBubble(cat.emoji + " " + cat.label + " (" + summary.lastUpdated + ")", text)
    }

    private fun showLinkDialog(cat: HealthCategory) {
        AlertDialog.Builder(this)
            .setTitle(cat.emoji + " " + cat.label + " 데이터 없음")
            .setMessage(cat.label + " 데이터가 없습니다.\nHealth Connect에서 삼성 헬스 연동을 확인하시겠습니까?")
            .setPositiveButton("연동 설정 열기") { _, _ -> openHealthConnectSettings() }
            .setNeutralButton("삼성 헬스 열기") { _, _ -> openSamsungHealth() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openHealthConnectSettings() {
        try {
            startActivity(Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, "com.sec.android.app.shealth")
            })
        } catch (e: Exception) {
            try { startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")) }
            catch (e2: Exception) {
                try {
                    val i = packageManager.getLaunchIntentForPackage("com.google.android.healthconnect.controller")
                    startActivity(i ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
                } catch (e3: Exception) { Toast.makeText(this, "Health Connect 설정을 열 수 없습니다.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun openSamsungHealth() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")
            if (intent != null) startActivity(intent)
            else Toast.makeText(this, "삼성 헬스가 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "삼성 헬스를 열 수 없습니다.", Toast.LENGTH_SHORT).show() }
    }

    // ==================== Health Text Builders ====================

    private fun buildStepsText(s: HealthSummary): String {
        if (s.steps == null) return "걸음수 데이터가 없습니다."
        val sb = StringBuilder()
        sb.appendLine("총 걸음수: " + "%,d".format(s.steps) + "보")
        val stepsHours = s.hourlyData.filter { it.steps > 0 }
        if (stepsHours.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("시간대별:")
            val maxSteps = stepsHours.maxOf { it.steps }.coerceAtLeast(1)
            for (h in stepsHours) {
                val barLen = ((h.steps.toDouble() / maxSteps) * 12).toInt().coerceAtLeast(1)
                sb.appendLine("%02d".format(h.hour) + "시 " + "█".repeat(barLen) + " " + "%,d".format(h.steps))
            }
        }
        return sb.toString().trimEnd()
    }

    private fun buildHeartRateText(s: HealthSummary): String {
        if (s.heartRateAvg == null) return "심박수 데이터가 없습니다."
        val sb = StringBuilder()
        sb.appendLine("평균: " + s.heartRateAvg + "bpm")
        sb.appendLine("최저: " + (s.heartRateMin ?: "-") + "  최고: " + (s.heartRateMax ?: "-"))
        val hrHours = s.hourlyData.filter { it.heartRateAvg != null }
        if (hrHours.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("시간대별:")
            val minHr = hrHours.mapNotNull { it.heartRateMin }.minOrNull() ?: 40
            val maxHr = hrHours.mapNotNull { it.heartRateMax }.maxOrNull() ?: 120
            val range = (maxHr - minHr).coerceAtLeast(1)
            for (h in hrHours) {
                val avg = h.heartRateAvg ?: continue
                val barLen = (((avg - minHr).toDouble() / range) * 10).toInt().coerceIn(1, 12)
                val rangeStr = if (h.heartRateMin != null && h.heartRateMax != null) " (${h.heartRateMin}-${h.heartRateMax})" else ""
                sb.appendLine("%02d".format(h.hour) + "시 " + "♥".repeat(barLen) + " " + avg + rangeStr)
            }
        }
        return sb.toString().trimEnd()
    }

    private fun buildSleepText(s: HealthSummary): String {
        if (s.sleepHours == null) return "수면 데이터가 없습니다."
        val sb = StringBuilder()
        sb.appendLine("총 수면: " + "%.1f".format(s.sleepHours) + "시간")
        if (s.sleepBlocks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("수면 타임라인:")
            for (block in s.sleepBlocks) {
                val startStr = "%02d:%02d".format(block.startHour, block.startMin)
                val endStr = "%02d:%02d".format(block.endHour, block.endMin)
                val icon = when {
                    block.stage.contains("깊은") -> "⬤⬤⬤"
                    block.stage.contains("얕은") -> "○○○"
                    block.stage.contains("REM") -> "✦✦✦"
                    block.stage.contains("깸") -> "│││"
                    else -> "■■■"
                }
                sb.appendLine("$startStr-$endStr $icon ${block.stage}")
            }
            sb.appendLine()
            sb.appendLine("⬤=깊은  ○=얕은  ✦=REM  │=깸")
        }
        return sb.toString().trimEnd()
    }

    private fun buildCaloriesText(s: HealthSummary): String {
        if (s.caloriesBurned == null) return "칼로리 데이터가 없습니다."
        return "소모 칼로리: " + "%,.0f".format(s.caloriesBurned) + "kcal"
    }

    private fun buildDistanceText(s: HealthSummary): String {
        if (s.distance == null) return "이동거리 데이터가 없습니다."
        return "이동거리: " + "%.0f".format(s.distance) + "m (" + "%.1f".format(s.distance / 1000) + "km)"
    }

    private fun buildSpo2Text(s: HealthSummary): String {
        if (s.oxygenSaturation == null) return "산소포화도 데이터가 없습니다."
        return "산소포화도: " + "%.1f".format(s.oxygenSaturation) + "%"
    }

    private fun buildWeightText(s: HealthSummary): String {
        if (s.weight == null) return "체중 데이터가 없습니다."
        return "체중: " + "%.1f".format(s.weight) + "kg"
    }

    private fun buildBodyCompositionText(s: HealthSummary): String {
        val sb = StringBuilder()
        sb.appendLine("[ 체성분 분석 ] (최근 30일 기준)")
        sb.appendLine()
        var hasData = false

        s.weight?.let { v -> sb.appendLine("⚖  체중:       ${"%.1f".format(v)} kg"); hasData = true }
        s.height?.let { v -> sb.appendLine("📏 신장:       ${"%.0f".format(v * 100)} cm"); hasData = true }
        s.bmi?.let { v ->
            val status = when { v < 18.5 -> "저체중"; v < 23.0 -> "정상"; v < 25.0 -> "과체중"; v < 30.0 -> "비만"; else -> "고도비만" }
            sb.appendLine("📊 BMI:        ${"%.1f".format(v)}  ($status)")
            hasData = true
        }
        s.bodyFat?.let { v ->
            val bar = "█".repeat((v / 5).toInt().coerceIn(1, 10))
            sb.appendLine("🔴 체지방률:   ${"%.1f".format(v)}%  $bar")
            hasData = true
        }
        s.leanBodyMass?.let { v -> sb.appendLine("💪 골격근량:   ${"%.1f".format(v)} kg"); hasData = true }
        s.basalMetabolicRate?.let { v -> sb.appendLine("🔥 기초대사량: $v kcal/일"); hasData = true }

        if (!hasData) return "체성분 데이터가 없습니다.\n삼성 헬스에서 체성분 측정 후 Health Connect와 동기화해주세요."

        s.bmi?.let { bmi ->
            sb.appendLine()
            sb.appendLine("BMI 범위:")
            sb.appendLine("저체중|---정상---|과체중|비만")
            val pos = ((bmi - 15) / 25 * 20).toInt().coerceIn(0, 19)
            sb.appendLine(" ".repeat(pos) + "▲")
            sb.appendLine("15   18.5  23  25  30  40")
        }
        return sb.toString().trimEnd()
    }

    private fun buildExerciseText(s: HealthSummary): String {
        if (s.exercises.isEmpty()) return "최근 7일 운동 기록이 없습니다."
        val sb = StringBuilder()
        sb.appendLine("최근 7일 운동:")
        for (ex in s.exercises) sb.appendLine("  • $ex")
        return sb.toString().trimEnd()
    }

    private fun buildAllText(s: HealthSummary): String {
        val sb = StringBuilder()
        sb.appendLine("[ ${s.lastUpdated} 기준 ]")
        s.steps?.let { sb.appendLine("👣 걸음수: ${"%,d".format(it)}보") }
        s.heartRateAvg?.let { avg ->
            sb.append("♥ 심박수: 평균 ${avg}bpm")
            s.heartRateMin?.let { mn -> sb.append(" (최저 $mn") }
            s.heartRateMax?.let { mx -> sb.append(" / 최고 $mx)") }
            sb.appendLine()
        }
        s.sleepHours?.let { sb.appendLine("💤 수면: ${"%.1f".format(it)}시간") }
        s.caloriesBurned?.let { sb.appendLine("🔥 칼로리: ${"%,.0f".format(it)}kcal") }
        s.distance?.let { sb.appendLine("📏 이동: ${"%.1f".format(it / 1000)}km") }
        s.oxygenSaturation?.let { sb.appendLine("🩸 산소포화도: ${"%.1f".format(it)}%") }
        s.weight?.let { sb.appendLine("⚖ 체중: ${"%.1f".format(it)}kg") }
        s.bodyFat?.let { sb.appendLine("🧬 체지방: ${"%.1f".format(it)}%") }
        s.leanBodyMass?.let { sb.appendLine("💪 골격근: ${"%.1f".format(it)}kg") }
        s.bmi?.let { sb.appendLine("📊 BMI: ${"%.1f".format(it)}") }
        s.basalMetabolicRate?.let { sb.appendLine("⚡ 기초대사: ${it}kcal") }
        if (s.exercises.isNotEmpty()) sb.appendLine("🏋 운동: ${s.exercises.joinToString(", ")}")
        return if (sb.length <= 20) "데이터 없음" else sb.toString().trimEnd()
    }

    // ==================== 실시간 심박수 모니터 ====================

    private fun setupHeartRateMonitor() {
        btnToggleHrMonitor.setOnClickListener {
            if (heartRateMonitor.isMonitoring()) {
                heartRateMonitor.stopMonitoring()
                btnToggleHrMonitor.text = "시작"
                tvHrPollLabel.text = "15초 갱신"
                stopHeartBeatAnimation()
            } else {
                startHeartRateMonitoring()
            }
        }

        // StateFlow 관찰 → UI 갱신
        lifecycleScope.launch {
            heartRateMonitor.state.collect { state ->
                updateHeartRateUI(state)
            }
        }
    }

    private fun startHeartRateMonitoring() {
        btnToggleHrMonitor.text = "중지"
        tvHrPollLabel.text = "15초 갱신 중"
        heartRateMonitor.startMonitoring(lifecycleScope)
        startHeartBeatAnimation()
    }

    private fun updateHeartRateUI(state: HeartRateState) {
        runOnUiThread {
            if (state.bpm != null) {
                tvLiveHeartRate.text = state.bpm.toString()
                tvLiveHeartRate.setTextColor(state.statusColor)
            } else {
                tvLiveHeartRate.text = "--"
                tvLiveHeartRate.setTextColor(0xFFAAAAAA.toInt())
            }
            tvHeartRateStatus.text = "${state.statusEmoji} ${state.status}"
            tvHeartRateStatus.setTextColor(state.statusColor)
            if (state.lastTime.isNotEmpty()) {
                tvHeartRateTime.text = "마지막: ${state.lastTime}" +
                        if (state.recentAvg != null && state.recentAvg != state.bpm)
                            "  (3분 평균: ${state.recentAvg}bpm)"
                        else ""
            }
            // 카드 배경 상태별 변경
            cardLiveHeartRate.setBackgroundColor(state.cardBg)
        }
    }

    private fun startHeartBeatAnimation() {
        heartBeatAnimator?.cancel()
        heartBeatAnimator = ObjectAnimator.ofFloat(tvHeartBeatIcon, "scaleX", 1f, 1.35f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(tvHeartBeatIcon, "scaleY", 1f, 1.35f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopHeartBeatAnimation() {
        heartBeatAnimator?.cancel()
        heartBeatAnimator = null
        tvHeartBeatIcon.scaleX = 1f
        tvHeartBeatIcon.scaleY = 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        heartRateMonitor.stopMonitoring()
        stopHeartBeatAnimation()
    }

    // ==================== Health Data ====================

    private fun loadHealthData() {
        lifecycleScope.launch {
            try {
                val summary = healthReader.readTodayData()
                currentSummary = summary
                currentHealthContext = summary.toContextString()
                Log.d("HealthChat", "Health context loaded:\n$currentHealthContext")
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    buildHomeDashboard(summary)
                }
            } catch (e: Exception) {
                Log.e("HealthChat", "Failed to read health data", e)
                addSystemMessage("건강 데이터 읽기 실패: ${e.message}")
                currentHealthContext = null
            }
        }
    }

    // ==================== Messaging ====================

    private fun sendMessage() {
        val msg = etMessage.text.toString().trim()

        // 이미지가 선택되어 있으면 이미지 + 텍스트 전송, 아니면 텍스트만 전송
        if (selectedImageUri != null) {
            if (msg.isEmpty()) {
                Toast.makeText(this, "설명을 입력해주세요", Toast.LENGTH_SHORT).show()
                return
            }
            sendMessageWithImage(msg)
        } else {
            if (msg.isEmpty()) return
            sendTextMessage(msg)
        }
    }

    private fun sendTextMessage(msg: String) {
        etMessage.text.clear()
        val nowTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        addChatBubble(msg, isUser = true, timeLabel = nowTime)
        startTypingIndicator()

        userMessageCount++

        lifecycleScope.launch {
            val response = chatClient.sendMessage(msg, currentHealthContext)
            stopTypingIndicator()
            val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            addChatBubble(response, isUser = false, timeLabel = resTime)

            if (userMessageCount % 3 == 0 && currentSessionId != null) {
                generateSessionTitle()
            }
        }
    }

    private fun selectImage(uri: Uri, mimeType: String) {
        selectedImageUri = uri
        selectedImageMimeType = mimeType
        addImageBubblePreview(uri)
        etMessage.hint = "이 사진에 대해 설명해주세요... (예: 점심 먹은 돈까스)"
        etMessage.requestFocus()
    }

    // ==================== Image / Food Analysis ====================

    private fun showImagePickerDialog() {
        AlertDialog.Builder(this)
            .setTitle("🍽️ 음식 사진 분석")
            .setMessage("사진을 선택하면 AI가 칼로리와 영양성분을 분석해드립니다.")
            .setPositiveButton("📷 카메라로 찍기") { _, _ ->
                val perm = android.Manifest.permission.CAMERA
                if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    requestCameraPermission.launch(perm)
                }
            }
            .setNeutralButton("🖼️ 갤러리에서 선택") { _, _ ->
                pickImageLauncher.launch("image/*")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun launchCamera() {
        val cacheDir = File(cacheDir, "camera").also { it.mkdirs() }
        val tempFile = File(cacheDir, "food_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
        cameraImageUri = uri
        takePictureLauncher.launch(uri)
    }

    // ==================== 삼성헬스 자동 저장 ====================
    // 저장 결과 + 로그 반환
    data class SaveResult(val success: Boolean, val log: String)

    private fun addDebugLog(text: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        debugLogs.append("[$timestamp] $text\n")
    }

    private suspend fun autoSaveToSamsungHealth(foodName: String, calories: Int, protein: Int, carbs: Int, fat: Int, mealType: String? = null): SaveResult {
        val logs = StringBuilder()
        logs.appendLine("[헬스 저장 디버그 로그]")
        logs.appendLine("음식: $foodName / ${calories}kcal")
        logs.appendLine("단백질 ${protein}g / 탄수화물 ${carbs}g / 지방 ${fat}g")

        addDebugLog("=== 식사 저장 시작 ===")
        addDebugLog("음식: $foodName, 칼로리: ${calories}kcal")

        return try {
            logs.appendLine("▶ Health Connect 저장 시도...")
            addDebugLog("Health Connect 저장 시도 중...")

            // 상세 로그를 포함한 새로운 함수 사용
            val result = healthReader.saveNutritionWithLog(
                foodName = foodName,
                calories = calories.toDouble(),
                carbs = carbs.toDouble(),
                protein = protein.toDouble(),
                fat = fat.toDouble(),
                mealTypeStr = mealType
            )

            // HealthDataReader의 상세 로그 추가
            logs.appendLine(result.log)

            if (result.success) {
                logs.appendLine("✅ Health Connect 저장 성공")
                addDebugLog("✅ Health Connect 저장 성공!")
                Log.d("MainActivity", "✅ Health Connect 저장 성공: $foodName")
                // Samsung Health 동기화 트리거 (Health Connect 데이터 반영)
                try {
                    val syncIntent = Intent("android.intent.action.VIEW").apply {
                        setPackage("com.sec.android.app.shealth")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    applicationContext.sendBroadcast(
                        Intent("com.samsung.android.app.shealth.tracker.food.ui.util.UPDATE_MEAL_SUMMARY")
                            .setPackage("com.sec.android.app.shealth")
                    )
                    addDebugLog("📡 Samsung Health 동기화 브로드캐스트 전송")
                } catch (ex: Exception) {
                    addDebugLog("⚠️ 동기화 브로드캐스트 실패: ${ex.message}")
                }
            } else {
                logs.appendLine("❌ Health Connect 저장 실패")
                addDebugLog("❌ Health Connect 저장 실패")
                Log.w("MainActivity", "⚠️ Health Connect 저장 실패")
            }
            SaveResult(result.success, logs.toString().trimEnd())
        } catch (e: Exception) {
            logs.appendLine("❌ 예외: ${e.javaClass.simpleName}")
            logs.appendLine("   ${e.message}")
            e.cause?.let { logs.appendLine("   원인: ${it.message}") }
            addDebugLog("❌ 예외 발생: ${e.javaClass.simpleName} - ${e.message}")
            Log.e("MainActivity", "❌ 자동 저장 오류", e)
            SaveResult(false, logs.toString().trimEnd())
        }
    }

    private suspend fun sendToSamsungHealth(foodName: String, calories: Int, protein: Int, carbs: Int, fat: Int, mealType: String? = null): SaveResult {
        return autoSaveToSamsungHealth(foodName, calories, protein, carbs, fat, mealType)
    }

    // 식사 타입 선택 버튼 말풍선
    private fun showMealTypeSelectorBubble(
        foodName: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        timeLabel: String
    ) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            gravity = Gravity.START
        }

        val questionBubble = TextView(this).apply {
            text = "🍽️ ${foodName} ${calories}kcal\n식사 시간대를 선택해주세요"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF1C1B1F.toInt())
            setBackgroundResource(R.drawable.chat_bubble_ai)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        wrapper.addView(questionBubble)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
        }

        val mealOptions = listOf(
            Triple("🌅", "아침", "breakfast"),
            Triple("☀️", "점심", "lunch"),
            Triple("🌙", "저녁", "dinner"),
            Triple("🍪", "간식", "snack")
        )

        mealOptions.forEach { (emoji, label, key) ->
            val btn = TextView(this).apply {
                text = "$emoji $label"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF6750A4.toInt())
                setBackgroundResource(R.drawable.category_chip_bg)
                setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(8) }
                setOnClickListener {
                    // 버튼 클릭 시 wrapper 제거 후 저장
                    chatContainer.removeView(wrapper)
                    val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    addChatBubble("$emoji $label 으로 저장 중...", isUser = false, timeLabel = resTime)
                    lifecycleScope.launch {
                        val saveResult = sendToSamsungHealth(foodName, calories, protein, carbs, fat, key)
                        addDebugLog("=== 저장 상세 로그 ($label) ===\n${saveResult.log}")
                        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        if (saveResult.success) {
                            addChatBubble("✅ $emoji $label 저장 완료\n$foodName ${calories}kcal", isUser = false, timeLabel = t)
                        } else {
                            addChatBubble("⚠️ 저장 실패\n🔴 디버그 버튼을 눌러 로그 확인", isUser = false, timeLabel = t)
                        }
                    }
                }
            }
            btnRow.addView(btn)
        }

        val hScroll = android.widget.HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
            isHorizontalScrollBarEnabled = false
            addView(btnRow)
        }
        wrapper.addView(hScroll)

        val label = TextView(this).apply {
            text = "Claude  $timeLabel"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF79747E.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(3) }
        }
        wrapper.addView(label)

        chatContainer.addView(wrapper)
        scrollToBottom()
    }

    private fun sendMessageWithImage(userMessage: String) {
        if (selectedImageUri == null) return

        etMessage.text.clear()
        etMessage.hint = "메시지를 입력해주세요..."
        val nowTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

        // 이미지 + 텍스트를 사용자 메시지로 표시
        addImageBubble(selectedImageUri!!, nowTime, userMessage)
        startTypingIndicator()

        lifecycleScope.launch {
            try {
                val base64 = compressBitmapToBase64(selectedImageUri!!)
                if (base64 == null) {
                    stopTypingIndicator()
                    addChatBubble("이미지를 읽을 수 없습니다.", isUser = false, timeLabel = nowTime)
                    selectedImageUri = null
                    return@launch
                }

                // 사용자 입력 메시지 + 기본 분석 프롬프트
                val analysisPrompt = userMessage + "\n\n칼로리와 영양성분도 분석해줘"

                val result = chatClient.sendImageMessage(base64, selectedImageMimeType!!, analysisPrompt, currentHealthContext)
                stopTypingIndicator()
                val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                addChatBubble(result.displayText, isUser = false, timeLabel = resTime)

                // 파싱 결과 → 디버그 창에만 기록
                addDebugLog("=== 영양 파싱 결과 ===")
                addDebugLog("foodName=${result.foodName}")
                addDebugLog("calories=${result.calories}")
                addDebugLog("protein=${result.protein}, carbs=${result.carbs}, fat=${result.fat}")

                // 영양 데이터가 있으면 식사 타입 선택 버튼 표시
                if (result.calories != null && result.calories > 0) {
                    val foodName = result.foodName ?: "음식"
                    showMealTypeSelectorBubble(
                        foodName = foodName,
                        calories = result.calories.toInt(),
                        protein = result.protein?.toInt() ?: 0,
                        carbs = result.carbs?.toInt() ?: 0,
                        fat = result.fat?.toInt() ?: 0,
                        timeLabel = resTime
                    )
                } else {
                    addDebugLog("영양 데이터 파싱 실패 - calories=${result.calories}")
                    addChatBubble("⚠️ 영양 데이터 파싱 실패\n🔴 디버그 버튼을 눌러 로그를 확인해주세요", isUser = false, timeLabel = resTime)
                }

                selectedImageUri = null
            } catch (e: Exception) {
                stopTypingIndicator()
                addChatBubble("이미지 분석 중 오류 발생: ${e.message}", isUser = false, timeLabel = nowTime)
                selectedImageUri = null
            }
        }
    }

    private fun processAndSendImage(uri: Uri, mimeType: String) {
        val nowTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

        // 썸네일 말풍선 추가
        addImageBubble(uri, nowTime)
        startTypingIndicator()

        lifecycleScope.launch {
            try {
                val base64 = compressBitmapToBase64(uri)
                if (base64 == null) {
                    stopTypingIndicator()
                    addChatBubble("이미지를 읽을 수 없습니다.", isUser = false, timeLabel = nowTime)
                    return@launch
                }

                val result = chatClient.sendImageMessage(base64, mimeType, "이 음식의 칼로리와 영양성분을 분석해줘", currentHealthContext)
                stopTypingIndicator()
                val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                addChatBubble(result.displayText, isUser = false, timeLabel = resTime)

                // 파싱 결과 → 디버그 창에만 기록
                addDebugLog("=== 영양 파싱 결과 ===")
                addDebugLog("foodName=${result.foodName}")
                addDebugLog("calories=${result.calories}")
                addDebugLog("protein=${result.protein}, carbs=${result.carbs}, fat=${result.fat}")

                // 영양 데이터가 있으면 식사 타입 선택 버튼 표시
                if (result.calories != null && result.calories > 0) {
                    val foodName = result.foodName ?: "음식"
                    showMealTypeSelectorBubble(
                        foodName = foodName,
                        calories = result.calories.toInt(),
                        protein = result.protein?.toInt() ?: 0,
                        carbs = result.carbs?.toInt() ?: 0,
                        fat = result.fat?.toInt() ?: 0,
                        timeLabel = resTime
                    )
                } else {
                    addDebugLog("영양 데이터 파싱 실패 - calories=${result.calories}")
                    addChatBubble("⚠️ 영양 데이터 파싱 실패\n🔴 디버그 버튼을 눌러 로그를 확인해주세요", isUser = false, timeLabel = resTime)
                }
            } catch (e: Exception) {
                stopTypingIndicator()
                addChatBubble("이미지 분석 중 오류 발생: ${e.message}", isUser = false, timeLabel = nowTime)
            }
        }
    }

    private fun addImageBubble(uri: Uri, timeLabel: String) {
        val bitmap = try {
            val inputStream = contentResolver.openInputStream(uri)
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeStream(inputStream, null, opts)
        } catch (e: Exception) { null }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            gravity = Gravity.END
        }

        if (bitmap != null) {
            val imageView = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dpToPx(180), dpToPx(180)).apply {
                    setBackgroundResource(R.drawable.chat_bubble_user)
                }
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                clipToOutline = true
            }
            wrapper.addView(imageView)
        }

        val label = TextView(this).apply {
            text = "나  $timeLabel"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF79747E.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(3) }
        }
        wrapper.addView(label)

        chatContainer.addView(wrapper)
        scrollToBottom()
    }

    // 사용자 입력 메시지가 있는 이미지 버블
    private fun addImageBubble(uri: Uri, timeLabel: String, userMessage: String) {
        // 먼저 텍스트 메시지 추가
        addChatBubble(userMessage, isUser = true, timeLabel = timeLabel)

        // 그 다음 이미지만 추가
        val bitmap = try {
            val inputStream = contentResolver.openInputStream(uri)
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeStream(inputStream, null, opts)
        } catch (e: Exception) { null }

        if (bitmap != null) {
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(8) }
                gravity = Gravity.END
            }

            val imageView = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dpToPx(180), dpToPx(180)).apply {
                    setBackgroundResource(R.drawable.chat_bubble_user)
                }
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                clipToOutline = true
            }
            wrapper.addView(imageView)
            chatContainer.addView(wrapper)
        }

        scrollToBottom()
    }

    private fun addImageBubblePreview(uri: Uri) {
        // 이미지 선택 시 미리보기 (채팅창에는 아직 표시하지 않음)
        Toast.makeText(this, "📸 사진이 선택되었습니다. 설명을 입력하고 전송해주세요.", Toast.LENGTH_SHORT).show()
    }

    private fun compressBitmapToBase64(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(inputStream) ?: return null

            // 최대 1024px로 리사이즈
            val maxDim = 1024
            val scale = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt(),
                    (original.height * scale).toInt(),
                    true
                )
            } else original

            val baos = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("ImageCompress", "Failed", e)
            null
        }
    }

    private fun generateSessionTitle() {
        lifecycleScope.launch {
            try {
                val title = chatClient.sendMessage("지금까지의 대화를 한 문장으로 요약해줘. 10자 이내로.", null)
                if (title.isNotEmpty() && currentSessionId != null) {
                    val trimTitle = title.trim().take(20)
                    chatClient.updateSessionTitle(currentSessionId!!, trimTitle)
                    runOnUiThread { tvSessionSubtitle.text = trimTitle }
                }
            } catch (e: Exception) {
                Log.e("SessionTitle", "Failed", e)
            }
        }
    }

    // ==================== UI Components ====================

    private fun addHealthBubble(title: String, content: String) {
        val container = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(10) }
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.health_bubble_bg)
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }

        val titleView = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF6750A4.toInt())
            setTypeface(null, Typeface.BOLD)
        }

        val contentView = TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF1C1B1F.toInt())
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
            setOnLongClickListener {
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("health", text))
                Toast.makeText(this@MainActivity, "복사됨", Toast.LENGTH_SHORT).show()
                true
            }
        }

        container.addView(titleView)
        container.addView(contentView)
        chatContainer.addView(container)
        scrollToBottom()
    }

    private fun addChatBubble(text: String, isUser: Boolean, timeLabel: String = "") {
        val screenW = resources.displayMetrics.widthPixels
        val maxBubbleW = (screenW * 0.78).toInt()

        val bubble = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (isUser) 0xFF21005D.toInt() else 0xFF1C1B1F.toInt())
            setBackgroundResource(if (isUser) R.drawable.chat_bubble_user else R.drawable.chat_bubble_ai)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { maxWidth = maxBubbleW }
            setOnLongClickListener {
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("chat", text))
                Toast.makeText(this@MainActivity, "복사됨", Toast.LENGTH_SHORT).show()
                true
            }
        }

        val label = TextView(this).apply {
            this.text = (if (isUser) "나" else "Claude") + if (timeLabel.isNotEmpty()) "  $timeLabel" else ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF79747E.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(3)
                if (isUser) marginEnd = dpToPx(2) else marginStart = dpToPx(2)
            }
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            gravity = if (isUser) Gravity.END else Gravity.START
            addView(bubble)
            addView(label)
        }

        chatContainer.addView(wrapper)
        scrollToBottom()
    }

    private fun addSystemMessage(text: String) {
        val wrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(10); topMargin = dpToPx(4) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val line1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(1), 1f).apply { marginEnd = dpToPx(8) }
            setBackgroundColor(0xFFE7E0EC.toInt())
        }
        val tv = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF79747E.toInt())
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        }
        val line2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(1), 1f).apply { marginStart = dpToPx(8) }
            setBackgroundColor(0xFFE7E0EC.toInt())
        }
        wrapper.addView(line1)
        wrapper.addView(tv)
        wrapper.addView(line2)
        chatContainer.addView(wrapper)
        scrollToBottom()
    }

    // ==================== Typing Indicator ====================

    private fun startTypingIndicator() {
        val tv = TextView(this).apply {
            text = "Claude ●"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF79747E.toInt())
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            setBackgroundResource(R.drawable.chat_bubble_ai)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }
        typingView = tv
        typingDotCount = 1
        chatContainer.addView(tv)
        scrollToBottom()

        typingRunnable = object : Runnable {
            override fun run() {
                typingDotCount = (typingDotCount % 3) + 1
                tv.text = "Claude " + "●".repeat(typingDotCount)
                typingHandler.postDelayed(this, 500)
            }
        }
        typingHandler.postDelayed(typingRunnable!!, 500)
    }

    private fun stopTypingIndicator() {
        typingRunnable?.let { typingHandler.removeCallbacks(it); typingRunnable = null }
        typingView?.let { chatContainer.removeView(it); typingView = null }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    // ==================== Debug Log ====================

    private fun showDebugLog() {
        val logText = if (debugLogs.isEmpty()) "로그 없음" else debugLogs.toString()

        val scrollTextView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            addView(TextView(this@MainActivity).apply {
                text = logText
                setTextColor(0xFF1C1B1F.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setTextIsSelectable(true)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("📋 디버그 로그")
            .setView(scrollTextView)
            .setPositiveButton("복사") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("debug_log", logText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "로그가 클립보드에 복사되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("초기화") { _, _ ->
                debugLogs.clear()
                Toast.makeText(this, "로그가 초기화되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("닫기") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ==================== Sessions ====================

    private fun loadSessionsList() {
        lifecycleScope.launch {
            val (sessions, current) = chatClient.getSessions()
            val adapter = SessionListAdapter(sessions, current ?: currentSessionId) { sessionId ->
                loadSession(sessionId)
            }
            sessionsRecyclerView.layoutManager = LinearLayoutManager(this@MainActivity)
            if (sessionsRecyclerView.itemDecorationCount == 0) {
                sessionsRecyclerView.addItemDecoration(
                    DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
                )
            }
            sessionsRecyclerView.adapter = adapter
        }
    }

    private fun loadSession(sessionId: String) {
        lifecycleScope.launch {
            val success = chatClient.switchSession(sessionId)
            if (success) {
                currentSessionId = sessionId
                userMessageCount = 0
                chatContainer.removeAllViews()
                val messages = chatClient.getSessionMessages(sessionId)
                if (messages.isNotEmpty()) {
                    for (msg in messages) {
                        addChatBubble(msg.content, isUser = msg.role == "user", timeLabel = msg.time)
                        if (msg.role == "user") userMessageCount++
                    }
                } else {
                    addSystemMessage("대화 내역 없음")
                }
                closeSessionPanel()
            }
        }
    }

    private fun newChatSession() {
        lifecycleScope.launch {
            val sessionId = chatClient.createNewSession()
            if (sessionId.isNotEmpty()) {
                currentSessionId = sessionId
                userMessageCount = 0
                chatContainer.removeAllViews()
                tvSessionSubtitle.text = "새 대화"
                addSystemMessage("새 채팅 시작됨 ✨")
                closeSessionPanel()
            }
        }
    }

    // ==================== 식사 기록 / 삭제 / 재추가 ====================

    private fun showFoodLogBubble() {
        lifecycleScope.launch {
            val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val entries = try { healthReader.readTodayNutrition() } catch (e: Exception) { emptyList() }

            if (entries.isEmpty()) {
                addHealthBubble("🍽️ 오늘 식사 기록", "아직 기록된 식사가 없습니다.\n📷 카메라 버튼으로 음식 사진을 찍거나\n💬 음식 이름을 말씀해주세요!")
                return@launch
            }

            val totalCals = entries.sumOf { it.calories }
            val totalCarbs = entries.sumOf { it.carbs ?: 0.0 }
            val totalProtein = entries.sumOf { it.protein ?: 0.0 }
            val totalFat = entries.sumOf { it.fat ?: 0.0 }

            val sb = StringBuilder()
            sb.appendLine("총 섭취: ${"%.0f".format(totalCals)}kcal")
            sb.appendLine("탄 ${"%.0f".format(totalCarbs)}g  단 ${"%.0f".format(totalProtein)}g  지 ${"%.0f".format(totalFat)}g")
            sb.appendLine()

            val grouped = entries.groupBy { it.mealType }
            val mealOrder = listOf(
                androidx.health.connect.client.records.MealType.MEAL_TYPE_BREAKFAST,
                androidx.health.connect.client.records.MealType.MEAL_TYPE_LUNCH,
                androidx.health.connect.client.records.MealType.MEAL_TYPE_DINNER,
                androidx.health.connect.client.records.MealType.MEAL_TYPE_SNACK,
                androidx.health.connect.client.records.MealType.MEAL_TYPE_UNKNOWN
            )
            for (mt in mealOrder) {
                val items = grouped[mt] ?: continue
                sb.appendLine(healthReader.mealTypeToKorean(mt))
                for (item in items) {
                    sb.append("  • ${item.foodName ?: "음식"} ${"%.0f".format(item.calories)}kcal")
                    val ns = listOfNotNull(
                        item.carbs?.let { "탄${it.toInt()}g" },
                        item.protein?.let { "단${it.toInt()}g" },
                        item.fat?.let { "지${it.toInt()}g" }
                    ).joinToString(" ")
                    if (ns.isNotEmpty()) sb.append("  $ns")
                    sb.appendLine()
                }
            }

            addHealthBubble("🍽️ 오늘 식사 기록 ($resTime)", sb.toString().trimEnd())
            showFoodDeleteBubble(entries, resTime)
        }
    }

    private fun showFoodDeleteBubble(entries: List<NutritionEntry>, timeLabel: String) {
        if (entries.isEmpty()) return

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            gravity = Gravity.START
        }

        val titleBubble = TextView(this).apply {
            text = "🗑️ 삭제할 식사를 선택하세요:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF1C1B1F.toInt())
            setBackgroundResource(R.drawable.chat_bubble_ai)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        wrapper.addView(titleBubble)

        for (entry in entries) {
            val mealLabel = healthReader.mealTypeToKorean(entry.mealType)
            val btn = TextView(this).apply {
                text = "🗑️ $mealLabel  ${entry.foodName ?: "음식"}  ${"%.0f".format(entry.calories)}kcal"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFB3261E.toInt())
                setBackgroundResource(R.drawable.category_chip_bg)
                setPadding(dpToPx(14), dpToPx(9), dpToPx(14), dpToPx(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(6) }
                setOnClickListener {
                    chatContainer.removeView(wrapper)
                    showDeleteConfirmDialog(entry)
                }
            }
            wrapper.addView(btn)
        }

        val cancelBtn = TextView(this).apply {
            text = "✕ 닫기"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF79747E.toInt())
            setBackgroundResource(R.drawable.category_chip_bg)
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
            setOnClickListener { chatContainer.removeView(wrapper) }
        }
        wrapper.addView(cancelBtn)

        val lbl = TextView(this).apply {
            text = "Claude  $timeLabel"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF79747E.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(3) }
        }
        wrapper.addView(lbl)
        chatContainer.addView(wrapper)
        scrollToBottom()
    }

    private fun showDeleteConfirmDialog(entry: NutritionEntry) {
        val mealLabel = healthReader.mealTypeToKorean(entry.mealType)
        AlertDialog.Builder(this)
            .setTitle("🗑️ 식사 삭제")
            .setMessage("$mealLabel  ${entry.foodName ?: "음식"}  (${entry.calories.toInt()}kcal)\n\n삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    val success = healthReader.deleteNutritionRecord(entry.recordId)
                    val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    if (success) {
                        addChatBubble("✅ 삭제 완료\n$mealLabel  ${entry.foodName ?: "음식"}  ${entry.calories.toInt()}kcal", isUser = false, timeLabel = t)
                        showReAddBubble(entry, t)
                    } else {
                        addChatBubble("❌ 삭제 실패\nHealth Connect에서 직접 삭제해주세요.", isUser = false, timeLabel = t)
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showReAddBubble(deleted: NutritionEntry, timeLabel: String) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            gravity = Gravity.START
        }

        val q = TextView(this).apply {
            text = "🔄 ${deleted.foodName ?: "음식"} 다시 추가할 시간대를 선택하세요:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF1C1B1F.toInt())
            setBackgroundResource(R.drawable.chat_bubble_ai)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        wrapper.addView(q)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(6) }
        }

        listOf(Triple("🌅", "아침", "breakfast"), Triple("☀️", "점심", "lunch"), Triple("🌙", "저녁", "dinner"), Triple("🍪", "간식", "snack")).forEach { (emoji, label, key) ->
            val btn = TextView(this).apply {
                text = "$emoji $label"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF6750A4.toInt())
                setBackgroundResource(R.drawable.category_chip_bg)
                setPadding(dpToPx(14), dpToPx(9), dpToPx(14), dpToPx(9))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(6) }
                setOnClickListener {
                    chatContainer.removeView(wrapper)
                    lifecycleScope.launch {
                        val r = sendToSamsungHealth(deleted.foodName ?: "음식", deleted.calories.toInt(), deleted.protein?.toInt() ?: 0, deleted.carbs?.toInt() ?: 0, deleted.fat?.toInt() ?: 0, key)
                        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        if (r.success) addChatBubble("✅ $emoji $label 으로 재추가 완료\n${deleted.foodName ?: "음식"}  ${deleted.calories.toInt()}kcal", isUser = false, timeLabel = t)
                        else addChatBubble("❌ 재추가 실패\n🔴 디버그 버튼에서 로그를 확인하세요", isUser = false, timeLabel = t)
                    }
                }
            }
            btnRow.addView(btn)
        }

        val hScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(4) }
            isHorizontalScrollBarEnabled = false
            addView(btnRow)
        }
        wrapper.addView(hScroll)

        val skipBtn = TextView(this).apply {
            text = "✕ 추가 안 함"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF79747E.toInt())
            setBackgroundResource(R.drawable.category_chip_bg)
            setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(6) }
            setOnClickListener { chatContainer.removeView(wrapper) }
        }
        wrapper.addView(skipBtn)

        val lbl = TextView(this).apply {
            text = "Claude  $timeLabel"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF79747E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(3) }
        }
        wrapper.addView(lbl)
        chatContainer.addView(wrapper)
        scrollToBottom()
    }

    // ==================== 수분 섭취 ====================

    private fun showWaterBubble() {
        lifecycleScope.launch {
            val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val water = try { healthReader.readTodayWater() } catch (e: Exception) { WaterIntakeSummary(0.0, emptyList()) }

            val totalMl = water.totalMl
            val goalMl = 2000.0
            val pct = (totalMl / goalMl * 100).toInt().coerceIn(0, 100)
            val filled = (pct / 10).coerceIn(0, 10)
            val bar = "🔵".repeat(filled) + "⚪".repeat(10 - filled)

            val sb = StringBuilder()
            sb.appendLine("오늘 수분: ${"%.0f".format(totalMl)}ml / ${goalMl.toInt()}ml")
            sb.appendLine("$bar  $pct%")
            if (totalMl >= goalMl) sb.appendLine("✅ 오늘 목표 달성!")
            else sb.appendLine("목표까지 ${"%.0f".format(goalMl - totalMl)}ml 남음")
            sb.appendLine()
            if (water.entries.isEmpty()) {
                sb.appendLine("아직 수분 섭취가 기록되지 않았습니다.")
            } else {
                sb.appendLine("기록:")
                val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                for (e in water.entries.sortedBy { it.time }) {
                    sb.appendLine("  ${sdf.format(java.util.Date(e.time.toEpochMilli()))}  ${"%.0f".format(e.ml)}ml")
                }
            }
            addHealthBubble("💧 수분 섭취 ($resTime)", sb.toString().trimEnd())
            showWaterQuickAddBubble(resTime)
        }
    }

    private fun showWaterQuickAddBubble(timeLabel: String) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpToPx(8) }
            gravity = Gravity.START
        }

        val q = TextView(this).apply {
            text = "💧 얼마나 마셨나요?"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF1C1B1F.toInt())
            setBackgroundResource(R.drawable.chat_bubble_ai)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        wrapper.addView(q)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(6) }
        }

        listOf(150, 200, 250, 350, 500).forEach { ml ->
            val btn = TextView(this).apply {
                text = "${ml}ml"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF0066CC.toInt())
                setBackgroundResource(R.drawable.category_chip_bg)
                setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(8) }
                setOnClickListener {
                    chatContainer.removeView(wrapper)
                    lifecycleScope.launch {
                        val ok = healthReader.saveWaterIntake(ml.toDouble())
                        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        if (ok) addChatBubble("💧 ${ml}ml 수분 섭취 기록 완료!", isUser = false, timeLabel = t)
                        else addChatBubble("❌ 수분 기록 실패\nHealth Connect 권한을 확인해주세요.", isUser = false, timeLabel = t)
                    }
                }
            }
            btnRow.addView(btn)
        }

        val hScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(4) }
            isHorizontalScrollBarEnabled = false
            addView(btnRow)
        }
        wrapper.addView(hScroll)

        val closeBtn = TextView(this).apply {
            text = "✕ 닫기"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF79747E.toInt())
            setBackgroundResource(R.drawable.category_chip_bg)
            setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(6) }
            setOnClickListener { chatContainer.removeView(wrapper) }
        }
        wrapper.addView(closeBtn)

        val lbl = TextView(this).apply {
            text = "Claude  $timeLabel"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF79747E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(3) }
        }
        wrapper.addView(lbl)
        chatContainer.addView(wrapper)
        scrollToBottom()
    }

    // ==================== AI 건강 코치 ====================

    private fun triggerAiCoach() {
        val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        addChatBubble("🤖 AI 건강 코치 분석 중...", isUser = false, timeLabel = resTime)
        startTypingIndicator()

        lifecycleScope.launch {
            try {
                val context = healthReader.buildComprehensiveAiCoachContext()
                val prompt = """당신은 전문 AI 건강 코치입니다. 아래 건강 데이터를 분석해 맞춤형 건강 조언을 제공해주세요.

$context

다음을 포함해 종합 분석해주세요:
1. 오늘 건강 상태 요약 (걸음수·심박수·수면 평가)
2. 식사·영양 분석 (섭취 칼로리 vs 소모 칼로리)
3. 수분 섭취 현황
4. 주간 트렌드에서 발견된 패턴 및 개선점
5. 오늘 바로 실천할 수 있는 건강 행동 3가지

친근하고 격려하는 어조로 500자 이내로 간결하게 답해주세요."""

                val response = chatClient.sendMessage(prompt, context)
                stopTypingIndicator()
                val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                addChatBubble("🤖 AI 건강 코치\n\n$response", isUser = false, timeLabel = t)
            } catch (e: Exception) {
                stopTypingIndicator()
                val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                addChatBubble("❌ AI 코치 오류: ${e.message}", isUser = false, timeLabel = t)
            }
        }
    }

    // ==================== 주간 트렌드 ====================

    private fun showWeeklyTrendBubble() {
        lifecycleScope.launch {
            val resTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val trends = try { healthReader.readWeeklyTrends() } catch (e: Exception) { emptyList() }

            if (trends.isEmpty()) { addHealthBubble("📊 주간 트렌드", "데이터가 없습니다."); return@launch }

            val sb = StringBuilder()
            val fmt = java.time.format.DateTimeFormatter.ofPattern("M/d(E)", java.util.Locale.KOREAN)

            // 걸음수
            val stepsData = trends.filter { it.steps != null }
            if (stepsData.isNotEmpty()) {
                sb.appendLine("👣 걸음수")
                val maxS = stepsData.maxOf { it.steps!! }.coerceAtLeast(1)
                for (d in trends) {
                    val ds = d.date.format(fmt)
                    if (d.steps != null) {
                        val bar = "█".repeat(((d.steps.toDouble() / maxS) * 8).toInt().coerceAtLeast(1))
                        sb.appendLine("$ds  $bar  ${"%,d".format(d.steps)}")
                    } else sb.appendLine("$ds  -")
                }
                sb.appendLine()
            }

            // 칼로리 소모 vs 섭취
            val calsHaveData = trends.any { it.caloriesBurned != null || it.nutritionCalories != null }
            if (calsHaveData) {
                sb.appendLine("🔥 칼로리  소모 / 섭취")
                for (d in trends) {
                    val ds = d.date.format(fmt)
                    val burned = d.caloriesBurned?.let { "${"%.0f".format(it)}소모" } ?: ""
                    val intake = d.nutritionCalories?.let { "${"%.0f".format(it)}섭취" } ?: ""
                    val info = listOf(burned, intake).filter { it.isNotEmpty() }.joinToString(" / ")
                    sb.appendLine("$ds  ${if (info.isEmpty()) "-" else info}")
                }
                sb.appendLine()
            }

            // 수면
            val sleepData = trends.filter { it.sleepHours != null }
            if (sleepData.isNotEmpty()) {
                sb.appendLine("💤 수면")
                val maxSl = sleepData.maxOf { it.sleepHours!! }.coerceAtLeast(1.0)
                for (d in trends) {
                    val ds = d.date.format(fmt)
                    if (d.sleepHours != null) {
                        val bar = "█".repeat(((d.sleepHours / maxSl) * 8).toInt().coerceAtLeast(1))
                        sb.appendLine("$ds  $bar  ${"%.1f".format(d.sleepHours)}h")
                    } else sb.appendLine("$ds  -")
                }
                sb.appendLine()
            }

            // 수분
            val waterData = trends.filter { it.waterMl != null }
            if (waterData.isNotEmpty()) {
                sb.appendLine("💧 수분 섭취")
                for (d in trends) {
                    val ds = d.date.format(fmt)
                    if (d.waterMl != null) {
                        val bar = "🔵".repeat(((d.waterMl / 2000.0) * 8).toInt().coerceIn(1, 8))
                        sb.appendLine("$ds  $bar  ${"%.0f".format(d.waterMl)}ml")
                    } else sb.appendLine("$ds  -")
                }
            }

            addHealthBubble("📊 주간 트렌드 ($resTime)", sb.toString().trimEnd())
        }
    }

    // ==================== Tab Navigation ====================

    private fun switchTab(tab: String) {
        currentTab = tab
        tabHome.visibility = if (tab == "home") View.VISIBLE else View.GONE
        tabChat.visibility = if (tab == "chat") View.VISIBLE else View.GONE
        tabRecords.visibility = if (tab == "records") View.VISIBLE else View.GONE
        tabProfile.visibility = if (tab == "profile") View.VISIBLE else View.GONE

        val orange = 0xFFFF6F0F.toInt()
        val gray = 0xFF888888.toInt()
        listOf(
            Triple(navHomeIcon, navHomeLabel, "home"),
            Triple(navChatIcon, navChatLabel, "chat"),
            Triple(navRecordsIcon, navRecordsLabel, "records"),
            Triple(navProfileIcon, navProfileLabel, "profile")
        ).forEach { (icon, label, t) ->
            val active = t == tab
            icon.setColorFilter(if (active) orange else gray)
            label.setTextColor(if (active) orange else gray)
        }

        tvTopBarDate.visibility = if (tab == "home") View.VISIBLE else View.GONE
        tvTopBarTitle.visibility = if (tab != "home") View.VISIBLE else View.GONE
        tvHealthScoreBadge.visibility = if (tab == "home") View.VISIBLE else View.GONE
        btnBell.visibility = if (tab == "home") View.VISIBLE else View.GONE
        btnSessionMenu.visibility = if (tab == "chat") View.VISIBLE else View.GONE
        btnHealthMenu.visibility = if (tab == "chat") View.VISIBLE else View.GONE

        when (tab) {
            "chat" -> tvTopBarTitle.text = "AI 코치"
            "records" -> tvTopBarTitle.text = "건강 기록"
            "profile" -> tvTopBarTitle.text = "마이페이지"
        }
    }

    private fun buildHomeDashboard(summary: HealthSummary) {
        val scoreVal = summary.healthScore ?: 70
        tvHealthScoreValue.text = scoreVal.toString()
        tvHealthScoreBadge.text = "${scoreVal}점"
        pbHealthScore.progress = scoreVal
        tvHealthScoreGrade.text = when {
            scoreVal >= 90 -> "최우수 🌟"
            scoreVal >= 80 -> "우수 💪"
            scoreVal >= 70 -> "양호 👍"
            scoreVal >= 60 -> "보통 😊"
            else -> "주의 필요 ⚠️"
        }
        val sleepScore = summary.sleepScore ?: 0
        tvSleepScoreSmall.text = if (sleepScore > 0) "$sleepScore" else "--"
        tvStressLevelSmall.text = when (summary.stressLevel) {
            "낮음" -> "낮음 😌"; "보통" -> "보통 😐"; "높음" -> "높음 😰"
            "매우높음" -> "매우높음 😱"; else -> "--"
        }
        tvSleepSummary.text = if (summary.sleepHours != null) {
            val h = summary.sleepHours
            "수면 시간: ${"%.1f".format(h)}시간" +
            (if (sleepScore > 0) "  |  수면 점수: ${sleepScore}점" else "") +
            when { h in 7.0..9.0 -> " ✅"; h >= 6.0 -> " ⚠️ 약간 부족"; else -> " ❌ 수면 부족" }
        } else "수면 데이터 없음"
        tvNutritionSummary.text = if (summary.nutritionTodayCalories != null) {
            "칼로리: ${"%.0f".format(summary.nutritionTodayCalories)}kcal" +
            (summary.nutritionTodayCarbs?.let { "  탄: ${"%.0f".format(it)}g" } ?: "") +
            (summary.nutritionTodayProtein?.let { "  단: ${"%.0f".format(it)}g" } ?: "") +
            (summary.nutritionTodayFat?.let { "  지: ${"%.0f".format(it)}g" } ?: "")
        } else "식사 기록이 없습니다"
        tvExerciseSummary.text = if (summary.exercises.isNotEmpty())
            "최근 7일: " + summary.exercises.take(3).joinToString(", ")
        else "최근 7일 운동 기록이 없습니다"

        buildMetricCards(summary)
        buildRecordsDashboard(summary)
    }

    private data class MetricCardData(
        val iconRes: Int, val value: String, val unit: String,
        val label: String, val hasData: Boolean = true
    )

    private fun buildMetricCards(s: HealthSummary) {
        metricsContainer.removeAllViews()
        val metrics = listOf(
            MetricCardData(R.drawable.ic_steps, s.steps?.let { "%,d".format(it) } ?: "--", "보", "걸음수", s.steps != null),
            MetricCardData(R.drawable.ic_heart, s.heartRateAvg?.toString() ?: "--", "bpm", "심박수", s.heartRateAvg != null),
            MetricCardData(R.drawable.ic_sleep, s.sleepHours?.let { "%.1f".format(it) } ?: "--", "시간", "수면", s.sleepHours != null),
            MetricCardData(R.drawable.ic_fire, s.caloriesBurned?.let { "%.0f".format(it) } ?: "--", "kcal", "총칼로리", s.caloriesBurned != null),
            MetricCardData(R.drawable.ic_fire, s.activeCaloriesBurned?.let { "%.0f".format(it) } ?: "--", "kcal", "활동칼로리", s.activeCaloriesBurned != null),
            MetricCardData(R.drawable.ic_blood_pressure,
                if (s.bloodPressureSystolic != null && s.bloodPressureDiastolic != null)
                    "${s.bloodPressureSystolic.toInt()}/${s.bloodPressureDiastolic.toInt()}"
                else "--", "mmHg", "혈압", s.bloodPressureSystolic != null),
            MetricCardData(R.drawable.ic_glucose, s.bloodGlucose?.let { "%.0f".format(it) } ?: "--", "mg/dL", "혈당", s.bloodGlucose != null),
            MetricCardData(R.drawable.ic_spo2, s.oxygenSaturation?.let { "%.1f".format(it) } ?: "--", "%", "산소포화도", s.oxygenSaturation != null),
            MetricCardData(R.drawable.ic_water_drop, "--", "ml", "수분", false),
            MetricCardData(R.drawable.ic_breath, s.respiratoryRate?.let { "%.0f".format(it) } ?: "--", "/분", "호흡수", s.respiratoryRate != null),
            MetricCardData(R.drawable.ic_vo2, s.vo2Max?.let { "%.1f".format(it) } ?: "--", "ml/kg", "VO2Max", s.vo2Max != null),
            MetricCardData(R.drawable.ic_floors, s.floorsClimbed?.let { "%.0f".format(it) } ?: "--", "층", "오른층수", s.floorsClimbed != null),
            MetricCardData(R.drawable.ic_weight, s.weight?.let { "%.1f".format(it) } ?: "--", "kg", "체중", s.weight != null),
            MetricCardData(R.drawable.ic_stress, s.stressLevel?.takeIf { it != "데이터없음" } ?: "--", "", "스트레스", s.stressLevel != null && s.stressLevel != "데이터없음"),
        )
        for (m in metrics) metricsContainer.addView(createMetricCard(m))
    }

    private fun createMetricCard(m: MetricCardData): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(14), dpToPx(12), dpToPx(14))
            background = resources.getDrawable(R.drawable.daangn_metric_card_bg, null)
            elevation = dpToPx(2).toFloat()
            val lp = LinearLayout.LayoutParams(dpToPx(108), dpToPx(130))
            lp.marginEnd = dpToPx(10)
            layoutParams = lp
        }
        val icon = ImageView(this).apply {
            val sz = dpToPx(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { bottomMargin = dpToPx(6) }
            setImageResource(m.iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            if (!m.hasData) alpha = 0.4f
        }
        val valueTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            text = m.value
            textSize = if (m.value.length > 6) 15f else 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (m.hasData) 0xFF1C1B1F.toInt() else 0xFFAAAAAA.toInt())
        }
        val unitTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            text = m.unit
            textSize = 10f
            setTextColor(0xFF888888.toInt())
        }
        val labelTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(4) }
            text = m.label
            textSize = 11f
            setTextColor(0xFF666666.toInt())
        }
        card.addView(icon); card.addView(valueTv); card.addView(unitTv); card.addView(labelTv)
        return card
    }

    private fun buildRecordsDashboard(summary: HealthSummary) {
        recordsContent.removeAllViews()
        fun addCard(emoji: String, title: String, subtitle: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = resources.getDrawable(R.drawable.daangn_card_bg, null)
                setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
                elevation = dpToPx(1).toFloat()
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dpToPx(12)
                layoutParams = lp
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            card.addView(TextView(this).apply { text = emoji; textSize = 24f; setPadding(0,0,dpToPx(12),0) })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply { text = title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF1C1B1F.toInt()) })
            col.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(0xFF888888.toInt()) })
            card.addView(col)
            card.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(0xFFAAAAAA.toInt()) })
            recordsContent.addView(card)
        }
        addCard("🍽️", "식사 기록", summary.nutritionTodayCalories?.let { "오늘 ${"%.0f".format(it)}kcal" } ?: "기록하기") { switchTab("chat"); showFoodLogBubble() }
        addCard("💧", "수분 섭취", "하루 권장 2,000ml") { switchTab("chat"); showWaterBubble() }
        addCard("📊", "주간 트렌드", "7일 건강 데이터") { switchTab("chat"); showWeeklyTrendBubble() }
        if (summary.exercises.isNotEmpty())
            addCard("🏋️", "운동 기록", "${summary.exercises.size}건 (최근 7일)") { switchTab("chat"); onCategoryClick(categories.find { it.key == "exercise" }!!) }

        val sectionTv = TextView(this).apply {
            text = "상세 건강 데이터"
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF1C1B1F.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dpToPx(8); lp.bottomMargin = dpToPx(8); layoutParams = lp
        }
        recordsContent.addView(sectionTv)

        addCard("🩺", "혈압", summary.bloodPressureSystolic?.let { "${it.toInt()}/${summary.bloodPressureDiastolic?.toInt()}mmHg" } ?: "측정 없음") { switchTab("chat"); onCategoryClick(categories.find { it.key == "blood_pressure" }!!) }
        addCard("🩸", "혈당", summary.bloodGlucose?.let { "${"%.0f".format(it)}mg/dL" } ?: "측정 없음") { switchTab("chat"); onCategoryClick(categories.find { it.key == "blood_glucose" }!!) }
        addCard("🧬", "건강 점수 분석", "${summary.healthScore ?: "--"}점/100") { switchTab("chat"); onCategoryClick(categories.find { it.key == "health_score" }!!) }
        addCard("😤", "스트레스", summary.stressLevel?.takeIf { it != "데이터없음" } ?: "측정 없음") { switchTab("chat"); onCategoryClick(categories.find { it.key == "stress" }!!) }
    }

    private fun buildProfileTab() {
        profileContent.removeAllViews()
        fun addSection(title: String, content: String) {
            val titleTv = TextView(this).apply {
                text = title; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF888888.toInt())
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dpToPx(16); lp.bottomMargin = dpToPx(4); layoutParams = lp
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = resources.getDrawable(R.drawable.daangn_card_bg, null)
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                elevation = dpToPx(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            card.addView(TextView(this).apply {
                text = content; textSize = 14f; setTextColor(0xFF1C1B1F.toInt()); setLineSpacing(4f, 1.2f)
            })
            profileContent.addView(titleTv); profileContent.addView(card)
        }
        addSection("체성분", currentSummary?.let {
            val sb = StringBuilder()
            it.weight?.let { w -> sb.appendLine("체중: ${"%.1f".format(w)}kg") }
            it.height?.let { h -> sb.appendLine("키: ${"%.0f".format(h * 100)}cm") }
            it.bmi?.let { b -> sb.appendLine("BMI: ${"%.1f".format(b)}") }
            it.bodyFat?.let { f -> sb.appendLine("체지방률: ${"%.1f".format(f)}%") }
            it.leanBodyMass?.let { l -> sb.appendLine("근육량: ${"%.1f".format(l)}kg") }
            it.basalMetabolicRate?.let { b -> sb.appendLine("기초대사량: ${b}kcal") }
            sb.toString().trimEnd()
        } ?: "건강 데이터 로딩 후 확인 가능합니다")
        addSection("앱 정보", "Health Chat v1.0\nAI 기반 건강 관리\nPowered by Claude AI")
        profileContent.addView(Button(this).apply {
            text = "⚙️  Health Connect 권한 설정"
            setBackgroundColor(0xFFFF6F0F.toInt()); setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dpToPx(16); layoutParams = lp
            setOnClickListener { openHealthConnectSettings() }
        })
    }

    private fun buildBloodPressureText(s: HealthSummary): String {
        if (s.bloodPressureSystolic == null) return "혈압 데이터가 없습니다.\n삼성 헬스에서 혈압 측정 후 Health Connect에서 동기화해주세요."
        val sys = s.bloodPressureSystolic.toInt()
        val dia = s.bloodPressureDiastolic?.toInt() ?: 0
        val status = when { sys < 120 && dia < 80 -> "정상 ✅"; sys < 130 && dia < 80 -> "주의 ⚠️"; sys < 140 || dia < 90 -> "고혈압 전단계 ⚠️"; else -> "고혈압 ❌" }
        return "수축기: ${sys}mmHg\n이완기: ${dia}mmHg\n상태: $status\n\n정상: 120/80mmHg 미만"
    }

    private fun buildBloodGlucoseText(s: HealthSummary): String {
        if (s.bloodGlucose == null) return "혈당 데이터가 없습니다."
        val v = s.bloodGlucose
        val status = when { v < 70 -> "저혈당 ⚠️"; v < 100 -> "정상 ✅"; v < 126 -> "전당뇨 ⚠️"; else -> "높음 ❌" }
        return "혈당: ${"%.0f".format(v)}mg/dL\n상태: $status\n\n정상 공복: 70-99mg/dL"
    }

    private fun buildRespiratoryText(s: HealthSummary): String {
        if (s.respiratoryRate == null) return "호흡수 데이터가 없습니다."
        val rate = s.respiratoryRate
        val status = when { rate in 12.0..20.0 -> "정상 ✅"; rate < 12.0 -> "서호흡 ⚠️"; else -> "빈호흡 ⚠️" }
        return "호흡수: ${"%.0f".format(rate)}회/분\n상태: $status\n정상: 12-20회/분"
    }

    private fun buildVo2MaxText(s: HealthSummary): String {
        if (s.vo2Max == null) return "VO2Max 데이터가 없습니다.\n달리기 운동 후 측정됩니다."
        val v = s.vo2Max
        val level = when { v >= 55.0 -> "매우 우수 🌟"; v >= 45.0 -> "우수 💪"; v >= 35.0 -> "보통 👍"; v >= 25.0 -> "낮음 ⚠️"; else -> "매우 낮음 ❌" }
        return "VO2Max: ${"%.1f".format(v)}ml/kg/min\n심폐 체력: $level"
    }

    private fun buildFloorsText(s: HealthSummary): String {
        if (s.floorsClimbed == null) return "오른 층수 데이터가 없습니다."
        val floors = s.floorsClimbed.toInt()
        return "오늘 오른 층수: ${floors}층\n${when { floors >= 10 -> "훌륭해요! 🎉"; floors >= 5 -> "좋아요 👍"; else -> "계단을 더 이용해보세요 🏃" }}\n목표: 하루 10층 이상"
    }

    private fun buildActiveCaloriesText(s: HealthSummary): String {
        val sb = StringBuilder()
        s.activeCaloriesBurned?.let { sb.appendLine("활동 칼로리: ${"%.0f".format(it)}kcal") }
        s.caloriesBurned?.let { sb.appendLine("총 소모 칼로리: ${"%.0f".format(it)}kcal") }
        if (s.activeCaloriesBurned != null && s.caloriesBurned != null) {
            val basal = s.caloriesBurned - s.activeCaloriesBurned
            if (basal > 0) sb.appendLine("기초 대사: ${"%.0f".format(basal)}kcal")
        }
        return if (sb.isEmpty()) "활동 칼로리 데이터가 없습니다." else sb.append("\n목표: 활동 칼로리 300kcal+").toString().trimEnd()
    }

    private fun buildHealthScoreText(s: HealthSummary): String {
        if (s.healthScore == null) return "건강 점수를 계산할 데이터가 부족합니다."
        val grade = when { s.healthScore >= 90 -> "최우수"; s.healthScore >= 80 -> "우수"; s.healthScore >= 70 -> "양호"; s.healthScore >= 60 -> "보통"; else -> "주의 필요" }
        val sb = StringBuilder()
        sb.appendLine("🏆 건강 점수: ${s.healthScore}/100  ($grade)")
        sb.appendLine()
        sb.appendLine("📊 항목별 평가:")
        s.steps?.let { sb.appendLine("  👣 걸음수: ${if (it >= 10000) "✅" else "⚠️"} ${"%,d".format(it)}보") }
        s.sleepHours?.let { sb.appendLine("  💤 수면: ${if (it in 7.0..9.0) "✅" else "⚠️"} ${"%.1f".format(it)}시간") }
        s.heartRateAvg?.let { sb.appendLine("  ❤️ 심박수: ${if (it in 60..80) "✅" else "⚠️"} ${it}bpm") }
        s.oxygenSaturation?.let { sb.appendLine("  🫁 산소: ${if (it >= 95) "✅" else "⚠️"} ${"%.1f".format(it)}%") }
        s.bmi?.let { sb.appendLine("  ⚖️ BMI: ${if (it in 18.5..23.0) "✅" else "⚠️"} ${"%.1f".format(it)}") }
        s.hrv?.let { sb.appendLine("  🧠 HRV: ${if (it >= 35.0) "✅" else "⚠️"} ${"%.1f".format(it)}ms") }
        return sb.toString().trimEnd()
    }

    private fun buildSleepScoreText(s: HealthSummary): String {
        if (s.sleepScore == null || s.sleepScore == 0) return "수면 점수 데이터가 없습니다."
        val grade = when { s.sleepScore >= 85 -> "우수 😴✨"; s.sleepScore >= 70 -> "양호 😴"; s.sleepScore >= 50 -> "보통 😐"; else -> "불량 ⚠️" }
        return "💤 수면 점수: ${s.sleepScore}/100  ($grade)\n수면 시간: ${s.sleepHours?.let { "%.1f".format(it) } ?: "--"}시간\n\n수면 시간(60점) + 깊은수면 비율(40점)"
    }

    private fun buildNutritionAnalysisText(s: HealthSummary): String {
        if (s.nutritionTodayCalories == null) return "오늘 영양 기록이 없습니다.\n📷 카메라로 음식 사진을 찍어 기록하세요!"
        val sb = StringBuilder()
        sb.appendLine("🥗 오늘 영양 분석")
        sb.appendLine("칼로리: ${"%.0f".format(s.nutritionTodayCalories)}kcal")
        s.nutritionTodayCarbs?.let { sb.appendLine("탄수화물: ${"%.0f".format(it)}g") }
        s.nutritionTodayProtein?.let { sb.appendLine("단백질: ${"%.0f".format(it)}g") }
        s.nutritionTodayFat?.let { sb.appendLine("지방: ${"%.0f".format(it)}g") }
        val total = (s.nutritionTodayCarbs ?: 0.0) + (s.nutritionTodayProtein ?: 0.0) + (s.nutritionTodayFat ?: 0.0)
        if (total > 0) {
            val c = ((s.nutritionTodayCarbs ?: 0.0) / total * 100).toInt()
            val p = ((s.nutritionTodayProtein ?: 0.0) / total * 100).toInt()
            val f = ((s.nutritionTodayFat ?: 0.0) / total * 100).toInt()
            sb.appendLine("\n매크로 비율:\n  탄: $c%  단: $p%  지: $f%")
            sb.appendLine("권장: 탄 50-60% / 단 15-25% / 지 20-35%")
        }
        return sb.toString().trimEnd()
    }

    private fun buildStressText(s: HealthSummary): String {
        val level = s.stressLevel ?: "데이터없음"
        val emoji = when (level) { "낮음" -> "😌"; "보통" -> "😐"; "높음" -> "😰"; "매우높음" -> "😱"; else -> "🤔" }
        val sb = StringBuilder()
        sb.appendLine("$emoji 스트레스: $level")
        s.hrv?.let { sb.appendLine("HRV: ${"%.1f".format(it)}ms RMSSD") }
        sb.appendLine("\nHRV 스트레스 기준:\n  >60ms 낮음  35-60ms 보통  15-35ms 높음  <15ms 매우높음")
        if (s.hrv == null) sb.appendLine("\n⚠️ 갤럭시 워치에서 스트레스 측정을 활성화해주세요.")
        return sb.toString().trimEnd()
    }

    // ==================== Session Adapter ====================

    inner class SessionListAdapter(
        private val sessions: List<ChatApiClient.SessionInfo>,
        private val currentId: String?,
        private val onSessionClick: (String) -> Unit
    ) : RecyclerView.Adapter<SessionListAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvSessionTitle)
            val tvMeta: TextView = v.findViewById(R.id.tvSessionMeta)

            fun bind(session: ChatApiClient.SessionInfo, isCurrent: Boolean) {
                tvTitle.text = session.title.take(20).ifBlank { "새 대화" }

                val dateStr = try {
                    val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                    val parsed = java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()
                    ).parse(session.updatedAt)
                    if (parsed != null) sdf.format(parsed) else session.updatedAt.take(10)
                } catch (e: Exception) { session.updatedAt.take(10) }

                tvMeta.text = "$dateStr · ${session.messageCount}개"

                if (isCurrent) {
                    itemView.setBackgroundResource(R.drawable.session_item_selected_bg)
                    tvTitle.setTextColor(0xFF4F378B.toInt())
                    tvTitle.setTypeface(null, Typeface.BOLD)
                } else {
                    itemView.setBackgroundResource(R.drawable.session_item_bg)
                    tvTitle.setTextColor(0xFF1C1B1F.toInt())
                    tvTitle.setTypeface(null, Typeface.NORMAL)
                }

                itemView.setOnClickListener { onSessionClick(session.id) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(sessions[position], sessions[position].id == currentId)
        }

        override fun getItemCount() = sessions.size
    }
}

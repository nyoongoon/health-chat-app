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
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
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
import kotlinx.coroutines.launch
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
    private lateinit var btnClosePanel: TextView
    private lateinit var tvSessionSubtitle: TextView

    private var currentHealthContext: String? = null
    private var currentSummary: HealthSummary? = null
    private var categoryMenuOpen = false
    private var sessionsPanelOpen = false
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

    // ==================== Health Data ====================

    private fun loadHealthData() {
        lifecycleScope.launch {
            try {
                val summary = healthReader.readTodayData()
                currentSummary = summary
                currentHealthContext = summary.toContextString()
                Log.d("HealthChat", "Health context loaded:\n$currentHealthContext")
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

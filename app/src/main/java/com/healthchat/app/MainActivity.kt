package com.healthchat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var healthReader: HealthDataReader
    private lateinit var chatClient: ChatApiClient

    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: View
    private lateinit var btnHealthMenu: View
    private lateinit var btnSessionMenu: View
    private lateinit var healthCategoryScroll: HorizontalScrollView
    private lateinit var healthCategoryContainer: LinearLayout
    private lateinit var sessionsPanelContainer: LinearLayout
    private lateinit var sessionsRecyclerView: RecyclerView
    private lateinit var btnNewSession: Button
    private lateinit var btnClosePanel: TextView
    private lateinit var dimOverlay: View
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

    data class HealthCategory(val emoji: String, val label: String, val key: String)

    private val categories = listOf(
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
        btnHealthMenu = findViewById(R.id.btnHealthMenu)
        btnSessionMenu = findViewById(R.id.btnSessionMenu)
        healthCategoryScroll = findViewById(R.id.healthCategoryScroll)
        healthCategoryContainer = findViewById(R.id.healthCategoryContainer)
        sessionsPanelContainer = findViewById(R.id.sessionsPanelContainer)
        sessionsRecyclerView = findViewById(R.id.sessionsRecyclerView)
        btnNewSession = findViewById(R.id.btnNewSession)
        btnClosePanel = findViewById(R.id.btnClosePanel)
        dimOverlay = findViewById(R.id.dimOverlay)
        tvSessionSubtitle = findViewById(R.id.tvSessionSubtitle)

        // Position session panel off-screen initially
        sessionsPanelContainer.post {
            sessionsPanelContainer.translationX = -dpToPx(260).toFloat()
        }

        btnSend.setOnClickListener { sendMessage() }

        btnHealthMenu.setOnClickListener {
            categoryMenuOpen = !categoryMenuOpen
            healthCategoryScroll.visibility = if (categoryMenuOpen) View.VISIBLE else View.GONE
        }

        btnSessionMenu.setOnClickListener {
            if (sessionsPanelOpen) closeSessionPanel() else openSessionPanel()
        }

        btnClosePanel.setOnClickListener { closeSessionPanel() }
        dimOverlay.setOnClickListener { closeSessionPanel() }
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
        dimOverlay.visibility = View.VISIBLE
        dimOverlay.alpha = 0f

        sessionsPanelContainer.animate()
            .translationX(0f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()

        dimOverlay.animate()
            .alpha(1f)
            .setDuration(280)
            .start()

        loadSessionsList()
    }

    private fun closeSessionPanel() {
        sessionsPanelOpen = false
        val panelW = dpToPx(260).toFloat()

        sessionsPanelContainer.animate()
            .translationX(-panelW)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { sessionsPanelContainer.visibility = View.INVISIBLE }
            .start()

        dimOverlay.animate()
            .alpha(0f)
            .setDuration(240)
            .withEndAction { dimOverlay.visibility = View.GONE }
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
        val summary = currentSummary
        if (summary == null) {
            addSystemMessage("건강 데이터를 아직 불러오지 못했습니다. 잠시 후 다시 시도해주세요.")
            return
        }
        categoryMenuOpen = false
        healthCategoryScroll.visibility = View.GONE

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
        if (msg.isEmpty()) return

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

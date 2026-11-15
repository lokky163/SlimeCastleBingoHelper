package com.slimecastle.bingohelper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.slimecastle.bingohelper.strategy.BingoStrategy
import com.slimecastle.bingohelper.strategy.FIELD_SIZE
import com.slimecastle.bingohelper.strategy.Field
import kotlin.math.roundToInt

/**
 * Foreground service with the floating panel, screen capture, live hint overlay and auto player.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.slimecastle.bingohelper.action.START"
        const val ACTION_STOP = "com.slimecastle.bingohelper.action.STOP"
        private const val ACTION_CLEAR_CALIBRATION =
            "com.slimecastle.bingohelper.action.CLEAR_CALIBRATION"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bingo_overlay"
        private const val TAG = "BingoHelper"

        /** Popups are closed by two taps in the free bottom-right corner of the game. */
        private const val DISMISS_X = 0.90f
        private const val DISMISS_Y = 0.96f
        private const val DISMISS_TAP_GAP_MS = 100L

        private const val PANEL_WIDTH_DP = 336
        private const val HINT_POLL_INTERVAL_MS = 100L
        private const val AUTO_FRAME_INTERVAL_MS = 50L
        private const val AUTO_FRAME_TIMEOUT_MS = 40L
        private const val DEFAULT_POLL_INTERVAL_MS = 250L
        private const val BOARD_INACTIVITY_TIMEOUT_MS = 10_000L

        /** The panel is parked above the board and may not grow past this share of height. */
        private const val PANEL_HEIGHT_SHARE = 0.36f

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                // Service is not running.
            }
        }

        fun clearCalibration(context: Context) {
            AppPrefs.clearCalibration(context)
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, OverlayService::class.java).apply {
                        action = ACTION_CLEAR_CALIBRATION
                    },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot notify overlay about reset calibration", t)
            }
        }
    }

    private enum class Group(val label: String) {
        BOARD("Поле"),
        RESET("Перезапуск"),
    }

    private class RecognitionOutcome(
        val message: String,
        val calibration: Calibration?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager

    // Panel views.
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var detailsView: View? = null
    private var collapseButton: TextView? = null
    private var panelCollapsed = false
    private var statusView: TextView? = null
    private var hintButton: Button? = null
    private var accessibilityNotice: TextView? = null
    private var autoButton: ImageButton? = null
    private var roundsUnlimited: RadioButton? = null
    private var roundsLimited: RadioButton? = null
    private var roundsEdit: EditText? = null
    private var moveWaitEdit: EditText? = null
    private var beforeRestartWaitEdit: EditText? = null
    private var restartWaitEdit: EditText? = null
    private var rewardCloseWaitEdit: EditText? = null
    private var modeX1: RadioButton? = null
    private var modeX10: RadioButton? = null
    private val recognizeButtons = LinkedHashMap<Group, Button>()
    private val toggleButtons = LinkedHashMap<Group, Button>()
    private val shownGroups = HashSet<Group>()
    private var panelFocusable = false
    private var resetCalibrationArmedUntil = 0L

    private var gridView: GridOverlayView? = null
    private val hintOutline = ArrayList<View>(4)
    private var outlinedCell = -1

    // Capture.
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var screenCapture: ScreenCapture? = null
    private var mediaProjection: MediaProjection? = null

    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDensity = 0
    private val minPanelY: Int by lazy {
        val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
            ).top
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) resources.getDimensionPixelSize(id) else 0
        }
        // Use the system-reported status-bar/cutout boundary, without an extra fixed gap.
        if (topInset > 0) topInset else dp(24)
    }

    private var capturing = false
    private var recognizing = false

    // Calibrated game description.
    private var calibration: Calibration? = null
    private var vision: GameVision? = null

    // Auto player.
    private var autoRunning = false
    private var autoSeq = 0
    private var autoField = Field()
    private var pendingCell = -1
    private var moveWaitMs = AppPrefs.DEFAULT_MOVE_WAIT_MS
    private var beforeRestartWaitMs = AppPrefs.DEFAULT_BEFORE_RESTART_WAIT_MS
    private var restartWaitMs = AppPrefs.DEFAULT_RESTART_WAIT_MS
    private var rewardCloseWaitMs = AppPrefs.DEFAULT_REWARD_CLOSE_WAIT_MS
    private var lastBoardRecognizedAt = 0L
    private val roundProgress = RoundProgress()
    private var roundLimit = 0
    private var resetAttempts = 0

    // Autoclicks suspend hints temporarily, preserving the user's choice for later.
    private var hintEnabled = false
    private var hintBeforeAuto = false
    private var overlaySeq = 0
    private var frameFailures = 0
    private var accessibilityConnected = false

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        isRunning = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val thread = HandlerThread("bingo-capture")
        thread.start()
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDensity = metrics.densityDpi

        loadCalibration()
        panelCollapsed = AppPrefs.panelCollapsed(this)
        accessibilityConnected = TapAccessibilityService.isConnected()
    }

    private fun loadCalibration() {
        val saved = AppPrefs.calibration(this) ?: return
        if (!saved.matchesScreen(displayWidth, displayHeight)) {
            Log.w(
                TAG,
                "Dropping calibration for ${saved.screenWidth}x${saved.screenHeight}, " +
                    "current screen is ${displayWidth}x$displayHeight",
            )
            AppPrefs.clearCalibration(this)
            return
        }
        calibration = saved
        vision = GameVision(saved)
        Log.d(TAG, "Calibration loaded: board=${saved.hasBoard} reset=${saved.hasReset}")
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLEAR_CALIBRATION -> {
                wipeCalibration()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundWithNotification()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
                }

                if (mediaProjection == null && data != null) {
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = try {
                        manager.getMediaProjection(resultCode, data)
                    } catch (_: Throwable) {
                        null
                    }

                    if (projection == null) {
                        Toast.makeText(
                            this,
                            "Не удалось получить захват экрана",
                            Toast.LENGTH_LONG,
                        ).show()
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    mediaProjection = projection
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            mainHandler.post { stopSelf() }
                        }
                    }, mainHandler)

                    screenCapture = ScreenCapture(
                        projection = projection,
                        handler = captureHandler!!,
                        width = displayWidth,
                        height = displayHeight,
                        densityDpi = displayDensity,
                    )
                }

                showMenu()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false

        autoRunning = false
        autoSeq++
        overlaySeq++
        mainHandler.removeCallbacksAndMessages(null)

        removeGrid()
        removeMenu()

        screenCapture?.release()
        screenCapture = null
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        stopForeground(true)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("BingoHelper")
            .setContentText("Меню поверх приложений активно")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Остановить", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Плавающее меню",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Уведомление о работе меню поверх игр"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- floating panel

    private fun showMenu() {
        if (menuView != null) return

        val panel = createMenuView()
        val params = WindowManager.LayoutParams(
            dp(PANEL_WIDTH_DP),
            if (panelCollapsed) WindowManager.LayoutParams.WRAP_CONTENT else basePanelHeight(),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(6)
            y = minPanelY
            allowDisplayCutout()
        }

        try {
            windowManager.addView(panel, params)
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot add floating menu", e)
            Toast.makeText(
                this,
                "Не удалось показать меню: ${e.message ?: e.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
            stopSelf()
            return
        }

        menuView = panel
        menuParams = params
        refreshPanel()
        monitorAccessibility()
        val saved = calibration
        if (saved?.ready == true) {
            setStatus("Калибровка поля и кнопок перезапуска загружена.")
        } else if (saved?.hasBoard == true) {
            setStatus("Поле загружено. Подсказка доступна; для автокликов распознайте перезапуск.")
        } else {
            setStatus("Для подсказки распознайте поле до первого хода.")
        }
    }

    private fun createMenuView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF0181B22.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val title = TextView(this).apply {
            text = "BingoHelper"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val resetCalibration = TextView(this).apply {
            text = "Сброс калибр."
            setTextColor(0xAAAAFFB0.toInt())
            textSize = 10f
            setPadding(dp(3), dp(4), dp(3), dp(4))
            setOnClickListener { resetCalibration() }
        }
        header.addView(resetCalibration)

        collapseButton = TextView(this).apply {
            text = if (panelCollapsed) "▼" else "▲"
            contentDescription = if (panelCollapsed) "Развернуть меню" else "Свернуть меню"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setOnClickListener { setPanelCollapsed(!panelCollapsed) }
        }
        header.addView(collapseButton)

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 17f
            setPadding(dp(8), dp(2), dp(2), dp(2))
            setOnClickListener { stopSelf() }
        }
        header.addView(close)

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        attachDragHandle(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        detailsView = scroll
        if (panelCollapsed) scroll.visibility = View.GONE

        statusView = TextView(this).apply {
            text = "Распознайте поле и кнопки перезапуска."
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10.5f
            setPadding(0, dp(2), 0, dp(6))
        }
        // The action/status line stays visible when the scrollable settings are collapsed.
        root.addView(statusView, 1, rowParams())

        for (group in Group.values()) {
            content.addView(createGroupRow(group), rowParams(bottom = dp(3)))
        }

        moveWaitEdit = createWaitEdit(AppPrefs.moveWaitMs(this)) { AppPrefs.setMoveWaitMs(this, it) }
        content.addView(createWaitRow("Ожидание после хода", moveWaitEdit!!), rowParams(bottom = dp(3)))
        beforeRestartWaitEdit = createWaitEdit(AppPrefs.beforeRestartWaitMs(this)) {
            AppPrefs.setBeforeRestartWaitMs(this, it)
        }
        content.addView(
            createWaitRow("Ожидание перед перезапуском", beforeRestartWaitEdit!!),
            rowParams(bottom = dp(3)),
        )
        restartWaitEdit = createWaitEdit(AppPrefs.restartWaitMs(this)) {
            AppPrefs.setRestartWaitMs(this, it)
        }
        content.addView(
            createWaitRow("Ожидание после перезапуска", restartWaitEdit!!),
            rowParams(bottom = dp(3)),
        )
        rewardCloseWaitEdit = createWaitEdit(AppPrefs.rewardCloseWaitMs(this)) {
            AppPrefs.setRewardCloseWaitMs(this, it)
        }
        content.addView(
            createWaitRow("Ожидание для закрытия наград", rewardCloseWaitEdit!!),
            rowParams(bottom = dp(3)),
        )
        content.addView(createRoundsRow(), rowParams(bottom = dp(4)))

        // Keep the hint switch visible even when the panel is collapsed.
        root.addView(createHintRow(), rowParams(bottom = dp(3)))
        // Keep the reset mode and auto-play toggle visible even when the panel is collapsed.
        root.addView(createModeRow(), rowParams(bottom = dp(3)))
        accessibilityNotice = TextView(this).apply {
            text = "Автокликер недоступен: включите «BingoHelper» в спец. возможностях. " +
                "Подсказка работает без службы."
            setTextColor(0xFFFF8888.toInt())
            textSize = 10.5f
            setPadding(0, dp(3), 0, 0)
        }
        root.addView(accessibilityNotice, rowParams())

        return root
    }

    private fun setPanelCollapsed(collapsed: Boolean) {
        val panel = menuView ?: return
        val params = menuParams ?: return
        if (panelCollapsed == collapsed) return

        panelCollapsed = collapsed
        AppPrefs.setPanelCollapsed(this, collapsed)
        detailsView?.visibility = if (collapsed) View.GONE else View.VISIBLE
        if (collapsed && calibration?.hasBoard != true) {
            Toast.makeText(
                this,
                "Для подсказки сначала распознайте поле.",
                Toast.LENGTH_LONG,
            ).show()
        }
        collapseButton?.apply {
            text = if (collapsed) "▼" else "▲"
            contentDescription = if (collapsed) "Развернуть меню" else "Свернуть меню"
        }
        params.height = if (collapsed) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            expandedPanelHeight()
        }
        params.y = clampPanelY(params.y, params.height.takeIf { it > 0 } ?: panel.height.coerceAtLeast(dp(130)))
        try {
            windowManager.updateViewLayout(panel, params)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot resize floating menu", t)
        }
    }

    private fun createGroupRow(group: Group): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = group.label
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10.5f
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f)
                .apply { marginEnd = dp(3) },
        )

        val recognize = compactButton("Распознать") { startRecognition(group) }
        recognizeButtons[group] = recognize
        row.addView(
            recognize,
            LinearLayout.LayoutParams(0, dp(34), 0.40f).apply { marginEnd = dp(3) },
        )

        val toggle = compactButton("Показать") { toggleGroup(group) }
        toggleButtons[group] = toggle
        row.addView(toggle, LinearLayout.LayoutParams(0, dp(34), 0.26f))

        return row
    }

    private fun createWaitRow(label: String, edit: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@OverlayService).apply {
            text = label
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10.5f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(edit, LinearLayout.LayoutParams(dp(66), dp(34)))
        addView(TextView(this@OverlayService).apply {
            text = " мс"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10.5f
        })
    }

    private fun createWaitEdit(initial: Int, save: (Int) -> Unit): EditText = EditText(this).apply {
        setText(initial.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
        setTextColor(Color.WHITE)
        textSize = 11f
        gravity = Gravity.CENTER
        setBackgroundColor(0x33FFFFFF)
        imeOptions = EditorInfo.IME_ACTION_DONE
        setPadding(dp(2), 0, dp(2), 0)
        configureNumericEditor(this)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toIntOrNull()?.let(save)
            }
        })
    }

    private fun createHintRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = "Подсказка: лучший ход"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10.5f
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.40f),
        )

        hintButton = compactButton("Вкл") { toggleHint() }
        row.addView(hintButton, LinearLayout.LayoutParams(0, dp(34), 0.60f))

        return row
    }

    private fun createModeRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = "Перезапуск"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10.5f
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.32f),
        )

        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        modeX1 = compactRadio("×1")
        modeX10 = compactRadio("×10")
        group.addView(modeX1, LinearLayout.LayoutParams(0, dp(34), 1f))
        group.addView(modeX10, LinearLayout.LayoutParams(0, dp(34), 1f))

        val x10 = AppPrefs.resetMode(this) == AppPrefs.RESET_MODE_X10
        if (x10) modeX10?.isChecked = true else modeX1?.isChecked = true

        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == modeX10?.id) AppPrefs.RESET_MODE_X10 else AppPrefs.RESET_MODE_X1
            AppPrefs.setResetMode(this, mode)
        }

        row.addView(
            group,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.50f)
                .apply { marginEnd = dp(4) },
        )

        autoButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_auto_play)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = RippleDrawable(
                ColorStateList.valueOf(0x44FFFFFF),
                GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(0xFF293440.toInt())
                    setStroke(dp(1), 0x665F85A8)
                },
                null,
            )
            contentDescription = "Запустить автоклики"
            setOnClickListener {
                if (autoRunning) stopAuto("Остановлено пользователем") else startAuto()
            }
        }
        row.addView(autoButton, LinearLayout.LayoutParams(0, dp(42), 0.18f))
        return row
    }

    private fun createRoundsRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        roundsUnlimited = compactRadio("Всегда")
        roundsLimited = compactRadio("Раундов")
        group.addView(roundsUnlimited, LinearLayout.LayoutParams(0, dp(34), 1f))
        group.addView(roundsLimited, LinearLayout.LayoutParams(0, dp(34), 1f))
        roundsUnlimited?.isChecked = true

        row.addView(group, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.68f))

        roundsEdit = EditText(this).apply {
            setText("10")
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setBackgroundColor(0x33FFFFFF)
            isEnabled = false
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(2), 0, dp(2), 0)
        }
        configureNumericEditor(roundsEdit!!)

        row.addView(roundsEdit, LinearLayout.LayoutParams(0, dp(34), 0.32f))

        group.setOnCheckedChangeListener { _, checkedId ->
            val limited = checkedId == roundsLimited?.id
            roundsEdit?.isEnabled = limited
        }

        return row
    }

    private fun configureNumericEditor(edit: EditText) {
        edit.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !panelFocusable) {
                setPanelFocusable(true)
                view.postDelayed({
                    if (!view.isAttachedToWindow) return@postDelayed
                    edit.requestFocus()
                    edit.setSelection(edit.text?.length ?: 0)
                    showKeyboard(edit)
                }, 60)
            }
            false
        }
        edit.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) {
                mainHandler.postDelayed({
                    if (menuView?.findFocus() !is EditText) {
                        hideKeyboard(view)
                        setPanelFocusable(false)
                    }
                }, 80)
            }
        }
        edit.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                view.clearFocus()
                mainHandler.postDelayed({
                    if (menuView?.findFocus() !is EditText) {
                        hideKeyboard(view)
                        setPanelFocusable(false)
                    }
                }, 80)
                true
            } else {
                false
            }
        }
    }

    private fun compactButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 10f
            setPadding(dp(2), 0, dp(2), 0)
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }

    private fun compactRadio(text: String): RadioButton =
        RadioButton(this).apply {
            id = View.generateViewId()
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 10.5f
            buttonTintList = ColorStateList.valueOf(0xFF7FC4FF.toInt())
            setPadding(0, 0, dp(2), 0)
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun rowParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun attachDragHandle(handle: View) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f

        handle.setOnTouchListener { _, event ->
            val params = menuParams ?: return@setOnTouchListener false
            val view = menuView ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - startRawX)).toInt()
                        .coerceIn(0, (displayWidth - view.width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - startRawY)).toInt()
                        .let { clampPanelY(it, view.height) }
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Throwable) {
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val view = menuView ?: return
        val params = menuParams ?: return
        if (panelFocusable == focusable) return

        panelFocusable = focusable
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags = if (focusable) {
            params.flags and notFocusable.inv()
        } else {
            params.flags or notFocusable
        }
        if (focusable) {
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot change panel focus", t)
        }

        if (!focusable) view.findFocus()?.clearFocus()
    }

    private fun showKeyboard(view: View) {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun removeMenu() {
        val view = menuView ?: return
        menuView = null
        menuParams = null
        detailsView = null
        collapseButton = null
        statusView = null
        hintButton = null
        accessibilityNotice = null
        autoButton = null
        roundsUnlimited = null
        roundsLimited = null
        roundsEdit = null
        moveWaitEdit = null
        beforeRestartWaitEdit = null
        restartWaitEdit = null
        rewardCloseWaitEdit = null
        modeX1 = null
        modeX10 = null
        recognizeButtons.clear()
        toggleButtons.clear()
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun setStatus(text: String) {
        statusView?.text = text
        resizeExpandedPanel()
        Log.d(TAG, text)
    }

    private fun basePanelHeight(): Int =
        (displayHeight * PANEL_HEIGHT_SHARE).toInt().coerceAtLeast(dp(240))

    private fun expandedPanelHeight(): Int {
        val panel = menuView as? LinearLayout ?: return basePanelHeight()
        val scroll = detailsView as? ScrollView ?: return basePanelHeight()

        // Measure the full content, not the current (possibly clipped) ScrollView viewport.
        val width = dp(PANEL_WIDTH_DP) - panel.paddingLeft - panel.paddingRight
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val content = scroll.getChildAt(0)
        content.measure(widthSpec, heightSpec)
        var required = panel.paddingTop + panel.paddingBottom + content.measuredHeight
        for (index in 0 until panel.childCount) {
            val child = panel.getChildAt(index)
            if (child === scroll || child.visibility == View.GONE) continue
            child.measure(widthSpec, heightSpec)
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            required += child.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
        }
        val available = (displayHeight - minPanelY - dp(8)).coerceAtLeast(1)
        return (required + dp(4)).coerceAtMost(available)
    }

    private fun clampPanelY(y: Int, height: Int): Int =
        y.coerceIn(minPanelY, (displayHeight - height).coerceAtLeast(minPanelY))

    private fun resizeExpandedPanel() {
        if (panelCollapsed) return
        val panel = menuView ?: return
        val params = menuParams ?: return
        val height = expandedPanelHeight()
        if (params.height == height) return
        params.height = height
        params.y = clampPanelY(params.y, height)
        try {
            windowManager.updateViewLayout(panel, params)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot resize floating menu", t)
        }
    }

    private fun refreshPanel() {
        val cal = calibration
        val busy = recognizing || autoRunning

        for ((_, button) in recognizeButtons) {
            button.isEnabled = !busy
        }

        for ((group, button) in toggleButtons) {
            val available = when (group) {
                Group.BOARD -> cal?.hasBoard == true
                Group.RESET -> cal?.hasReset == true
            }
            button.isEnabled = available && !autoRunning
            button.text = if (shownGroups.contains(group)) "Скрыть" else "Показать"
        }

        hintButton?.isEnabled = cal?.hasBoard == true && !recognizing && !autoRunning
        hintButton?.text = if (hintEnabled) "Выкл" else "Вкл"
        autoButton?.apply {
            val available = autoRunning || (accessibilityConnected && cal?.ready == true && !recognizing)
            setImageResource(if (autoRunning) R.drawable.ic_auto_stop else R.drawable.ic_auto_play)
            contentDescription = if (autoRunning) "Остановить автоклики" else "Запустить автоклики"
            isEnabled = available
            alpha = if (available) 1f else 0.45f
        }
        accessibilityNotice?.visibility = if (accessibilityConnected) View.GONE else View.VISIBLE
        roundsEdit?.isEnabled = roundsLimited?.isChecked == true && !autoRunning
        moveWaitEdit?.isEnabled = !autoRunning
        beforeRestartWaitEdit?.isEnabled = !autoRunning
        restartWaitEdit?.isEnabled = !autoRunning
        rewardCloseWaitEdit?.isEnabled = !autoRunning
        resizeExpandedPanel()
    }

    private fun monitorAccessibility() {
        val connected = TapAccessibilityService.isConnected()
        if (accessibilityConnected != connected) {
            accessibilityConnected = connected
            if (!connected && autoRunning) {
                stopAuto("Автоклики остановлены: служба «BingoHelper» выключена.")
            }
            refreshPanel()
        }
        mainHandler.postDelayed({ monitorAccessibility() }, 1000)
    }

    // ---------------------------------------------------------------- grid overlay

    private fun toggleGroup(group: Group) {
        if (autoRunning) {
            setStatus("Остановите автоклики, чтобы показывать калибровку.")
            return
        }
        if (!shownGroups.remove(group)) {
            // A calibration preview is explicitly requested and has a different drawing
            // style; never leave the live best-cell hint silently hidden behind it.
            setHintEnabled(false)
            shownGroups.add(group)
        }
        rebuildSystemOverlay()
        refreshPanel()
    }

    private fun rebuildSystemOverlay() {
        if (autoRunning) {
            if (!hintEnabled) removeGrid() else showHintOverlay()
            return
        }
        // Manual "Показать" is a calibration preview, not a live hint. Keep its fill
        // visible until the user hides it, even if a hint mode was selected.
        if (shownGroups.isEmpty() && hintEnabled) {
            showHintOverlay()
            return
        }

        val cal = calibration
        if (cal == null) {
            removeGrid()
            return
        }

        val cells = if (shownGroups.contains(Group.BOARD)) cal.cells else emptyList()
        val extras = ArrayList<GridOverlayView.Extra>()

        addExtras(extras, cal.resetButtons, Group.RESET, 0x40FF5252, 0xFFFF5252.toInt())

        if (cells.isEmpty() && extras.isEmpty()) {
            removeGrid()
        } else {
            showGrid(cells, extras)
        }
    }

    private fun addExtras(
        into: MutableList<GridOverlayView.Extra>,
        rects: List<RectF>?,
        group: Group,
        fill: Int,
        stroke: Int,
    ) {
        if (!shownGroups.contains(group)) return
        rects?.forEach { into += GridOverlayView.Extra(RectF(it), fill, stroke) }
    }

    private fun showHintOverlay() = removePreview()

    private fun showGrid(
        cells: List<RectF>,
        extras: List<GridOverlayView.Extra>,
    ) {
        removeHintOutline()
        val view = gridView ?: createGrid() ?: return
        view.cells = cells
        view.extras = extras
        view.invalidate()
    }

    private fun updateTracking(next: Int) {
        val rect = if (hintEnabled) calibration?.cells?.getOrNull(next) else null
        if (rect == null) {
            removeHintOutline()
            return
        }
        if (outlinedCell == next && hintOutline.size == 4) return
        removeHintOutline()

        val density = resources.displayMetrics.density
        val stroke = (density * 4.8f).roundToInt().coerceAtLeast(1)
        val outset = density * 3f + stroke / 2f
        val left = (rect.left - outset).roundToInt()
        val top = (rect.top - outset).roundToInt()
        val right = (rect.right + outset).roundToInt()
        val bottom = (rect.bottom + outset).roundToInt()

        // Four narrow opaque windows instead of one opaque full-screen window: Android 12+
        // would otherwise block touches on the game even in transparent parts of the overlay.
        val edges = listOf(
            Rect(left, top, right, top + stroke),
            Rect(left, bottom - stroke, right, bottom),
            Rect(left, top + stroke, left + stroke, bottom - stroke),
            Rect(right - stroke, top + stroke, right, bottom - stroke),
        )
        try {
            for (edge in edges) {
                val x = edge.left.coerceIn(0, displayWidth)
                val y = edge.top.coerceIn(0, displayHeight)
                val width = edge.right.coerceIn(0, displayWidth) - x
                val height = edge.bottom.coerceIn(0, displayHeight) - y
                if (width <= 0 || height <= 0) continue
                val view = View(this).apply { setBackgroundColor(Color.RED) }
                val params = WindowManager.LayoutParams(
                    width, height, overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x
                    this.y = y
                    alpha = 1f
                    allowDisplayCutout()
                }
                windowManager.addView(view, params)
                hintOutline.add(view)
            }
            outlinedCell = next
        } catch (e: Throwable) {
            removeHintOutline()
            setStatus("Не удалось показать подсказку: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun removeHintOutline() {
        outlinedCell = -1
        for (view in hintOutline) {
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            }
        }
        hintOutline.clear()
    }

    private fun createGrid(): GridOverlayView? {
        val view = GridOverlayView(this).apply {
            isClickable = false
            isFocusable = false
        }

        val params = WindowManager.LayoutParams(
            displayWidth,
            displayHeight,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // On Android 12+, a fully opaque, non-touchable overlay can still make
            // accessibility gestures fail as untrusted touches.
            alpha = 0.65f
            allowDisplayCutout()
        }

        return try {
            windowManager.addView(view, params)
            gridView = view
            view
        } catch (e: Throwable) {
            setStatus("Не удалось показать сетку: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun removeGrid() {
        removeHintOutline()
        removePreview()
    }

    private fun removePreview() {
        val view = gridView ?: return
        gridView = null
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- recognition

    private fun startRecognition(group: Group) {
        if (recognizing) return
        if (autoRunning) {
            setStatus("Сначала остановите автоклики.")
            return
        }

        val capture = screenCapture
        if (capture == null || !capture.isReady) {
            setStatus("Захват экрана недоступен. Перезапустите меню.")
            return
        }

        recognizing = true
        refreshPanel()
        setStatus("Распознавание: ${group.label.lowercase()}…")

        val panel = menuView
        val grid = gridView
        val outline = hintOutline.toList()
        panel?.visibility = View.INVISIBLE
        grid?.visibility = View.INVISIBLE
        outline.forEach { it.visibility = View.INVISIBLE }

        mainHandler.postDelayed({
            capture.captureFresh { bitmap ->
                var outcome = RecognitionOutcome("Не удалось сделать снимок экрана.", null)
                if (bitmap != null) {
                    outcome = try {
                        analyzeFor(group, bitmap)
                    } catch (e: BingoRecognizer.RecognitionException) {
                        RecognitionOutcome("Не удалось найти поле: ${e.message}", null)
                    } catch (t: Throwable) {
                        RecognitionOutcome(
                            "Ошибка распознавания: ${t.message ?: t.javaClass.simpleName}",
                            null,
                        )
                    }
                }

                mainHandler.post {
                    panel?.visibility = View.VISIBLE
                    grid?.visibility = View.VISIBLE
                    outline.filter { it in hintOutline }.forEach { it.visibility = View.VISIBLE }
                    recognizing = false
                    outcome.calibration?.let { applyCalibration(it) }
                    setStatus(outcome.message)
                    refreshPanel()
                    rebuildSystemOverlay()
                }
            }
        }, 120)
    }

    private fun analyzeFor(group: Group, bitmap: android.graphics.Bitmap): RecognitionOutcome {
        val base = calibration ?: Calibration.empty(displayWidth, displayHeight)

        return when (group) {
            Group.BOARD -> {
                val recognized = BingoRecognizer.recognize(bitmap)
                val closed = GameVision.CellReader().read(bitmap, recognized.cells)
                val closedCount = closed.count { it }

                if (closedCount != 25) {
                    RecognitionOutcome(
                        "Поле не в начальном состоянии ($closedCount/25 закрыто). " +
                            "Откройте игру до первого хода.",
                        null,
                    )
                } else {
                    RecognitionOutcome(
                        "Поле распознано: 25 клеток.",
                        base.copy(
                            screenWidth = displayWidth,
                            screenHeight = displayHeight,
                            board = recognized.board,
                            cells = recognized.cells,
                        ),
                    )
                }
            }

            Group.RESET -> {
                val rects = GameVision.detectResetButtonRects(bitmap)
                if (rects == null) {
                    RecognitionOutcome(
                        "Не нашёл две кнопки перезапуска. Откройте экран после 8 ходов.",
                        null,
                    )
                } else {
                    RecognitionOutcome(
                        "Кнопки перезапуска сохранены.",
                        base.copy(
                            screenWidth = displayWidth,
                            screenHeight = displayHeight,
                            resetButtons = rects,
                        ),
                    )
                }
            }
        }
    }

    private fun applyCalibration(value: Calibration) {
        calibration = value
        vision = GameVision(value)
        AppPrefs.saveCalibration(this, value)
        Log.d(
            TAG,
            "Calibration saved: board=${value.hasBoard} reset=${value.hasReset}",
        )
        rebuildSystemOverlay()
        refreshPanel()
    }

    private fun resetCalibration() {
        if (autoRunning) {
            setStatus("Сначала остановите автоклики.")
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now > resetCalibrationArmedUntil) {
            resetCalibrationArmedUntil = now + 4000
            setStatus("Нажмите «сброс калибр.» ещё раз, чтобы удалить координаты.")
            return
        }

        resetCalibrationArmedUntil = 0
        wipeCalibration()
    }

    private fun wipeCalibration() {
        stopAuto()
        setHintEnabled(false)
        calibration = null
        vision = null
        shownGroups.clear()
        AppPrefs.clearCalibration(this)
        removeGrid()
        setStatus("Калибровка сброшена.")
        refreshPanel()
    }

    // ---------------------------------------------------------------- hint overlay

    private fun toggleHint() {
        if (autoRunning) return
        if (!hintEnabled && calibration?.hasBoard != true) {
            setStatus("Сначала распознайте поле.")
            return
        }
        if (!hintEnabled) {
            val bounds = panelBounds()
            if (bounds == null) {
                setStatus("Не удалось определить положение меню. Попробуйте включить подсказку ещё раз.")
                return
            }
            val board = calibration?.board ?: return
            if (RectF.intersects(bounds, board)) {
                setStatus("Подсказку включить нельзя: меню перекрывает поле. Сверните или переместите меню.")
                return
            }
        }

        setHintEnabled(!hintEnabled)
        if (hintEnabled) shownGroups.clear()
        refreshPanel()
        rebuildSystemOverlay()
        setStatus(if (hintEnabled) "Подсказка включена: красная рамка — лучший ход." else "Подсказка выключена.")
        if (hintEnabled) scheduleOverlay(HINT_POLL_INTERVAL_MS)
    }

    private fun setHintEnabled(enabled: Boolean) {
        if (hintEnabled == enabled) return
        hintEnabled = enabled
        overlaySeq++
        screenCapture?.setFrameInterval(
            if (enabled) HINT_POLL_INTERVAL_MS else DEFAULT_POLL_INTERVAL_MS,
        )
    }

    private fun stopHintIfMenuBlocksBoard(): Boolean {
        val bounds = panelBounds()
        val board = calibration?.board
        if (bounds != null && board != null && !RectF.intersects(bounds, board)) return false
        setHintEnabled(false)
        removeGrid()
        refreshPanel()
        setStatus("Подсказка выключена: меню перекрывает поле. Сверните или переместите меню.")
        return true
    }

    private fun scheduleOverlay(delayMs: Long) {
        val seq = overlaySeq
        mainHandler.postDelayed({
            if (!hintEnabled || seq != overlaySeq || autoRunning) return@postDelayed
            if (recognizing || capturing || shownGroups.isNotEmpty()) {
                scheduleOverlay(HINT_POLL_INTERVAL_MS)
            } else {
                if (stopHintIfMenuBlocksBoard()) return@postDelayed
                val nextCheckAt = SystemClock.uptimeMillis() + HINT_POLL_INTERVAL_MS
                captureFrame(detectResetButtons = false, fresh = false) { frame ->
                    if (!hintEnabled || seq != overlaySeq || autoRunning) {
                        return@captureFrame
                    }
                    if (stopHintIfMenuBlocksBoard()) return@captureFrame
                    if (frame != null && shownGroups.isEmpty()) displayFrame(frame)
                    scheduleOverlay(nextCheckAt - SystemClock.uptimeMillis())
                }
            }
        }, delayMs.coerceAtLeast(0))
    }

    private fun displayFrame(frame: GameVision.Frame) {
        if (calibration == null) return
        if (!hintEnabled) return
        if (frame.boardReadable) {
            showHintOverlay()
            updateTracking(nextBestCell(frame.closed))
        } else {
            removeGrid()
        }
    }

    private fun nextBestCell(closed: BooleanArray): Int {
        if (closed.size != Calibration.FIELD_CELL_COUNT) return -1

        val field = Field()
        for (index in closed.indices) {
            field.cells[index].used = !closed[index]
        }

        val remaining = (16 - field.usedCellCount).coerceIn(1, 16)
        var cell = BingoStrategy.nextMove(field, remaining)
        if (cell.used) {
            cell = field.cells.firstOrNull { !it.used } ?: return -1
        }
        return cell.id - 1
    }

    // ---------------------------------------------------------------- auto player

    private fun startAuto() {
        if (autoRunning || recognizing) return

        val cal = calibration
        if (cal == null || !cal.ready) {
            setStatus(
                "Не хватает калибровки: " +
                    (cal?.missingLabels()?.joinToString(", ") ?: "поле и кнопки перезапуска"),
            )
            return
        }

        if (!TapAccessibilityService.isConnected()) {
            setStatus("Автокликер недоступен: включите «BingoHelper» в спец. возможностях.")
            Toast.makeText(
                this,
                "Для автоматических нажатий включите службу BingoHelper",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val capture = screenCapture
        if (capture == null || !capture.isReady) {
            setStatus("Захват экрана недоступен. Перезапустите меню.")
            return
        }

        val limit = readRoundLimit()
        if (limit < 0) {
            setStatus("Укажите количество раундов от 1 до 999.")
            return
        }

        val moveWait = moveWaitEdit?.text?.toString()?.toIntOrNull()
        val beforeRestartWait = beforeRestartWaitEdit?.text?.toString()?.toIntOrNull()
        val restartWait = restartWaitEdit?.text?.toString()?.toIntOrNull()
        val rewardCloseWait = rewardCloseWaitEdit?.text?.toString()?.toIntOrNull()
        if (moveWait == null || beforeRestartWait == null || restartWait == null || rewardCloseWait == null) {
            setStatus("Укажите все ожидания в миллисекундах.")
            return
        }

        val menuBounds = panelBounds()
        if (menuBounds == null) {
            setStatus("Не удалось определить положение меню. Попробуйте запустить ещё раз.")
            return
        }
        if (panelBlocksAuto(menuBounds, cal)) {
            setStatus("Автоклики запустить нельзя: меню мешает нажатиям. Сверните или переместите меню.")
            return
        }

        roundLimit = limit
        moveWaitMs = moveWait
        beforeRestartWaitMs = beforeRestartWait
        restartWaitMs = restartWait
        rewardCloseWaitMs = rewardCloseWait
        roundProgress.reset()
        autoField = Field()
        pendingCell = -1
        lastBoardRecognizedAt = SystemClock.uptimeMillis()
        resetAttempts = 0
        frameFailures = 0
        hintBeforeAuto = hintEnabled
        setHintEnabled(false)
        autoRunning = true
        autoSeq++
        // Cache game updates, including those rendered during the configurable pauses.
        screenCapture?.setFrameInterval(AUTO_FRAME_INTERVAL_MS)

        removeGrid()
        refreshPanel()
        setStatus(
            if (limit > 0) {
                "Автоклики запущены: $limit раунд(ов)."
            } else {
                "Автоклики запущены."
            },
        )
        scheduleBoardTimeout(autoSeq, BOARD_INACTIVITY_TIMEOUT_MS)
        scheduleAuto(0)
    }

    private fun readRoundLimit(): Int {
        if (roundsUnlimited?.isChecked == true) return 0
        val value = roundsEdit?.text?.toString()?.toIntOrNull() ?: return -1
        if (value <= 0) return -1
        return value.coerceAtMost(999)
    }

    private fun stopAuto(message: String? = null) {
        val wasRunning = autoRunning
        autoRunning = false
        autoSeq++
        pendingCell = -1

        if (wasRunning) {
            setHintEnabled(hintBeforeAuto && !panelOverlapsBoard())
            screenCapture?.setFrameInterval(
                if (hintEnabled) HINT_POLL_INTERVAL_MS else DEFAULT_POLL_INTERVAL_MS,
            )
            hintBeforeAuto = false
            refreshPanel()
            if (message != null) {
                setStatus(message)
                if (panelCollapsed && message != "Остановлено пользователем" &&
                    !message.startsWith("Готово:")
                ) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            rebuildSystemOverlay()
            if (hintEnabled) scheduleOverlay(HINT_POLL_INTERVAL_MS)
        } else if (message != null) {
            setStatus(message)
        }
    }

    private fun scheduleAuto(delayMs: Long) {
        val seq = autoSeq
        mainHandler.postDelayed({
            if (autoRunning && seq == autoSeq) autoTick()
        }, delayMs.coerceAtLeast(0))
    }

    private fun scheduleBoardTimeout(seq: Int, delayMs: Long) {
        mainHandler.postDelayed({
            if (!autoRunning || seq != autoSeq) return@postDelayed
            val elapsed = SystemClock.uptimeMillis() - lastBoardRecognizedAt
            if (elapsed >= BOARD_INACTIVITY_TIMEOUT_MS) {
                stopAuto("Обычное поле не распознавалось 10 секунд. Остановлено.")
            } else {
                scheduleBoardTimeout(seq, BOARD_INACTIVITY_TIMEOUT_MS - elapsed)
            }
        }, delayMs)
    }

    private fun autoTick() {
        if (!autoRunning) return
        if (stopAutoIfMenuBlocks()) return
        if (recognizing || capturing) {
            scheduleAuto(DEFAULT_POLL_INTERVAL_MS)
            return
        }

        // Decide from the current screen before any dismissal taps can change it.
        readAutoFrame(autoSeq)
    }

    private fun readAutoFrame(seq: Int) {
        captureFrame(detectResetButtons = true, freshTimeoutMs = AUTO_FRAME_TIMEOUT_MS) { frame ->
            if (seq != autoSeq) return@captureFrame
            if (!autoRunning) return@captureFrame
            if (stopAutoIfMenuBlocks()) return@captureFrame

            if (frame == null) {
                frameFailures++
                if (frameFailures >= 12) {
                    stopAuto("Не удалось получить кадры игры. Перезапустите меню и захват экрана.")
                    return@captureFrame
                }
                if (frameFailures == 3) setStatus("Ожидаю изображение игры…")
                scheduleAuto(DEFAULT_POLL_INTERVAL_MS)
                return@captureFrame
            }
            frameFailures = 0
            if (frame.boardReadable) lastBoardRecognizedAt = SystemClock.uptimeMillis()

            handleFrame(frame)
        }
    }

    private fun handleFrame(frame: GameVision.Frame) {
        // The restart screen can retain a few wooden-looking cells, so check its buttons
        // before deciding that the board is playable.
        if (frame.resetVisible) {
            resetAttempts++
            if (resetAttempts > 3) {
                stopAuto("Кнопки перезапуска не срабатывают. Остановлено.")
                return
            }
            restartRound()
            return
        }

        // No readable board or restart buttons means a popup or animation. Dismiss it
        // without a move, then wait before recognizing again.
        if (!frame.boardReadable) {
            setStatus("Поле не видно. Закрываю меню вниз-вправо…")
            dismissThenContinue(moveWaitMs.toLong())
            return
        }

        if (roundProgress.awaitingNewBoard) {
            if (frame.closedCount >= 24) {
                roundProgress.onNewBoard()
                resetAttempts = 0
                autoField = Field()
                pendingCell = -1
                setStatus("Перезапуск выполнен, делаю первый ход раунда ${roundProgress.roundsDone + 1}.")
            } else {
                // A game animation or another popup can briefly show the old board.
                dismissThenContinue(moveWaitMs.toLong())
                return
            }
        }

        resetAttempts = 0

        // Normal board.
        val cal = calibration ?: run {
            stopAuto("Калибровка потеряна.")
            return
        }
        if (frame.closed.size != cal.cells.size) {
            stopAuto("Поле не совпадает с калибровкой.")
            return
        }

        syncField(frame.closed)

        // Use the count from before the tap: the next frame may already show rewards.
        val waitBeforeRestart = frame.closedCount == 14 || frame.closedCount == 15

        if (pendingCell >= 0 && !frame.closed[pendingCell]) {
            pendingCell = -1
        }

        if (pendingCell >= 0) {
            tapCell(pendingCell) { afterAutoAction(restarted = false, waitBeforeRestart = waitBeforeRestart) }
            return
        }

        val cell = chooseCell()
        if (cell < 0) {
            setStatus("Нет доступных клеток. Жду перезапуска…")
            scheduleAuto(DEFAULT_POLL_INTERVAL_MS)
            return
        }

        pendingCell = cell
        tapCell(cell) { afterAutoAction(restarted = false, waitBeforeRestart = waitBeforeRestart) }
    }

    private fun afterAutoAction(restarted: Boolean, waitBeforeRestart: Boolean = false) {
        val seq = autoSeq
        mainHandler.postDelayed({
            if (autoRunning && seq == autoSeq) {
                val extraWait = when {
                    restarted -> restartWaitMs.toLong()
                    waitBeforeRestart -> beforeRestartWaitMs.toLong()
                    else -> 0L
                }
                dismissThenContinue(extraWait)
            }
        }, moveWaitMs.toLong())
    }

    private fun dismissThenContinue(waitAfterDismissMs: Long) {
        tapDismissTwice {
            // The second tap ends the gesture; let the reward close before any next step.
            scheduleAuto(rewardCloseWaitMs.toLong() + waitAfterDismissMs)
        }
    }

    private fun syncField(closed: BooleanArray) {
        for (index in autoField.cells.indices) {
            if (index < closed.size && !closed[index]) {
                autoField.cells[index].used = true
            }
        }
    }

    private fun chooseCell(): Int {
        val remaining = (16 - autoField.usedCellCount).coerceIn(1, 16)
        var cell = BingoStrategy.nextMove(autoField, remaining)
        if (cell.used) {
            cell = autoField.cells.firstOrNull { !it.used } ?: return -1
        }
        if (cell.used) return -1
        return cell.id - 1
    }

    private fun tapCell(index: Int, onDone: () -> Unit) {
        if (!autoRunning) return
        val cal = calibration ?: return
        val rect = cal.cells.getOrNull(index)
        if (rect == null) {
            stopAuto("Нет клетки ${index + 1}.")
            return
        }

        updateTracking(index)
        setStatus("Кликаю клетку: строка ${index / FIELD_SIZE + 1}, столбец ${index % FIELD_SIZE + 1}…")

        tap(rect.centerX(), rect.centerY()) { ok ->
            if (!autoRunning) return@tap
            if (!ok) {
                stopAuto("Не удалось выполнить нажатие")
                return@tap
            }
            if (roundProgress.onCellTapped() && roundLimit > 0 &&
                roundProgress.roundsDone >= roundLimit
            ) {
                stopAuto("Готово: ${roundProgress.roundsDone} раунд(ов). Остановлено.")
                return@tap
            }
            onDone()
        }
    }

    private fun tapDismissTwice(onDone: () -> Unit) {
        val x = displayWidth * DISMISS_X
        val y = displayHeight * DISMISS_Y
        fun dismissOnce(then: () -> Unit) {
            tap(x, y, quick = true) { ok ->
                if (!autoRunning) return@tap
                if (!ok) {
                    stopAuto("Не удалось выполнить нажатие")
                    return@tap
                }
                then()
            }
        }
        dismissOnce {
            val seq = autoSeq
            mainHandler.postDelayed({
                if (autoRunning && seq == autoSeq) dismissOnce(onDone)
            }, DISMISS_TAP_GAP_MS)
        }
    }

    private fun restartRound() {
        val cal = calibration ?: return

        if (roundLimit > 0 && roundProgress.roundsDone >= roundLimit) {
            stopAuto("Готово: ${roundProgress.roundsDone} раунд(ов). Остановлено.")
            return
        }

        val buttons = cal.resetButtons.sortedBy { it.left }
        val mode = AppPrefs.resetMode(this)
        val rect = if (mode == AppPrefs.RESET_MODE_X10) buttons.last() else buttons.first()

        setStatus("Нажимаю перезапуск ×$mode (${roundProgress.roundsDone + 1}${if (roundLimit > 0) "/$roundLimit" else ""})…")

        tap(rect.centerX(), rect.centerY()) { ok ->
            if (!autoRunning) return@tap
            if (!ok) {
                stopAuto("Не удалось нажать кнопку перезапуска")
                return@tap
            }
            roundProgress.onRestartTapped()
            afterAutoAction(restarted = true)
        }
    }

    // ---------------------------------------------------------------- capture and taps

    private fun captureFrame(
        detectResetButtons: Boolean,
        fresh: Boolean = true,
        freshTimeoutMs: Long? = null,
        onFrame: (GameVision.Frame?) -> Unit,
    ) {
        if (capturing) {
            onFrame(null)
            return
        }

        val capture = screenCapture
        val currentVision = vision
        if (capture == null || currentVision == null) {
            onFrame(null)
            return
        }

        capturing = true

        val panel = menuView
        val hidePanel = panelOverlapsBoard()
        if (hidePanel) panel?.visibility = View.INVISIBLE
        val grid = gridView
        val hideGrid = grid?.extras?.isNotEmpty() == true
        if (hideGrid) grid.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            val onBitmap: (Bitmap?) -> Unit = { bitmap ->
                var frame: GameVision.Frame? = null

                if (bitmap != null) {
                    try {
                        frame = currentVision.analyze(bitmap, detectResetButtons)
                        if (frame.screenLuminance < GameVision.MIN_VALID_LUMINANCE) {
                            frame = null
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Screen analysis failed", t)
                    }
                }

                mainHandler.post {
                    capturing = false
                    if (hidePanel) panel?.visibility = View.VISIBLE
                    if (hideGrid) grid?.visibility = View.VISIBLE
                    onFrame(frame)
                }
            }
            if (freshTimeoutMs != null) capture.captureFresh(freshTimeoutMs, onBitmap)
            else if (fresh) capture.captureFresh(onResult = onBitmap)
            else capture.capture(onBitmap)
        }, if (hidePanel) 70L else 0L)
    }

    private fun panelBounds(): RectF? {
        val view = menuView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        // WRAP_CONTENT uses the actual laid-out height, not the last expanded height.
        val height = if (panelCollapsed) view.height else maxOf(view.height, menuParams?.height ?: 0)
        return RectF(
            position[0].toFloat(), position[1].toFloat(),
            (position[0] + view.width).toFloat(), (position[1] + height).toFloat(),
        )
    }

    private fun panelBlocksAuto(bounds: RectF, cal: Calibration): Boolean {
        val dismissX = displayWidth * DISMISS_X
        val dismissY = displayHeight * DISMISS_Y
        val margin = dp(8).toFloat()
        val dismissArea = RectF(
            dismissX - margin, dismissY - margin,
            dismissX + margin, dismissY + margin,
        )
        return (cal.board?.let { RectF.intersects(bounds, it) } == true) ||
            cal.resetButtons.any { RectF.intersects(bounds, it) } ||
            RectF.intersects(bounds, dismissArea)
    }

    private fun stopAutoIfMenuBlocks(): Boolean {
        val cal = calibration ?: run {
            stopAuto("Калибровка потеряна.")
            return true
        }
        val bounds = panelBounds()
        if (bounds == null || panelBlocksAuto(bounds, cal)) {
            stopAuto("Автоклики остановлены: меню мешает нажатиям. Сверните или переместите меню.")
            return true
        }
        return false
    }

    private fun panelOverlapsBoard(): Boolean {
        val bounds = panelBounds() ?: return false
        val board = calibration?.board ?: return false
        return RectF.intersects(bounds, board)
    }

    private fun tap(x: Float, y: Float, quick: Boolean = false, onResult: (Boolean) -> Unit) {
        if (stopAutoIfMenuBlocks()) return
        val seq = autoSeq
        val grid = gridView
        val restoreGrid = grid?.visibility == View.VISIBLE
        val outline = hintOutline.toList()
        if (restoreGrid) grid?.visibility = View.INVISIBLE
        outline.forEach { it.visibility = View.INVISIBLE }

        // Let SurfaceFlinger remove the overlay before injecting the gesture. FLAG_NOT_TOUCHABLE
        // alone is insufficient on Android 12 when a full-screen overlay occludes a target.
        mainHandler.postDelayed({
            fun finish(ok: Boolean) {
                if (restoreGrid && grid === gridView) grid?.visibility = View.VISIBLE
                outline.filter { it in hintOutline }.forEach { it.visibility = View.VISIBLE }
                Log.d(TAG, "Gesture (${x.roundToInt()}, ${y.roundToInt()}): ${if (ok) "done" else "failed"}")
                if (seq == autoSeq) onResult(ok)
            }

            if (!autoRunning || seq != autoSeq) {
                finish(false)
                return@postDelayed
            }
            // Recheck immediately before every gesture, including the second dismissal tap.
            if (stopAutoIfMenuBlocks()) return@postDelayed
            val service = TapAccessibilityService.instance
            if (service == null) {
                finish(false)
            } else {
                service.tap(x, y, if (quick) 20L else 55L) { ok -> finish(ok) }
            }
        }, if (restoreGrid || outline.isNotEmpty()) 90L else 0L)
    }

    // ---------------------------------------------------------------- helpers

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun WindowManager.LayoutParams.allowDisplayCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

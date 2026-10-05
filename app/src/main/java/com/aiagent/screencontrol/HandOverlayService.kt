package com.aiagent.screencontrol

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.hypot

class HandOverlayService : Service() {
    companion object {
        @Volatile var instance: HandOverlayService? = null
            private set
        private const val CHANNEL_ID = "ai_agent_overlay"
        private const val NOTIF_ID = 4242
        fun start(ctx: Context) {
            val i = Intent(ctx, HandOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, HandOverlayService::class.java)) }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: AgentOverlayView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIF_ID, buildNotification("ИИ-агент активен"))
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = AgentOverlayView(this)
        val type: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try { windowManager.addView(view, params); overlayView = view }
        catch (e: Exception) { stopSelf(); return }
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        view.handVisible = prefs.getBoolean("show_hand", true)
        view.avatarVisible = prefs.getBoolean("show_avatar", true)
    }

    override fun onDestroy() {
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null; instance = null; super.onDestroy()
    }

    fun animateTo(x: Float, y: Float, rect: Rect?) { mainHandler.post { overlayView?.setTarget(x, y, rect) } }
    fun clearHighlight() { mainHandler.post { overlayView?.clearHighlight() } }
    fun setHandVisible(v: Boolean) { mainHandler.post { overlayView?.handVisible = v } }
    fun setAvatarVisible(v: Boolean) { mainHandler.post { overlayView?.avatarVisible = v } }
    fun showStatus(s: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(s))
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AI Agent Overlay", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Screen Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

class AgentOverlayView(context: Context) : View(context) {
    var handVisible: Boolean = true
        set(v) { field = v; postInvalidate() }
    var avatarVisible: Boolean = true
        set(v) { field = v; postInvalidate() }

    private var cursorX = 0f
    private var cursorY = 0f
    private var highlight: RectF? = null
    private var pulse = 0f

    private val handFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF"); style = Paint.Style.FILL; alpha = 210
    }
    private val handStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081"); style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val avatarBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E88E5"); style = Paint.Style.FILL
    }
    private val avatarRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF"); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val eyePupil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D47A1"); style = Paint.Style.FILL
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
        addUpdateListener { pulse = it.animatedValue as Float; postInvalidate() }
    }
    init { pulseAnimator.start() }
    override fun onDetachedFromWindow() { pulseAnimator.cancel(); super.onDetachedFromWindow() }

    fun setTarget(x: Float, y: Float, rect: Rect?) {
        if (handVisible) {
            ObjectAnimator.ofFloat(this, "cursorX", cursorX, x).setDuration(700L).start()
            ObjectAnimator.ofFloat(this, "cursorY", cursorY, y).setDuration(700L).start()
        } else { cursorX = x; cursorY = y; postInvalidate() }
        highlight = rect?.let { RectF(it) }
        postInvalidate()
    }
    fun clearHighlight() { highlight = null; postInvalidate() }
    @Suppress("unused") fun setCursorX(v: Float) { cursorX = v; postInvalidate() }
    @Suppress("unused") fun setCursorY(v: Float) { cursorY = v; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        highlight?.let { r ->
            val inflate = 8f + 14f * pulse
            highlightPaint.alpha = (255 * (1f - pulse)).toInt().coerceIn(50, 255)
            canvas.drawRoundRect(r.left - inflate, r.top - inflate,
                r.right + inflate, r.bottom + inflate, 18f, 18f, highlightPaint)
        }
        if (avatarVisible) drawAvatar(canvas)
        if (handVisible) drawHand(canvas, cursorX, cursorY)
    }
    private fun drawHand(canvas: Canvas, x: Float, y: Float) {
        if (x <= 0f && y <= 0f) return
        canvas.drawCircle(x, y, 30f, handFill)
        canvas.drawCircle(x, y, 30f, handStroke)
        canvas.drawCircle(x, y, 7f, dotPaint)
    }
    private fun drawAvatar(canvas: Canvas) {
        val cx = width - 150f; val cy = 200f; val radius = 72f
        canvas.drawCircle(cx, cy, radius, avatarBody)
        canvas.drawCircle(cx, cy, radius, avatarRim)
        val eox = 26f; val eoy = -12f; val er = 15f; val pr = 7f
        val dx = cursorX - cx; val dy = cursorY - cy
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val px = (dx / len) * 6f; val py = (dy / len) * 6f
        canvas.drawCircle(cx - eox, cy + eoy, er, eyeWhite)
        canvas.drawCircle(cx + eox, cy + eoy, er, eyeWhite)
        canvas.drawCircle(cx - eox + px, cy + eoy + py, pr, eyePupil)
        canvas.drawCircle(cx + eox + px, cy + eoy + py, pr, eyePupil)
        val m = RectF(cx - 28f, cy + 12f, cx + 28f, cy + 44f)
        canvas.drawArc(m, 15f, 150f, false, mouthPaint)
    }
}

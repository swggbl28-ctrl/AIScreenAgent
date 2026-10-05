package com.aiagent.screencontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var agentJob: Job? = null
    @Volatile private var running = false

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null; stopAgent(); return super.onUnbind(intent)
    }
    override fun onDestroy() { instance = null; running = false; scope.cancel(); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun startAgent(task: String, apiKey: String) {
        if (running) return
        running = true
        agentJob = scope.launch { runAgentLoop(task, apiKey) }
    }

    fun stopAgent() { running = false; agentJob?.cancel(); agentJob = null }

    private suspend fun runAgentLoop(task: String, apiKey: String) {
        val api = DeepSeekApiClient(apiKey)
        val history = StringBuilder()
        var steps = 0
        while (running && isActive && steps < 80) {
            steps++
            delay(900)
            val screenJson = scanScreen()
            val action = api.decide(task, screenJson, history.toString())
            if (action == null) {
                delay(1500)
                continue
            }
            when (action.optString("action", "")) {
                "finish" -> {
                    HandOverlayService.instance?.clearHighlight()
                    running = false
                }
                "click" -> {
                    val x = action.optInt("x", -1)
                    val y = action.optInt("y", -1)
                    if (x >= 0 && y >= 0) {
                        val rect = readBounds(action.optJSONArray("target_bounds"))
                        HandOverlayService.instance?.animateTo(x.toFloat(), y.toFloat(), rect)
                        delay(850)
                        dispatchClick(x.toFloat(), y.toFloat())
                        history.append("click(").append(x).append(",").append(y).append("); ")
                        delay(400)
                        HandOverlayService.instance?.clearHighlight()
                    }
                }
                "input" -> {
                    val text = action.optString("text", "")
                    if (text.isNotEmpty()) {
                        val rect = readBounds(action.optJSONArray("target_bounds"))
                        var cx = action.optInt("x", -1)
                        var cy = action.optInt("y", -1)
                        if (rect != null) { cx = rect.centerX(); cy = rect.centerY() }
                        if (cx >= 0 && cy >= 0) {
                            HandOverlayService.instance?.animateTo(cx.toFloat(), cy.toFloat(), rect)
                            delay(700)
                            dispatchClick(cx.toFloat(), cy.toFloat())
                            delay(550)
                        }
                        performTextInput(text)
                        history.append("input('").append(text).append("'); ")
                        delay(400)
                        HandOverlayService.instance?.clearHighlight()
                    }
                }
                "swipe" -> {
                    val x1 = action.optInt("x", -1)
                    val y1 = action.optInt("y", -1)
                    val x2 = action.optInt("x2", -1)
                    val y2 = action.optInt("y2", -1)
                    if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) {
                        HandOverlayService.instance?.animateTo(x1.toFloat(), y1.toFloat(), null)
                        delay(650)
                        dispatchSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                        history.append("swipe(").append(x1).append(",").append(y1)
                            .append("->").append(x2).append(",").append(y2).append("); ")
                        delay(400)
                        HandOverlayService.instance?.clearHighlight()
                    }
                }
            }
            delay(800)
        }
        withContext(Dispatchers.Main) {
            HandOverlayService.instance?.showStatus("Агент завершил работу")
        }
    }

    private fun readBounds(arr: JSONArray?): Rect? {
        if (arr == null || arr.length() < 4) return null
        return try { Rect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3)) }
        catch (e: Exception) { null }
    }

    private fun scanScreen(): String {
        val root = rootInActiveWindow ?: return "{}"
        val elements = JSONArray()
        traverse(root, elements, 0)
        val m = resources.displayMetrics
        return JSONObject().apply {
            put("package", root.packageName?.toString() ?: "")
            put("screen", JSONObject().put("width", m.widthPixels).put("height", m.heightPixels))
            put("elements", elements)
        }.toString()
    }

    private fun traverse(node: AccessibilityNodeInfo?, out: JSONArray, depth: Int) {
        if (node == null || depth > 30) return
        val r = Rect(); node.getBoundsInScreen(r)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
            out.put(JSONObject().apply {
                put("text", text); put("desc", desc)
                put("id", node.viewIdResourceName.orEmpty())
                put("class", node.className?.toString().orEmpty())
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("enabled", node.isEnabled)
                put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
            })
        }
        for (i in 0 until node.childCount) traverse(node.getChild(i), out, depth + 1)
    }

    private fun dispatchClick(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 90L)).build(), null, null)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 420L)).build(), null, null)
    }

    private fun performTextInput(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused?.takeIf { it.isEditable } ?: findFirstEditable(root) ?: return
        if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val f = findFirstEditable(node.getChild(i), depth + 1)
            if (f != null) return f
        }
        return null
    }
}

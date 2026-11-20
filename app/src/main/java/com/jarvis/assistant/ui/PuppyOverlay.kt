package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import com.jarvis.assistant.R
import com.jarvis.assistant.widget.JarvisWidgetActionReceiver
import kotlin.math.abs
import kotlin.random.Random

/** Small, playful, draggable puppy companion. It never owns the microphone. */
object PuppyOverlay : HudController.Listener {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var image: ImageView? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var movement: ValueAnimator? = null
    private var context: Context? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private val wander = object : Runnable {
        override fun run() {
            val v = image ?: return
            val p = params ?: return
            val wm = manager ?: return
            val c = context ?: return
            // Use application resource metrics; Context.display can be unavailable for
            // an Application context on some Android builds.
            val metrics = c.resources.displayMetrics
            val maxX = (metrics.widthPixels - v.width).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - v.height - 48).coerceAtLeast(48)
            val targetX = Random.nextInt(0, maxX + 1)
            val targetY = Random.nextInt(48, maxY + 1)
            val originX = p.x
            val originY = p.y
            movement?.cancel()
            // 40% of the original movement speed (60% slower), as requested.
            // Original travel was 2.6–4.3 seconds; divide by 0.4.
            val travelDuration = Random.nextLong(6_500L, 10_750L)
            movement = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = travelDuration
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    p.x = (originX + (targetX - originX) * f).toInt().coerceIn(0, maxX)
                    p.y = (originY + (targetY - originY) * f).toInt().coerceAtLeast(0)
                    try { wm.updateViewLayout(v, p) } catch (_: Exception) { cancel() }
                }
                start()
            }
            // Let the full slower movement finish, then pause briefly before wandering again.
            main.postDelayed(this, travelDuration + Random.nextLong(2500L, 4000L))
        }
    }

    fun show(appContext: Context) {
        main.post {
            if (image != null) return@post
            val app = appContext.applicationContext
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return@post
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val size = (28 * app.resources.displayMetrics.density).toInt()
                val type = if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                val lp = WindowManager.LayoutParams(
                    size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 180 }
                val puppy = ImageView(app).apply {
                    setImageResource(R.drawable.jarvis_puppy)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    // Keep the smaller puppy clearly visible without overpowering the screen.
                    alpha = 0.6f
                    contentDescription = "Jarvis puppy. Tap to talk, drag to move."
                    setOnTouchListener { view, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                movement?.cancel()
                                downX = event.rawX; downY = event.rawY
                                startX = lp.x; startY = lp.y; dragged = false
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = (event.rawX - downX).toInt()
                                val dy = (event.rawY - downY).toInt()
                                if (abs(dx) > 5 || abs(dy) > 5) dragged = true
                                lp.x = (startX + dx).coerceAtLeast(0)
                                lp.y = (startY + dy).coerceAtLeast(0)
                                try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!dragged) {
                                    val intent = Intent(app, JarvisWidgetActionReceiver::class.java)
                                        .setAction(JarvisWidgetActionReceiver.ACTION_START_LISTENING)
                                    app.sendBroadcast(intent)
                                } else {
                                    main.removeCallbacks(wander)
                                    main.postDelayed(wander, 1800L)
                                }
                                true
                            }
                            else -> true
                        }
                    }
                }
                wm.addView(puppy, lp)
                context = app; manager = wm; params = lp; image = puppy
                HudController.addListener(this)
                main.postDelayed(wander, 3500L)
            } catch (_: Exception) {
                image = null; manager = null; params = null
            }
        }
    }

    fun hide() {
        main.post {
            main.removeCallbacks(wander)
            movement?.cancel(); movement = null
            HudController.removeListener(this)
            val v = image
            val wm = manager
            try { if (v != null && wm != null) wm.removeView(v) } catch (_: Exception) {}
            image = null; manager = null; params = null; context = null
        }
    }

    override fun onHudState(state: HudState) {
        main.post {
            val v = image ?: return@post
            when (state) {
                HudState.LISTENING -> { v.alpha = 0.6f; v.scaleX = 1.12f; v.scaleY = 1.12f }
                HudState.THINKING, HudState.EXECUTING -> { v.alpha = 0.54f; v.scaleX = 0.96f; v.scaleY = 0.96f }
                HudState.SPEAKING, HudState.DONE -> { v.alpha = 0.6f; v.scaleX = 1.08f; v.scaleY = 1.08f }
                HudState.ERROR -> { v.alpha = 0.39f; v.scaleX = 1f; v.scaleY = 1f }
                else -> { v.alpha = 0.6f; v.scaleX = 1f; v.scaleY = 1f }
            }
        }
    }
}

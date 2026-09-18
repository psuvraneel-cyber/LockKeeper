package com.lockkeeper.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class CooldownOverlayView(
    context: Context,
    private val targetPackage: String,
    private val initialSeconds: Long,
    private val isLockout: Boolean,
    private val onExitClicked: () -> Unit
) : LinearLayout(context) {

    private val countdownTextView: TextView
    private var timer: CountDownTimer? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#090D16"))
        isFocusableInTouchMode = true
        requestFocus()

        val titleView = TextView(context).apply {
            text = if (isLockout) "PIN Lockout Active" else "Strict Cooldown Active"
            textSize = 24f
            setTextColor(if (isLockout) Color.parseColor("#F87171") else Color.parseColor("#F59E0B"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        addView(titleView)

        val explanationView = TextView(context).apply {
            text = if (isLockout) {
                "Too many incorrect PIN attempts.\nAuthentication is temporarily disabled."
            } else {
                "This app is in a strict cooldown period.\nIt cannot be opened even with the correct PIN."
            }
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
                bottomMargin = 36
            }
        }
        addView(explanationView)

        countdownTextView = TextView(context).apply {
            text = formatTime(initialSeconds)
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 48
            }
        }
        addView(countdownTextView)

        val exitButton = Button(context).apply {
            text = "Return to Home"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD

            val shape = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#1E293B"))
                setStroke(2, Color.parseColor("#475569"))
            }
            background = shape
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                onExitClicked()
            }
        }
        addView(exitButton)

        startTimer(initialSeconds)
    }

    private fun startTimer(seconds: Long) {
        timer?.cancel()
        timer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdownTextView.text = formatTime(millisUntilFinished / 1000L)
            }

            override fun onFinish() {
                countdownTextView.text = "00:00"
                onExitClicked()
            }
        }.start()
    }

    private fun formatTime(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    override fun onDetachedFromWindow() {
        timer?.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onExitClicked()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

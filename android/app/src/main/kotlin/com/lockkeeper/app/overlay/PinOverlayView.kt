package com.lockkeeper.app.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.CycleInterpolator
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class PinOverlayView(
    context: Context,
    private val targetPackage: String,
    private val expectedPinLength: Int = 4,
    private val onPinEntered: (String) -> Unit,
    private val onBackInterception: () -> Unit
) : LinearLayout(context) {

    private val pinBuilder = StringBuilder()
    private val dotsLayout = LinearLayout(context)
    private val statusTextView = TextView(context)
    private var failedCount = 0

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#0F172A")) // Deep dark background
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()

        setupHeader()
        setupDots()
        setupStatus()
        setupKeypad()
    }

    private fun setupHeader() {
        val pm = context.packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(targetPackage, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            targetPackage
        }

        val appIcon = try {
            pm.getApplicationIcon(targetPackage)
        } catch (e: Exception) {
            null
        }

        if (appIcon != null) {
            val iconView = ImageView(context).apply {
                setImageDrawable(appIcon)
                layoutParams = LayoutParams(140, 140).apply {
                    bottomMargin = 24
                }
            }
            addView(iconView)
        }

        val titleView = TextView(context).apply {
            text = appName
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        addView(titleView)

        val subtitleView = TextView(context).apply {
            text = "Locked by LockKeeper"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 32
                topMargin = 6
            }
        }
        addView(subtitleView)
    }

    private fun setupDots() {
        dotsLayout.orientation = HORIZONTAL
        dotsLayout.gravity = Gravity.CENTER
        dotsLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 24
        }
        updateDots()
        addView(dotsLayout)
    }

    private fun updateDots() {
        dotsLayout.removeAllViews()
        val count = pinBuilder.length
        val maxDisplay = maxOf(expectedPinLength, count)
        for (i in 0 until maxDisplay) {
            val dot = View(context).apply {
                val size = 28
                val shape = GradientDrawable().apply {
                    this.shape = GradientDrawable.OVAL
                    if (i < count) {
                        setColor(Color.parseColor("#38BDF8")) // Bright sky blue
                    } else {
                        setColor(Color.parseColor("#334155")) // Muted dark blue
                    }
                }
                background = shape
                layoutParams = LayoutParams(size, size).apply {
                    setMargins(14, 0, 14, 0)
                }
            }
            dotsLayout.addView(dot)
        }
    }

    private fun setupStatus() {
        statusTextView.apply {
            text = "Enter your PIN"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 32
            }
        }
        addView(statusTextView)
    }

    private fun setupKeypad() {
        val grid = GridLayout(context).apply {
            columnCount = 3
            rowCount = 4
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✓", "0", "⌫")
        for (key in keys) {
            val btn = Button(context).apply {
                text = key
                textSize = 24f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD

                val btnShape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    val bgColor = when (key) {
                        "✓" -> Color.parseColor("#0284C7")
                        "⌫" -> Color.parseColor("#334155")
                        else -> Color.parseColor("#1E293B")
                    }
                    setColor(bgColor)
                }
                background = btnShape
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 170
                    height = 170
                    setMargins(20, 16, 20, 16)
                }

                setOnClickListener {
                    when (key) {
                        "⌫" -> {
                            if (pinBuilder.isNotEmpty()) {
                                pinBuilder.deleteCharAt(pinBuilder.length - 1)
                                updateDots()
                            }
                        }
                        "✓" -> {
                            if (pinBuilder.length >= 4) {
                                onPinEntered(pinBuilder.toString())
                            } else {
                                statusTextView.text = "PIN must be at least 4 digits"
                                statusTextView.setTextColor(Color.parseColor("#F87171"))
                            }
                        }
                        else -> {
                            if (pinBuilder.length < 8) {
                                pinBuilder.append(key)
                                updateDots()
                                if (pinBuilder.length == expectedPinLength) {
                                    onPinEntered(pinBuilder.toString())
                                }
                            }
                        }
                    }
                }
            }
            grid.addView(btn)
        }
        addView(grid)
    }

    fun onWrongPin(serverFailedCount: Int = -1) {
        failedCount = if (serverFailedCount > 0) serverFailedCount else (failedCount + 1)
        statusTextView.text = "Incorrect PIN ($failedCount/5)"
        statusTextView.setTextColor(Color.parseColor("#F87171")) // Red

        val shake = TranslateAnimation(0f, 25f, 0f, 0f).apply {
            duration = 400
            interpolator = CycleInterpolator(4f)
        }
        dotsLayout.startAnimation(shake)

        pinBuilder.clear()
        updateDots()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBackInterception()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

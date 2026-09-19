package com.lockkeeper.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class AdminOverlayView(
    context: Context,
    private val onPasswordSubmitted: (String) -> Unit,
    private val onCancelClicked: () -> Unit
) : LinearLayout(context) {

    private val inputEditText: EditText
    private val errorTextView: TextView
    private val submitButton: Button
    private val cancelButton: Button

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#180D0D")) // Dark warning reddish tone
        setPadding(64, 48, 64, 48)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "LockKeeper Admin Authentication Screen"
        requestFocus()

        val warningBadge = TextView(context).apply {
            text = "🛡️ LOCKKEEPER ANTI-TAMPER"
            textSize = 12f
            setTextColor(Color.parseColor("#F87171"))
            typeface = Typeface.DEFAULT_BOLD
            val badgeBg = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#3F1212"))
                setStroke(2, Color.parseColor("#7F1D1D"))
            }
            background = badgeBg
            setPadding(24, 8, 24, 8)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
        }
        addView(warningBadge)

        val titleView = TextView(context).apply {
            text = "Admin Password Required"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        addView(titleView)

        val subtitleView = TextView(context).apply {
            text = "LockKeeper self-protection detected an attempt to modify or remove protections.\nEnter your Admin Password to continue."
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
                bottomMargin = 32
            }
        }
        addView(subtitleView)

        inputEditText = EditText(context).apply {
            hint = "Enter Admin Password"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            contentDescription = "Admin Password Input"

            val inputBg = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#261414"))
                setStroke(2, Color.parseColor("#DC2626"))
            }
            background = inputBg
            setPadding(32, 28, 32, 28)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE && isEnabled) {
                    val pass = text.toString()
                    if (pass.isNotEmpty()) {
                        onPasswordSubmitted(pass)
                    }
                    true
                } else {
                    false
                }
            }
        }
        addView(inputEditText)

        errorTextView = TextView(context).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 24
            }
        }
        addView(errorTextView)

        val buttonContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        cancelButton = Button(context).apply {
            text = "Back / Cancel"
            textSize = 15f
            setTextColor(Color.parseColor("#E2E8F0"))
            contentDescription = "Cancel and go back"
            val cancelBg = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#262626"))
            }
            background = cancelBg
            setPadding(32, 24, 32, 24)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 16
            }
            setOnClickListener {
                onCancelClicked()
            }
        }
        buttonContainer.addView(cancelButton)

        submitButton = Button(context).apply {
            text = "Unlock (30s)"
            textSize = 15f
            setTextColor(Color.WHITE)
            contentDescription = "Submit Admin Password"
            typeface = Typeface.DEFAULT_BOLD
            val submitBg = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#DC2626"))
            }
            background = submitBg
            setPadding(32, 24, 32, 24)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val pass = inputEditText.text.toString()
                if (pass.isNotEmpty() && isEnabled) {
                    onPasswordSubmitted(pass)
                }
            }
        }
        buttonContainer.addView(submitButton)

        addView(buttonContainer)
    }

    fun showError(message: String) {
        errorTextView.text = message
        inputEditText.text.clear()
    }

    fun showLockout(remainingSeconds: Long) {
        errorTextView.text = "Too many incorrect attempts. Locked out for $remainingSeconds seconds."
        inputEditText.isEnabled = false
        submitButton.isEnabled = false
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true // Never allow any touch on this security overlay to leak through to underlying windows!
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (!event.isCanceled) {
                    onCancelClicked()
                }
                return true
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

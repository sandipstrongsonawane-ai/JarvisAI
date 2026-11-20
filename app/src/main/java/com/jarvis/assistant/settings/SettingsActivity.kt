package com.jarvis.assistant.settings

import com.jarvis.assistant.security.AppLockManager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.GeminiClient
import com.jarvis.assistant.ai.JarvisLlmClient
import com.jarvis.assistant.util.PersistentMemory
import com.jarvis.assistant.util.SettingsManager
import com.jarvis.assistant.voice.TextToSpeechHelper
import com.jarvis.assistant.voice.VoiceAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var memory: PersistentMemory
    private lateinit var tts: TextToSpeechHelper
    private lateinit var voiceAuth: VoiceAuthManager
    private lateinit var voiceTypeLabel: TextView
    private lateinit var noteText: TextView
    private lateinit var voiceAuthStatus: TextView
    private val voiceChipRefs = mutableMapOf<String, LinearLayout>()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) runEnrollment() else toast("Microphone permission is needed to enroll your voice.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        memory = PersistentMemory(this)
        tts = TextToSpeechHelper(this)
        voiceAuth = VoiceAuthManager(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.glass_screen_bg)
            addView(com.jarvis.assistant.ui.CornerFrameView(this@SettingsActivity))
            addView(buildUi())
        })
    }

    private val cyan = Color.parseColor("#00D9FF")
    private val cyanDim = Color.parseColor("#007A99")
    private val hudText = Color.parseColor("#B8ECFF")
    private val hudTextDim = Color.parseColor("#5A8A99")
    private val bg = Color.parseColor("#03080E")

    private fun hudButton(label: String, filled: Boolean = false, iconRes: Int? = null, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (filled) Color.parseColor("#03080E") else cyan)
        background = ContextCompat.getDrawable(
            this@SettingsActivity,
            if (filled) R.drawable.glass_button_filled else R.drawable.glass_button_bg
        )
        if (iconRes != null) {
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = 20
        }
        setPadding(32, 28, 32, 28)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 16
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun linkRow(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.glass_button_bg)
        setPadding(28, 24, 28, 24)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = 14
        layoutParams = lp
        addView(ImageView(this@SettingsActivity).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val size = 88
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 24 }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(2, cyanDim)
                setColor(Color.parseColor("#0A1825"))
            }
            setPadding(20, 20, 20, 20)
        })

        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(cyan)
                textSize = 15f
                typeface = Typeface.MONOSPACE
            })
            addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                setTextColor(hudTextDim)
                textSize = 12f
            })
        })

        addView(TextView(this@SettingsActivity).apply {
            text = "\u203A"
            setTextColor(cyanDim)
            textSize = 22f
        })

        setOnClickListener { onClick() }
    }

    private fun sectionCard(builder: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.glass_card_bg)
        setPadding(36, 32, 36, 32)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 32
        layoutParams = lp
        builder()
    }

    private fun sectionTitle(text: String, iconRes: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            layoutParams = LinearLayout.LayoutParams(6, 36)
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.hud_accent_bar)
        })
        if (iconRes != 0) {
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(32, 32).apply { marginStart = 12; marginEnd = 4 }
            })
        }
        addView(TextView(this@SettingsActivity).apply {
            this.text = "  ${text.uppercase()}"
            setTextColor(cyan)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
        })
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = 16
        layoutParams = lp
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(hudText)
        textSize = 13f
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = 12
        layoutParams = lp
    }

    private fun hudEditText(initial: String) = EditText(this).apply {
        setText(initial)
        setTextColor(Color.WHITE)
        setHintTextColor(hudTextDim)
        background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.glass_button_bg)
        setPadding(28, 20, 28, 20)
    }

    // Voice type selector card — icon + label, highlighted border when selected
    private fun voiceTypeChip(type: String, iconRes: Int, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(16, 32, 16, 32)
        background = GradientDrawable().apply {
            cornerRadius = 28f
            setColor(if (selected) Color.parseColor("#1A00D9FF") else Color.TRANSPARENT)
            setStroke(3, if (selected) cyan else Color.parseColor("#1A3040"))
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = 12
        layoutParams = lp

        addView(ImageView(this@SettingsActivity).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(48, 48)
        })
        addView(TextView(this@SettingsActivity).apply {
            text = type.uppercase()
            setTextColor(if (selected) cyan else hudTextDim)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tlp.topMargin = 8
            layoutParams = tlp
        })

        setOnClickListener { onClick() }
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 80)
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(8, 48)
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.hud_accent_bar)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "  J.A.R.V.I.S — SETTINGS"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.MONOSPACE
            })
        })

        // Usage stats + feedback as icon link-rows
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 28
            layoutParams = lp
            addView(linkRow(R.drawable.ic_stats, "USAGE STATS", "View system usage and activity") {
                startActivity(Intent(this@SettingsActivity, StatsActivity::class.java))
            })
            addView(linkRow(R.drawable.ic_feedback, "SEND FEEDBACK", "Help improve J.A.R.V.I.S") {
                startActivity(Intent(this@SettingsActivity, FeedbackActivity::class.java))
            })
        })

        root.addView(sectionCard {
            addView(sectionTitle("Puppy Companion"))
            addView(bodyText("Enable the movable puppy overlay. Android asks for a separate display-over-other-apps permission."))
            addView(hudButton("ENABLE PUPPY OVERLAY") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@SettingsActivity)) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                        toast("Could not open overlay permission settings.")
                    }
                } else {
                    com.jarvis.assistant.ui.PuppyOverlay.show(applicationContext)
                    toast("Puppy companion enabled.")
                }
            })
        })

        root.addView(sectionCard {
            addView(sectionTitle("Voice Speed"))
            val speedLabel = bodyText("${settings.getVoiceSpeed()}x")
            addView(speedLabel)
            addView(SeekBar(this@SettingsActivity).apply {
                max = 20
                progress = ((settings.getVoiceSpeed() - 0.3f) * 10).toInt().coerceIn(0, 20)
                progressTintList = android.content.res.ColorStateList.valueOf(cyan)
                thumbTintList = android.content.res.ColorStateList.valueOf(cyan)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        val speed = 0.3f + value / 10f
                        settings.setVoiceSpeed(speed)
                        speedLabel.text = "${"%.1f".format(speed)}x"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { tts.speak("Voice speed test.") }
                })
            })
        })

        root.addView(sectionCard {
            addView(sectionTitle("Your Name"))
            val nameInput = hudEditText(settings.getUserName())
            addView(nameInput)
            addView(hudButton("SAVE NAME") {
                val name = nameInput.text.toString().trim()
                if (name.isNotBlank()) { settings.setUserName(name); toast("Saved.") }
            })
        })
        
        root.addView(sectionCard {
            addView(sectionTitle("Cloud AI & Voice API Keys"))
            addView(bodyText("Keys are encrypted on this phone and are not embedded in the APK. Groq is tried first; Gemini is the fallback."))
            val groqInput = hudEditText(settings.getGroqApiKey()).apply {
                hint = "Groq API key"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            val geminiInput = hudEditText(settings.getGeminiApiKey()).apply {
                hint = "Gemini API key"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            val elevenLabsInput = hudEditText(settings.getElevenLabsApiKey()).apply {
                hint = "ElevenLabs API key"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            val elevenLabsVoiceIdInput = hudEditText(settings.getElevenLabsVoiceId()).apply {
                hint = "ElevenLabs female voice ID"
                setSingleLine(true)
            }
            val weatherInput = hudEditText(settings.getOpenWeatherApiKey()).apply {
                hint = "OpenWeather API key"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            addView(bodyText("GROQ"))
            addView(groqInput)
            addView(bodyText("GEMINI"))
            addView(geminiInput)
            addView(bodyText("ELEVENLABS — FEMALE VOICE API KEY"))
            addView(elevenLabsInput)
            addView(bodyText("ELEVENLABS VOICE ID"))
            addView(elevenLabsVoiceIdInput)
            addView(bodyText("OPENWEATHER"))
            addView(weatherInput)
            var keysVisible = false
            addView(hudButton("SHOW / HIDE KEYS") {
                keysVisible = !keysVisible
                val type = InputType.TYPE_CLASS_TEXT or if (keysVisible) {
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                groqInput.inputType = type
                geminiInput.inputType = type
                elevenLabsInput.inputType = type
                weatherInput.inputType = type
                groqInput.setSelection(groqInput.text.length)
                geminiInput.setSelection(geminiInput.text.length)
                elevenLabsInput.setSelection(elevenLabsInput.text.length)
                weatherInput.setSelection(weatherInput.text.length)
            })
            addView(hudButton("SAVE API KEYS", filled = true) {
                val voiceId = elevenLabsVoiceIdInput.text.toString().trim()
                if (!voiceId.matches(Regex("^[A-Za-z0-9_-]{8,64}$"))) {
                    toast("Invalid ElevenLabs voice ID. No API keys were changed.")
                } else {
                    // Validate every field before writing any provider setting.
                    settings.setGroqApiKey(groqInput.text.toString().trim())
                    settings.setGeminiApiKey(geminiInput.text.toString().trim())
                    settings.setElevenLabsApiKey(elevenLabsInput.text.toString().trim())
                    settings.setElevenLabsVoiceId(voiceId)
                    settings.setOpenWeatherApiKey(weatherInput.text.toString().trim())
                    toast("API keys and voice ID saved securely on this phone.")
                }
            })
            addView(hudButton("TEST GROQ CONNECTION") {
                testCloudKey("Groq", groqInput.text.toString().trim())
            })
            addView(hudButton("TEST GEMINI CONNECTION") {
                testCloudKey("Gemini", geminiInput.text.toString().trim())
            })
        })

        root.addView(sectionCard {
            addView(sectionTitle("Remembered Note"))
            noteText = bodyText(memory.recall("last_note") ?: "(nothing remembered yet)")
            addView(noteText)
            addView(hudButton("CLEAR NOTE") {
                memory.forget("last_note")
                noteText.text = "(nothing remembered yet)"
            })
        })

        root.addView(sectionCard {
            addView(sectionTitle("Voice Authentication", R.drawable.ic_mic))
            addView(bodyText(
                "Offline neural speaker verification (CAM++). When Voice Lock is ON, Jarvis " +
                "checks the speaker before every spoken command. Typed chat is not voice-verified. " +
                "The random spoken phrase is only a basic replay deterrent; it cannot guarantee " +
                "liveness or stop live relays and generated-voice attacks. This is not bank-grade security."
            ))
            voiceAuthStatus = bodyText(voiceAuthStatusText())
            addView(voiceAuthStatus)

            addView(hudButton("ENROLL MY VOICE", filled = true, iconRes = R.drawable.ic_mic) {
                if (ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    runEnrollment()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            })

            lateinit var voiceAuthToggle: Button
            voiceAuthToggle = hudButton(
                if (voiceAuth.isEnabled()) "DISABLE VOICE LOCK" else "ENABLE VOICE LOCK"
            ) {
                if (!voiceAuth.isEnrolled()) {
                    toast("Enroll your voice first.")
                } else {
                    val nowEnabled = !voiceAuth.isEnabled()
                    voiceAuth.setEnabled(nowEnabled)
                    voiceAuthStatus.text = voiceAuthStatusText()
                }
            }
            addView(voiceAuthToggle)

            addView(hudButton("RESET VOICE ENROLLMENT") {
                voiceAuth.resetEnrollment()
                voiceAuthStatus.text = voiceAuthStatusText()
                voiceAuthToggle.text = "ENABLE VOICE LOCK"
                toast("Voice enrollment cleared.")
            })
        })

        root.addView(sectionCard {
            
            addView(sectionTitle("Jarvis App Lock", R.drawable.ic_lock))
            addView(bodyText(
                "PIN protects apps you lock by voice (\"lock WhatsApp\"). " +
                "Stored as a hash only — not your phone lock screen PIN."
            ))
            val pinStatus = bodyText(
                if (AppLockManager(this@SettingsActivity).hasPin()) "PIN is set."
                else "No PIN set yet — lock commands need a PIN first."
            )
            addView(pinStatus)
            addView(hudButton("SET PASSWORD") {
                showGlassInputDialog(
                    title = "Jarvis App Password",
                    message = "Min 4 characters. Stored as hash only.",
                    hint = "Password",
                    password = true,
                    positive = "Save"
                ) { s ->
                    if (s.length >= 4) {
                        AppLockManager(this@SettingsActivity).setCredential(
                            AppLockManager.LockType.PASSWORD, s
                        )
                        toast("Password saved.")
                    } else toast("Min 4 characters.")
                }
            })
            addView(hudButton("SET PATTERN PATH") {
                showGlassInputDialog(
                    title = "Pattern (dots 0-8)",
                    message = "3x3 grid: 0 1 2 / 3 4 5 / 6 7 8. Example: 0-3-6-7-8",
                    hint = "0-1-2-5-8",
                    password = true,
                    positive = "Save"
                ) { s ->
                    val v = s.trim()
                    if (v.length >= 5 && v.contains("-")) {
                        AppLockManager(this@SettingsActivity).setCredential(
                            AppLockManager.LockType.PATTERN, v
                        )
                        toast("Pattern saved.")
                    } else toast("Use form like 0-1-2-5-8")
                }
            })
            addView(hudButton("SET / CHANGE APP PIN") {
                showJarvisPinDialog(pinStatus)
            })
            addView(hudButton("CLEAR APP PIN") {
                val prefs = getSharedPreferences("jarvis_app_lock", MODE_PRIVATE)
                prefs.edit().remove("pin_hash").apply()
                pinStatus.text = "No PIN set yet — lock commands need a PIN first."
                toast("App PIN cleared.")
            })

            addView(sectionTitle("Lock Screen", R.drawable.ic_lock))
            addView(bodyText(
                "Android no longer allows regular apps to set or change your device's lock " +
                "screen PIN/pattern directly (a security restriction since Android 8) — only " +
                "this shortcut into system settings is possible. For an in-app lock instead, " +
                "use \"lock [app name]\" by voice, which is Jarvis's own PIN-gated app lock."
            ))
            addView(hudButton("OPEN LOCK SCREEN SETTINGS") {
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (e: Exception) {
                    toast("Couldn't open security settings on this device.")
                }
            })
        })

        root.addView(hudButton("CLOSE", iconRes = R.drawable.ic_back) { finish() }.apply {
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.topMargin = 48
            layoutParams = lp
        })

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun testCloudKey(provider: String, key: String) {
        if (key.isBlank()) {
            toast("Enter the $provider API key first.")
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (provider == "Groq") {
                        val response = JarvisLlmClient(apiKeyProvider = { key }).chat("Reply with only the word OK.")
                        if (response.ok) {
                            "Groq generation successful."
                        } else {
                            "Groq generation failed: ${response.error.orEmpty().take(180)}"
                        }
                    } else {
                        val response = GeminiClient(apiKeyProvider = { key })
                            .getCommand("Reply with only the word OK.")
                        val detail = response.message.orEmpty()
                        if (detail.startsWith("Gemini API error") ||
                            detail.startsWith("Gemini API key missing") ||
                            detail.contains("available models abhi busy", ignoreCase = true)
                        ) {
                            "Gemini generation failed: ${detail.take(180)}"
                        } else {
                            "Gemini generation successful."
                        }
                    }
                } catch (e: Exception) {
                    "$provider test failed: ${e.message.orEmpty().take(160)}"
                }
            }
            toast(result)
        }
    }

    private fun voiceAuthStatusText(): String = when {
        !voiceAuth.isEnrolled() -> "Not enrolled yet."
        voiceAuth.isEnabled() -> "Enrolled — voice lock is ON."
        else -> "Enrolled — voice lock is OFF."
    }

    private fun runEnrollment() {
        toast("Voice enrollment starting. Background wake word remains enabled. Say the exact same phrase for all three samples.")
        scope.launch {
            try {
                val samples = mutableListOf<ShortArray>()
                val enrollmentPhrase = "Meri awaaz pehchano"
                val prompts = listOf(
                    "First sample. Say: $enrollmentPhrase",
                    "Second sample. Repeat exactly: $enrollmentPhrase",
                    "Final sample. Repeat exactly: $enrollmentPhrase"
                )
                for (i in prompts.indices) {
                    tts.speak(prompts[i])
                    val promptFinished = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        while (tts.isSpeaking()) kotlinx.coroutines.delay(100L)
                        true
                    } ?: false
                    if (!promptFinished) {
                        tts.stop()
                        toast("Voice prompt did not finish. Please retry enrollment.")
                        return@launch
                    }
                    val sample = withContext(Dispatchers.IO) { voiceAuth.captureSample(5000) }
                    if (sample == null) {
                        toast("Sample ${i + 1} was too quiet or too short. Enrollment stopped; please retry.")
                        tts.speak("I could not capture that sample. Please try enrollment again in a quiet place.")
                        return@launch
                    }
                    samples += sample
                    kotlinx.coroutines.delay(500L)
                }
                val enrolled = voiceAuth.enrollFromSamples(samples)
                if (!enrolled) {
                    toast(voiceAuth.lastEnrollmentFailureReason() ?: "Voice samples did not match reliably. Keep the phone 15–20 cm away and repeat the exact phrase.")
                    tts.speak("Voice enrollment failed. Please try again.")
                    return@launch
                }
                voiceAuth.setEnabled(true)
                voiceAuthStatus.text = voiceAuthStatusText()
                toast("Neural voice enrolled and voice lock enabled.")
                tts.speak("Voice enrollment complete. Voice lock is now enabled.")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    private fun showJarvisPinDialog(pinStatus: TextView) {
        showGlassInputDialog(
            title = "Jarvis App PIN",
            message = "Min 4 digits. Hash only — not phone lock PIN.",
            hint = "••••",
            password = false,
            positive = "SAVE"
        ) { pin ->
            if (pin.length < 4) toast("PIN must be at least 4 digits.")
            else {
                AppLockManager(this).setPin(pin)
                pinStatus.text = "PIN is set."
                toast("App PIN saved.")
            }
        }
    }



    private fun showGlassInputDialog(
        title: String,
        message: String? = null,
        hint: String,
        password: Boolean,
        positive: String = "Save",
        onSave: (String) -> Unit
    ) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(0xE0050E16.toInt())
                setStroke(2, 0x9900D9FF.toInt())
            }
        }
        if (!message.isNullOrBlank()) {
            root.addView(TextView(this).apply {
                text = message
                setTextColor(0xFF5A8A99.toInt())
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, pad / 2)
            })
        }
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(0xFF5A8A99.toInt())
            setTextColor(0xFFE8FBFF.toInt())
            textSize = 16f
            typeface = Typeface.MONOSPACE
            inputType = if (password)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(0x660A1825.toInt())
                setStroke(2, 0x6600D9FF.toInt())
            }
            setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
        }
        root.addView(input)

        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(root)
            .setPositiveButton(positive) { _, _ -> onSave(input.text?.toString().orEmpty()) }
            .setNegativeButton("Cancel", null)
            .create()

        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = 32f
                setColor(0xF003080E.toInt())
                setStroke(2, 0xAA00D9FF.toInt())
            })
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF00D9FF.toInt())
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF5A8A99.toInt())
            dlg.findViewById<TextView>(android.R.id.title)?.apply {
                setTextColor(0xFF00D9FF.toInt())
                typeface = Typeface.MONOSPACE
            }
        }
        dlg.show()
    }

}

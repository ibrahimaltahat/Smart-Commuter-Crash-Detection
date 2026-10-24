package com.smartcommuter.presentation.countdown

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcommuter.domain.usecase.SafetyAnomalyState
import com.smartcommuter.service.TripMonitoringService
import kotlinx.coroutines.delay

class EmergencyCountdownActivity : ComponentActivity() {

    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. إيقاظ الشاشة وتجاوز قفل الهاتف (Lock Screen Bypass)
        turnScreenOnAndKeyguardOff()

        // 2. منع إغلاق الشاشة بزر الرجوع (Back Button) بدون إدخال رمز الـ PIN
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // لا نفعل شيئاً: يجب إدخال الـ PIN لإلغاء الإنذار لحماية المستخدم من الإغلاق الخاطئ
            }
        })

        // 3. تهيئة محرك الاهتزاز والصوت التحذيري
        initHapticsAndSiren()

        setContent {
            val anomalyState by TripMonitoringService.anomalyState.collectAsStateWithLifecycle()

            EmergencyCountdownScreen(
                anomalyState = anomalyState,
                onPinVerified = {
                    cancelAlarmAndClose()
                },
                onCountdownTick = { remainingSeconds ->
                    triggerTickFeedback(remainingSeconds)
                },
                onCountdownExpired = {
                    triggerEmergencyEscalation()
                },
                verifyPinUseCase = { enteredPin ->
                    // ملاحظة: اربطها لاحقاً مع EncryptedSharedPreferences/DataStore
                    // للتجربة والمناقشة الـ PIN الافتراضي هو "1234"
                    enteredPin == "1234"
                }
            )
        }
    }

    private fun turnScreenOnAndKeyguardOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun initHapticsAndSiren() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {
        }
    }

    /**
     * توليد نبضة اهتزاز وصوت تنبيه مع كل ثانية، وتزداد حدتها في آخر 10 ثوانٍ
     */
    private fun triggerTickFeedback(remainingSeconds: Int) {
        if (remainingSeconds <= 0) return

        val durationMs = if (remainingSeconds <= 10) 400L else 150L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }

        val toneType = if (remainingSeconds <= 10) {
            ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
        } else {
            ToneGenerator.TONE_PROP_BEEP
        }
        toneGenerator?.startTone(toneType, 200)
    }

    /**
     * إلغاء الإنذار الكاذب عند إدخال الـ PIN الصحيح
     */
    private fun cancelAlarmAndClose() {
        stopHapticsAndSiren()
        cancelAlertNotification()

        val cancelIntent = Intent(this, TripMonitoringService::class.java).apply {
            action = TripMonitoringService.ACTION_CANCEL_ALARM
        }
        startService(cancelIntent)
        finish()
    }

    /**
     * تصعيد الطوارئ عند انتهاء الـ 30 ثانية دون استجابة
     */
    private fun triggerEmergencyEscalation() {
        stopHapticsAndSiren()
        // هنا يتم إبلاغ الخدمة أو الـ ViewModel ببدء SOSRepository.executeEmergencyEscalation(...)
        // وتتحول الشاشة إلى وضع "تم إرسال الاستغاثة وجاري البث الحي"
    }

    private fun cancelAlertNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1002) // ALERT_NOTIFICATION_ID
    }

    private fun stopHapticsAndSiren() {
        vibrator?.cancel()
        toneGenerator?.release()
        toneGenerator = null
    }

    override fun onDestroy() {
        stopHapticsAndSiren()
        super.onDestroy()
    }

        @Composable
        fun EmergencyCountdownScreen(
            anomalyState: SafetyAnomalyState,
            onPinVerified: () -> Unit,
            onCountdownTick: (Int) -> Unit,
            onCountdownExpired: () -> Unit,
            verifyPinUseCase: (String) -> Boolean
        ) {
            var remainingSeconds by remember { mutableIntStateOf(30) }
            var enteredPin by remember { mutableStateOf("") }
            var isPinError by remember { mutableStateOf(false) }
            var isSosDispatched by remember { mutableStateOf(false) }

            // تشغيل المؤقت التنازلي (30 ثانية)
            LaunchedEffect(isSosDispatched) {
                if (!isSosDispatched) {
                    while (remainingSeconds > 0) {
                        onCountdownTick(remainingSeconds)
                        delay(1000L)
                        remainingSeconds--
                    }
                    isSosDispatched = true
                    onCountdownExpired()
                }
            }

            // حركة نبضية (Pulse Animation) للعداد لجذب انتباه المستخدم المصاب
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = if (remainingSeconds <= 10 && !isSosDispatched) 1.12f else 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            val backgroundColor by animateColorAsState(
                targetValue = when {
                    isSosDispatched -> Color(0xFF8B0000) // أحمر قاني عند إرسال الاستغاثة
                    remainingSeconds <= 10 -> Color(0xFF3B0A0A)
                    else -> Color(0xFF121212)
                },
                label = "bgColor"
            )

            val anomalyDescription = when (anomalyState) {
                is SafetyAnomalyState.CrashDetected ->
                    "تم رصد اصطدام قوي بقوة ${"%.1f".format(anomalyState.impactGForce)}G!"
                is SafetyAnomalyState.RouteDeviated ->
                    "تم رصد خروج عن المسار الآمن المحدد!"
                is SafetyAnomalyState.UnusualStoppage ->
                    "توقف مريب منذ ${anomalyState.stationaryDurationMinutes} دقائق!"
                else -> "تم رصد حالة طوارئ محتملة!"
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. رأس الشاشة: نوع الخطر المرصود
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isSosDispatched) "🚨 تم إطلاق نداء الاستغاثة!" else "⚠️ تنبيه أمان طارئ",
                        color = Color(0xFFFF5252),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isSosDispatched) {
                            "تم إرسال موقعك الجغرافي عبر SMS وتفعيل البث المباشر والصندوق الأسود للأهل."
                        } else {
                            anomalyDescription
                        },
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // 2. حلقة العد التنازلي الدائرية (Circular Countdown Ring)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(190.dp)
                        .scale(pulseScale)
                ) {
                    val progress by animateFloatAsState(
                        targetValue = remainingSeconds / 30f,
                        animationSpec = tween(durationMillis = 900),
                        label = "progress"
                    )

                    CircularProgressIndicator(
                        progress = { if (isSosDispatched) 1f else progress },
                        modifier = Modifier.fillMaxSize(),
                        color = if (remainingSeconds <= 10) Color(0xFFFF1744) else Color(0xFFFF9100),
                        strokeWidth = 12.dp,
                        trackColor = Color(0xFF2C2C2C)
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isSosDispatched) "SOS" else "$remainingSeconds",
                            color = Color.White,
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (!isSosDispatched) {
                            Text(
                                text = "ثانية متبقية",
                                color = Color.LightGray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // 3. مؤشر نقاط رمز الأمان (4-Digit PIN Dots)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isPinError) "❌ رمز الأمان غير صحيح! حاول فوراً"
                        else "أدخل رمز الأمان (PIN) لتأكيد سلامتك وإلغاء الإنذار",
                        color = if (isPinError) Color(0xFFFF5252) else Color(0xFFE0E0E0),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) { index ->
                            val isFilled = index < enteredPin.length
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isPinError -> Color(0xFFFF1744)
                                            isFilled -> Color(0xFF00E676)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = if (isPinError) Color(0xFFFF1744) else Color.White,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }

                // 4. لوحة الأرقام الكبيرة (High-Contrast Numpad - NFR-07)
                HighStressPinKeypad(
                    onDigitClick = { digit ->
                        if (enteredPin.length < 4) {
                            isPinError = false
                            enteredPin += digit
                            // فحص الرمز تلقائياً بمجرد إدخال الرقم الرابع دون الحاجة لزر "تأكيد"
                            if (enteredPin.length == 4) {
                                if (verifyPinUseCase(enteredPin)) {
                                    onPinVerified()
                                } else {
                                    isPinError = true
                                    enteredPin = ""
                                }
                            }
                        }
                    },
                    onBackspaceClick = {
                        if (enteredPin.isNotEmpty()) {
                            enteredPin = enteredPin.dropLast(1)
                            isPinError = false
                        }
                    },
                    onSendImmediateSosClick = {
                        remainingSeconds = 0
                        isSosDispatched = true
                        onCountdownExpired()
                    },
                    isSosDispatched = isSosDispatched
                )
            }
        }

        @Composable
        private fun HighStressPinKeypad(
            onDigitClick: (String) -> Unit,
            onBackspaceClick: () -> Unit,
            onSendImmediateSosClick: () -> Unit,
            isSosDispatched: Boolean
        ) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("SOS", "0", "⌫")
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { key ->
                            val isSosButton = key == "SOS"
                            val buttonBgColor = when {
                                isSosButton -> Color(0xFFD50000)
                                key == "⌫" -> Color(0xFF424242)
                                else -> Color(0xFF262626)
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(68.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(buttonBgColor)
                                    .clickable(enabled = !(isSosButton && isSosDispatched)) {
                                        when (key) {
                                            "⌫" -> onBackspaceClick()
                                            "SOS" -> onSendImmediateSosClick()
                                            else -> onDigitClick(key)
                                        }
                                    }
                            ) {
                                Text(
                                    text = if (isSosButton) "إرسال فوري" else key,
                                    color = Color.White,
                                    fontSize = if (isSosButton) 15.sp else 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
}
package com.smartcommuter.domain.usecase

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import kotlin.math.sqrt

/**
 * حالات الخطر التي يمكن أن يرصدها النظام
 */
sealed class SafetyAnomalyState {
    object Normal : SafetyAnomalyState()
    data class CrashDetected(val impactGForce: Float, val preImpactSpeedKmh: Float) : SafetyAnomalyState()
    data class RouteDeviated(val currentLocation: LatLng, val toleranceMeters: Double) : SafetyAnomalyState()
    data class UnusualStoppage(val stationaryDurationMinutes: Long) : SafetyAnomalyState()
}

/**
 * 1. خوارزمية كشف الحوادث متعددة الشروط (Multi-Condition Crash Detection)
 */
class CrashDetectionEngine {

    companion object {
        private const val GRAVITY_EARTH = 9.80665f
        private const val CRITICAL_G_FORCE_THRESHOLD = 4.0f // 4G Impact
        private const val MIN_VEHICULAR_SPEED_KMH = 25.0f   // لتجنب إنذار سقوط الهاتف من اليد
        private const val IMPACT_VERIFICATION_WINDOW_MS = 3500L // مهلة 3.5 ثانية لفحص التوقف بعد الصدمة
    }

    private var lastKnownSpeedKmh: Float = 0f
    private var maxRecentSpeedKmh: Float = 0f
    private var lastSpeedTimestamp: Long = 0L

    private var pendingImpactGForce: Float? = null
    private var impactTimestamp: Long = 0L

    /**
     * تحديث سرعة الـ GPS باستمرار والاحتفاظ بأعلى سرعة خلال آخر 10 ثوانٍ
     * لأن الـ GPS قد يتأخر ثانية أو ثانيتين في تحديث السرعة لحظة الاصطدام
     */
    fun updateGpsVelocity(speedMetersPerSec: Float, currentTimeMs: Long = System.currentTimeMillis()) {
        val speedKmh = speedMetersPerSec * 3.6f
        lastKnownSpeedKmh = speedKmh

        if (currentTimeMs - lastSpeedTimestamp <= 10_000L) {
            if (speedKmh > maxRecentSpeedKmh) maxRecentSpeedKmh = speedKmh
        } else {
            maxRecentSpeedKmh = speedKmh
        }
        lastSpeedTimestamp = currentTimeMs
    }

    /**
     * تقييم قراءات حساس التسارع الخطي (TYPE_LINEAR_ACCELERATION)
     */
    fun evaluateSensorFrame(
        ax: Float,
        ay: Float,
        az: Float,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SafetyAnomalyState {
        // حساب محصلة التسارع بالاتجاهات الثلاثة وتحويلها إلى وحدة G-Force
        val netAcceleration = sqrt((ax * ax) + (ay * ay) + (az * az))
        val currentGForce = netAcceleration / GRAVITY_EARTH

        // المرحلة 1: رصد ذروة الصدمة (Spike) بشرط أن تكون المركبة مسرعة قبلها
        if (currentGForce >= CRITICAL_G_FORCE_THRESHOLD && maxRecentSpeedKmh >= MIN_VEHICULAR_SPEED_KMH) {
            if (pendingImpactGForce == null || currentGForce > pendingImpactGForce!!) {
                pendingImpactGForce = currentGForce
                impactTimestamp = currentTimeMs
            }
        }

        // المرحلة 2: التحقق من حالة ما بعد الصدمة (Post-Impact Verification)
        pendingImpactGForce?.let { recordedPeakG ->
            val elapsedSinceImpact = currentTimeMs - impactTimestamp
            if (elapsedSinceImpact >= IMPACT_VERIFICATION_WINDOW_MS) {
                // إذا هبطت السرعة بعد الصدمة إلى أقل من 5 كم/ساعة، نؤكد حدوث الحادث
                if (lastKnownSpeedKmh < 5.0f) {
                    val confirmedPreSpeed = maxRecentSpeedKmh
                    resetState()
                    return SafetyAnomalyState.CrashDetected(
                        impactGForce = recordedPeakG,
                        preImpactSpeedKmh = confirmedPreSpeed
                    )
                } else {
                    // إذا استمرت السيارة بالمشى بسرعة طبيعية (مثلاً: مطب قوي على الطريق السريع)، نلغي الاشتباه
                    resetState()
                }
            }
        }

        return SafetyAnomalyState.Normal
    }

    fun resetState() {
        pendingImpactGForce = null
        impactTimestamp = 0L
    }
}

/**
 * 2. خوارزمية مراقبة السياج الجغرافي للمسار (Geofence Corridor & Stoppage Engine)
 */
class RouteAnomalyEngine {

    private var expectedRoutePoints: List<LatLng> = emptyList()
    private var corridorRadiusMeters: Double = 500.0
    private var stationaryStartTimestamp: Long? = null

    /**
     * فك تشفير خط المسار القادم من Google Directions API عند بدء الرحلة
     */
    fun initializeRouteCorridor(encodedPolyline: String, radiusMeters: Double = 500.0) {
        expectedRoutePoints = PolyUtil.decode(encodedPolyline)
        corridorRadiusMeters = radiusMeters
        stationaryStartTimestamp = null
    }

    /**
     * فحص موقع المستخدم الحالي مقارنة بالممر الجغرافي المسموح به
     */
    fun evaluateLocation(
        currentLat: Double,
        currentLng: Double,
        speedMetersPerSec: Float,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SafetyAnomalyState {
        if (expectedRoutePoints.isEmpty()) return SafetyAnomalyState.Normal

        val currentLatLng = LatLng(currentLat, currentLng)

        // 1. فحص الانحراف عن المسار باستخدام حسابات الهندسة الكروية (geodesic = true)
        val isInsideCorridor = PolyUtil.isLocationOnPath(
            currentLatLng,
            expectedRoutePoints,
            true, // حساب انحناء الكرة الأرضية
            corridorRadiusMeters
        )

        if (!isInsideCorridor) {
            return SafetyAnomalyState.RouteDeviated(
                currentLocation = currentLatLng,
                toleranceMeters = corridorRadiusMeters
            )
        }

        // 2. فحص التوقف المريب لفترة طويلة (أكثر من 5 دقائق بسرعة صفر)
        val speedKmh = speedMetersPerSec * 3.6f
        if (speedKmh < 1.5f) {
            if (stationaryStartTimestamp == null) {
                stationaryStartTimestamp = currentTimeMs
            } else {
                val stoppedMinutes = (currentTimeMs - stationaryStartTimestamp!!) / 60_000L
                if (stoppedMinutes >= 5L) {
                    stationaryStartTimestamp = currentTimeMs // لمنع تكرار التنبيه كل ثانية
                    return SafetyAnomalyState.UnusualStoppage(stoppedMinutes)
                }
            }
        } else {
            stationaryStartTimestamp = null
        }

        return SafetyAnomalyState.Normal
    }
}
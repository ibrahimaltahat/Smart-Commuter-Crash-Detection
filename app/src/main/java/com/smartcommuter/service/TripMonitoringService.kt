package com.smartcommuter.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.smartcommuter.domain.usecase.CrashDetectionEngine
import com.smartcommuter.domain.usecase.RouteAnomalyEngine
import com.smartcommuter.domain.usecase.SafetyAnomalyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TripMonitoringService : LifecycleService(), SensorEventListener {

    companion object {
        const val ACTION_START_TRIP = "ACTION_START_TRIP"
        const val ACTION_STOP_TRIP = "ACTION_STOP_TRIP"
        const val ACTION_CANCEL_ALARM = "ACTION_CANCEL_ALARM"
        const val ACTION_TRIGGER_SILENT_SOS = "ACTION_TRIGGER_SILENT_SOS"
        const val EXTRA_ENCODED_POLYLINE = "EXTRA_ENCODED_POLYLINE"
        const val EXTRA_CORRIDOR_RADIUS = "EXTRA_CORRIDOR_RADIUS"

        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val CHANNEL_ID_MONITOR = "trip_monitor_channel"
        private const val CHANNEL_ID_EMERGENCY = "emergency_alert_channel"

        // StateFlow لمشاركة الحالة اللحظية مع الـ ViewModels والشاشات
        private val _anomalyState = MutableStateFlow<SafetyAnomalyState>(SafetyAnomalyState.Normal)
        val anomalyState: StateFlow<SafetyAnomalyState> = _anomalyState
    }

    private lateinit var sensorManager: SensorManager
    private var linearAccelSensor: Sensor? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null

    // الكاشف الصامت لضغطات زر الصوت (Silent SOS)
    private lateinit var silentVolumeDetector: SilentVolumeSosDetector

    private val crashEngine = CrashDetectionEngine()
    private val routeEngine = RouteAnomalyEngine()
    private var isAlertActive = false

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // تهيئة كاشف الاستغاثة الصامتة بزر الصوت
        silentVolumeDetector = SilentVolumeSosDetector(this) {
            executeSilentSosProtocol()
        }

        createNotificationChannels()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_TRIP -> {
                val polyline = intent.getStringExtra(EXTRA_ENCODED_POLYLINE) ?: ""
                val radius = intent.getDoubleExtra(EXTRA_CORRIDOR_RADIUS, 500.0)
                startForegroundMonitoring(polyline, radius)
                silentVolumeDetector.startListening()
            }
            ACTION_TRIGGER_SILENT_SOS -> {
                executeSilentSosProtocol()
            }
            ACTION_CANCEL_ALARM -> {
                resetEmergencyState()
            }
            ACTION_STOP_TRIP -> {
                silentVolumeDetector.stopListening()
                stopMonitoringService()
            }
        }
        return START_STICKY
    }

    private fun startForegroundMonitoring(encodedPolyline: String, radiusMeters: Double) {
        acquirePartialWakeLock()
        routeEngine.initializeRouteCorridor(encodedPolyline, radiusMeters)

        val notification = buildMonitoringNotification("جاري مراقبة أمان الرحلة والمسار...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        requestAdaptiveLocationUpdates(
            intervalMs = 15_000L,
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestAdaptiveLocationUpdates(intervalMs: Long, priority: Int) {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val currentSpeed = if (location.hasSpeed()) location.speed else 0f

                lifecycleScope.launch(Dispatchers.Default) {
                    crashEngine.updateGpsVelocity(currentSpeed)

                    if (!isAlertActive) {
                        val routeStatus = routeEngine.evaluateLocation(
                            currentLat = location.latitude,
                            currentLng = location.longitude,
                            speedMetersPerSec = currentSpeed
                        )
                        if (routeStatus !is SafetyAnomalyState.Normal) {
                            triggerPreAlertVerification(routeStatus)
                        }
                    }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || isAlertActive) return

        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]

            lifecycleScope.launch(Dispatchers.Default) {
                val crashStatus = crashEngine.evaluateSensorFrame(ax, ay, az)
                if (crashStatus is SafetyAnomalyState.CrashDetected) {
                    triggerPreAlertVerification(crashStatus)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun triggerPreAlertVerification(state: SafetyAnomalyState) {
        if (isAlertActive) return
        isAlertActive = true
        _anomalyState.value = state

        requestAdaptiveLocationUpdates(intervalMs = 1_000L, priority = Priority.PRIORITY_HIGH_ACCURACY)
        dispatchFullScreenCountdownAlert(state)
    }

    /**
     * تنفيذ الاستغاثة الصامتة فوراً دون إضاءة الشاشة أو إصدار صوت
     */
    @SuppressLint("MissingPermission")
    private fun executeSilentSosProtocol() {
        Log.d("TripMonitoringService", "Silent SOS Protocol Activated!")
        requestAdaptiveLocationUpdates(intervalMs = 1_000L, priority = Priority.PRIORITY_HIGH_ACCURACY)

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            lifecycleScope.launch(Dispatchers.IO) {
                val lat = location?.latitude ?: 32.2746
                val lng = location?.longitude ?: 35.8914
                Log.d("TripMonitoringService", "Dispatching Silent SOS at ($lat, $lng)")
                // هنا يتم استدعاء SOSRepository لإرسال الـ SMS والبث المباشر بصمت
            }
        }
    }

    private fun resetEmergencyState() {
        isAlertActive = false
        crashEngine.resetState()
        _anomalyState.value = SafetyAnomalyState.Normal
        requestAdaptiveLocationUpdates(
            intervalMs = 15_000L,
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
        )
    }

    private fun dispatchFullScreenCountdownAlert(state: SafetyAnomalyState) {
        val fullScreenIntent = Intent(
            this,
            Class.forName("com.smartcommuter.presentation.countdown.EmergencyCountdownActivity")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertTitle = when (state) {
            is SafetyAnomalyState.CrashDetected -> "تم رصد اصطدام قوي (${"%.1f".format(state.impactGForce)}G)!"
            is SafetyAnomalyState.RouteDeviated -> "تم رصد انحراف عن المسار المحدد!"
            is SafetyAnomalyState.UnusualStoppage -> "توقف غير معتاد منذ ${state.stationaryDurationMinutes} دقائق!"
            else -> "تنبيه أمان!"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_EMERGENCY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(alertTitle)
            .setContentText("أدخل رمز الأمان (PIN) خلال 30 ثانية لإلغاء إرسال الاستغاثة.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun buildMonitoringNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("حارس الأمان الشخصي نشط")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                CHANNEL_ID_MONITOR,
                "مراقبة الرحلة النشطة",
                NotificationManager.IMPORTANCE_LOW
            )
            val emergencyChannel = NotificationChannel(
                CHANNEL_ID_EMERGENCY,
                "إنذارات الطوارئ والحوادث",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 1000)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(monitorChannel)
            manager.createNotificationChannel(emergencyChannel)
        }
    }

    private fun acquirePartialWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmartCommuter::TripMonitoringWakeLock"
        ).apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun stopMonitoringService() {
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        silentVolumeDetector.stopListening()
        stopMonitoringService()
        super.onDestroy()
    }
}
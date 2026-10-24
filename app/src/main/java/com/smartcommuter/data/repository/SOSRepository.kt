package com.smartcommuter.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.net.toUri
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.storage.FirebaseStorage
import com.smartcommuter.data.hardware.AudioBlackboxRecorder
import com.smartcommuter.data.hardware.OfflineSmsDispatcher
import com.smartcommuter.data.local.dao.SafetyDao
import com.smartcommuter.data.local.entity.AudioBlackboxEntity
import com.smartcommuter.data.local.entity.IncidentEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SOSRepository(
    private val context: Context,
    private val safetyDao: SafetyDao,
    private val smsDispatcher: OfflineSmsDispatcher,
    private val audioRecorder: AudioBlackboxRecorder,
    private val firebaseDb: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val firebaseStorage: FirebaseStorage = FirebaseStorage.getInstance()
) {

    /**
     * تنفيذ بروتوكول الطوارئ القصوى بعد انتهاء عداد الـ 30 ثانية دون استجابة
     */
    suspend fun executeEmergencyEscalation(
        tripId: String,
        commuterUid: String,
        commuterName: String,
        anomalyType: String,
        peakGForce: Float,
        preImpactSpeedKmh: Float,
        currentLat: Double,
        currentLng: Double,
        bearing: Float
    ) = withContext(Dispatchers.IO) {

        val incidentId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val batteryPct = getDeviceBatteryPercentage()

        // 1. التوثيق المحلي الفوري في Room Database (Offline-First Guarantee)
        val incidentEntity = IncidentEventEntity(
            incidentId = incidentId,
            tripId = tripId,
            anomalyType = anomalyType,
            peakGForce = peakGForce,
            preImpactSpeedKmh = preImpactSpeedKmh,
            triggerLat = currentLat,
            triggerLng = currentLng,
            wasCancelledByPin = false,
            timestamp = now
        )
        safetyDao.insertIncident(incidentEntity)

        // جلب أرقام الأهل المخزنة محلياً في Room
        val contacts = safetyDao.getTrustedContacts()
        val smsPhoneList = contacts.filter { it.receiveSms }.map { it.phoneNumber }

        // 2. إطلاق المسارات الثلاثة المتوازية مع عزل الأخطاء (supervisorScope)
        supervisorScope {

            // المسار المتوازي الأول: إرسال SMS فوري عبر شبكة GSM (لا يحتاج إنترنت)
            val smsJob = async {
                smsDispatcher.sendEmergencySms(
                    phoneNumbers = smsPhoneList,
                    commuterName = commuterName,
                    anomalyType = anomalyType,
                    lat = currentLat,
                    lng = currentLng,
                    batteryPct = batteryPct,
                    tripId = tripId
                )
            }

            // المسار المتوازي الثاني: فتح بث مباشر على Firebase Realtime DB وتفعيل الـ Heartbeat
            val cloudRealtimeJob = async {
                initializeFirebaseEmergencyNode(
                    tripId = tripId,
                    commuterUid = commuterUid,
                    commuterName = commuterName,
                    anomalyType = anomalyType,
                    lat = currentLat,
                    lng = currentLng,
                    bearing = bearing,
                    batteryPct = batteryPct,
                    impactGForce = peakGForce
                )
            }

            // المسار المتوازي الثالث: تسجيل 3 مقاطع صوتية متتالية (كل مقطع 10 ثوانٍ) ورفعها
            val audioBlackboxJob = async {
                captureAndSyncAudioBlackbox(
                    tripId = tripId,
                    incidentId = incidentId,
                    clipCount = 3
                )
            }

            // انتظار اكتمال المهام الأولية دون أن يوقف فشل إحداها الأخرى
            smsJob.await()
            cloudRealtimeJob.await()
            audioBlackboxJob.await()
        }
    }

    /**
     * تهيئة عقدة الطوارئ في Firebase مع تفعيل ميزة .onDisconnect() الذكية
     */
    private suspend fun initializeFirebaseEmergencyNode(
        tripId: String,
        commuterUid: String,
        commuterName: String,
        anomalyType: String,
        lat: Double,
        lng: Double,
        bearing: Float,
        batteryPct: Int,
        impactGForce: Float
    ) {
        try {
            val emergencyRef = firebaseDb.getReference("active_emergencies").child(tripId)

            // ميزة هندسية: إذا تحطم الهاتف أو طفى بعد الحادث، يغير سيرفر Firebase الحالة تلقائياً!
            emergencyRef.child("connectionStatus")
                .onDisconnect()
                .setValue("DEVICE_OFFLINE_OR_DESTROYED")

            val payload = mapOf(
                "commuterUid" to commuterUid,
                "commuterName" to commuterName,
                "emergencyState" to anomalyType,
                "connectionStatus" to "ONLINE",
                "escalatedAt" to ServerValue.TIMESTAMP,
                "telemetry_live" to mapOf(
                    "lat" to lat,
                    "lng" to lng,
                    "speedKmh" to 0.0f,
                    "bearing" to bearing,
                    "batteryPct" to batteryPct,
                    "impactGForce" to impactGForce,
                    "lastUpdated" to ServerValue.TIMESTAMP
                )
            )

            emergencyRef.setValue(payload).await()
        } catch (e: Exception) {
            Log.e("SOSRepository", "Firebase RTDB init failed (Offline mode active): ${e.message}")
        }
    }

    /**
     * تحديث الإحداثيات الحية كل ثانية أثناء حالة الطوارئ النشطة
     */
    suspend fun pushLiveTelemetryUpdate(
        tripId: String,
        lat: Double,
        lng: Double,
        speedKmh: Float,
        bearing: Float
    ) {
        try {
            val telemetryUpdate = mapOf(
                "lat" to lat,
                "lng" to lng,
                "speedKmh" to speedKmh,
                "bearing" to bearing,
                "batteryPct" to getDeviceBatteryPercentage(),
                "lastUpdated" to ServerValue.TIMESTAMP
            )
            firebaseDb.getReference("active_emergencies")
                .child(tripId)
                .child("telemetry_live")
                .updateChildren(telemetryUpdate)
                .await()
        } catch (e: Exception) {
            Log.w("SOSRepository", "Live telemetry update skipped (No connection)")
        }
    }

    /**
     * تسجيل الصوت المحيطي وحفظه محلياً ثم رفعه إلى Firebase Storage
     */
    private suspend fun captureAndSyncAudioBlackbox(
        tripId: String,
        incidentId: String,
        clipCount: Int
    ) {
        for (index in 1..clipCount) {
            val audioFile: File? = audioRecorder.recordAmbientClip(
                incidentId = incidentId,
                clipIndex = index,
                durationMs = 10_000L
            )

            if (audioFile != null && audioFile.exists()) {
                // 1. الحفظ المبدئي في Room بحالة PENDING (لضمان عدم ضياع التسجيل لو لا يوجد نت)
                val logId = safetyDao.insertAudioLog(
                    AudioBlackboxEntity(
                        incidentId = incidentId,
                        localFilePath = audioFile.absolutePath,
                        durationSec = 10,
                        uploadStatus = "PENDING",
                        recordedAt = System.currentTimeMillis()
                    )
                )

                // 2. محاولة الرفع المباشر إلى Firebase Cloud Storage
                try {
                    val storageRef = firebaseStorage.reference
                        .child("emergency_audio/$tripId/${audioFile.name}")

                    storageRef.putFile(audioFile.toUri()).await()
                    val downloadUrl = storageRef.downloadUrl.await().toString()

                    // تحديث الحالة في Room إلى UPLOADED
                    safetyDao.updateAudioSyncStatus(logId, "UPLOADED", downloadUrl)

                    // إضافة رابط الصوت في شجرة البث المباشر للأهل
                    val clipData = mapOf(
                        "storageUrl" to downloadUrl,
                        "timestamp" to ServerValue.TIMESTAMP,
                        "durationSec" to 10
                    )
                    firebaseDb.getReference("active_emergencies")
                        .child(tripId)
                        .child("audio_blackbox_stream")
                        .child("clip_0$index")
                        .setValue(clipData)
                        .await()

                } catch (e: Exception) {
                    // في حال انقطاع الإنترنت، يبقى الملف محفوظاً في الهاتف وحالته FAILED ليتم رفعه لاحقاً
                    safetyDao.updateAudioSyncStatus(logId, "FAILED_WAITING_NETWORK", null)
                    Log.e("SOSRepository", "Audio upload deferred to offline queue: ${e.message}")
                }
            }
        }
    }

    private fun getDeviceBatteryPercentage(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) ((level / scale.toFloat()) * 100).toInt() else 100
    }
}
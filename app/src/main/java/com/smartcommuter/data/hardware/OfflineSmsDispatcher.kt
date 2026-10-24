package com.smartcommuter.data.hardware

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

class OfflineSmsDispatcher(private val context: Context) {

    fun sendEmergencySms(
        phoneNumbers: List<String>,
        commuterName: String,
        anomalyType: String,
        lat: Double,
        lng: Double,
        batteryPct: Int,
        tripId: String
    ) {
        if (phoneNumbers.isEmpty()) return

        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        // رابط خرائط جوجل المباشر الذي يفتح تطبيق الخرائط عند الأهل فوراً
        val googleMapsUrl = "https://maps.google.com/?q=$lat,$lng"

        val messageBody = """
            🚨 استغاثة طارئة ($anomalyType)
            المستخدم: $commuterName
            الموقع الحالي: $googleMapsUrl
            البطارية: $batteryPct%
            كود التتبع الحي: $tripId
        """.trimIndent()

        val messageParts = smsManager.divideMessage(messageBody)

        phoneNumbers.forEach { phone ->
            try {
                smsManager.sendMultipartTextMessage(
                    phone,
                    null,
                    messageParts,
                    null,
                    null
                )
                Log.d("OfflineSmsDispatcher", "Emergency SMS dispatched to $phone")
            } catch (e: Exception) {
                Log.e("OfflineSmsDispatcher", "Failed to send SMS to $phone: ${e.message}")
            }
        }
    }
}
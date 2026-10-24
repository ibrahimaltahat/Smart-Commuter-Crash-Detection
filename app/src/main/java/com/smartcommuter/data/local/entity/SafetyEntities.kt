package com.smartcommuter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trusted_contacts")
data class TrustedContactEntity(
    @PrimaryKey(autoGenerate = true) val contactId: Int = 0,
    val fullName: String,
    val phoneNumber: String,
    val priorityOrder: Int, // 1 to 5
    val receiveSms: Boolean = true,
    val receiveLiveLink: Boolean = true
)

@Entity(tableName = "trip_sessions")
data class TripSessionEntity(
    @PrimaryKey val tripId: String, // UUID
    val startTimestamp: Long,
    val endTimestamp: Long? = null,
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val encodedPolyline: String, // Cached from Google Directions API
    val corridorRadiusMeters: Float = 500f,
    val tripStatus: String // ACTIVE, COMPLETED, SOS_ESCALATED
)

@Entity(
    tableName = "location_breadcrumbs",
    foreignKeys = [ForeignKey(
        entity = TripSessionEntity::class,
        parentColumns = ["tripId"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tripId")]
)
data class LocationBreadcrumbEntity(
    @PrimaryKey(autoGenerate = true) val pointId: Long = 0,
    val tripId: String,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val bearing: Float,
    val timestamp: Long,
    val isSyncedToCloud: Boolean = false
)

@Entity(
    tableName = "incident_events",
    foreignKeys = [ForeignKey(
        entity = TripSessionEntity::class,
        parentColumns = ["tripId"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tripId")]
)
data class IncidentEventEntity(
    @PrimaryKey val incidentId: String, // UUID
    val tripId: String,
    val anomalyType: String, // CRASH_IMPACT, ROUTE_DEVIATION, UNUSUAL_STOP, SILENT_MANUAL
    val peakGForce: Float,
    val preImpactSpeedKmh: Float,
    val triggerLat: Double,
    val triggerLng: Double,
    val wasCancelledByPin: Boolean,
    val timestamp: Long
)

@Entity(
    tableName = "audio_blackbox_logs",
    foreignKeys = [ForeignKey(
        entity = IncidentEventEntity::class,
        parentColumns = ["incidentId"],
        childColumns = ["incidentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("incidentId")]
)
data class AudioBlackboxEntity(
    @PrimaryKey(autoGenerate = true) val audioId: Int = 0,
    val incidentId: String,
    val localFilePath: String,
    val cloudStorageUrl: String? = null,
    val durationSec: Int = 10,
    val uploadStatus: String = "PENDING", // PENDING, UPLOADED, FAILED
    val recordedAt: Long
)
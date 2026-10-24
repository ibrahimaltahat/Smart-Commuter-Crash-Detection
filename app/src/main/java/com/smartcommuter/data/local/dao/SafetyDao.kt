package com.smartcommuter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.smartcommuter.data.local.entity.AudioBlackboxEntity
import com.smartcommuter.data.local.entity.IncidentEventEntity
import com.smartcommuter.data.local.entity.TrustedContactEntity

@Dao
interface SafetyDao {

    @Query("SELECT * FROM trusted_contacts ORDER BY priorityOrder ASC LIMIT 5")
    suspend fun getTrustedContacts(): List<TrustedContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: TrustedContactEntity)

    @Query("DELETE FROM trusted_contacts WHERE contactId = :id")
    suspend fun deleteContact(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: IncidentEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioLog(audioLog: AudioBlackboxEntity): Long

    @Query("UPDATE audio_blackbox_logs SET uploadStatus = :status, cloudStorageUrl = :url WHERE audioId = :id")
    suspend fun updateAudioSyncStatus(id: Long, status: String, url: String?)
}
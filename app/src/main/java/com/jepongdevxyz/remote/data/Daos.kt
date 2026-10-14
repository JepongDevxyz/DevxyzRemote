package com.jepongdevxyz.remote.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun sessions(): Flow<List<SessionEntity>>

    @Insert
    suspend fun insert(s: SessionEntity): Long

    @Query("UPDATE sessions SET durationSec = :sec WHERE id = :id")
    suspend fun updateDuration(id: Long, sec: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY ts ASC")
    fun messages(sessionId: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(m: MessageEntity): Long
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun devices(): Flow<List<DeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: DeviceEntity)

    @Query("UPDATE devices SET name = :name WHERE deviceId = :deviceId")
    suspend fun rename(deviceId: String, name: String)

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun remove(deviceId: String)
}

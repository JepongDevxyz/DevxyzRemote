package com.jepongdevxyz.remote.data

import android.content.Context
import com.jepongdevxyz.remote.core.Prefs
import kotlinx.coroutines.flow.Flow

/**
 * Single access point for session history, chat messages and known devices.
 * Obtain via [get]; the instance is a process-wide singleton.
 */
class SessionRepository private constructor(private val db: AppDatabase) {

    companion object {
        @Volatile
        private var instance: SessionRepository? = null

        fun get(context: Context): SessionRepository {
            Prefs.init(context)
            return instance ?: synchronized(this) {
                instance ?: SessionRepository(AppDatabase.get(context)).also { instance = it }
            }
        }
    }

    /** All past sessions, newest first. */
    fun sessions(): Flow<List<SessionEntity>> = db.sessionDao().sessions()

    suspend fun deleteSession(id: Long) = db.sessionDao().delete(id)

    suspend fun clearSessions() = db.sessionDao().clear()

    /** Chat history of one session, oldest first. */
    fun messages(sessionId: Long): Flow<List<MessageEntity>> =
        db.messageDao().messages(sessionId)

    suspend fun insertMessage(m: MessageEntity) {
        db.messageDao().insert(m)
    }

    /** Known devices, most recently seen first. */
    fun knownDevices(): Flow<List<DeviceEntity>> = db.deviceDao().devices()

    suspend fun upsertDevice(d: DeviceEntity) = db.deviceDao().upsert(d)

    suspend fun renameDevice(deviceId: String, name: String) =
        db.deviceDao().rename(deviceId, name)

    suspend fun removeDevice(deviceId: String) = db.deviceDao().remove(deviceId)

    /** Used by SessionCoordinator when a session goes live. */
    internal suspend fun insertSession(s: SessionEntity): Long =
        db.sessionDao().insert(s)

    /** Used by SessionCoordinator when a session ends. */
    internal suspend fun updateSessionDuration(id: Long, sec: Long) =
        db.sessionDao().updateDuration(id, sec)
}

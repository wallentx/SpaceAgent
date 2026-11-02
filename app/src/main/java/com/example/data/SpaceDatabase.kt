package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Query("SELECT * FROM spaces ORDER BY isFavorite DESC, likes DESC")
    fun getAllSpaces(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM spaces WHERE id = :id LIMIT 1")
    suspend fun getSpaceById(id: String): SpaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpaces(spaces: List<SpaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpace(space: SpaceEntity)

    @Update
    suspend fun updateSpace(space: SpaceEntity)

    @Query("UPDATE spaces SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Delete
    suspend fun deleteSpace(space: SpaceEntity)

    @Query("SELECT COUNT(*) FROM spaces")
    suspend fun getSpaceCount(): Int
}

@Dao
interface AgentSessionDao {
    @Query("SELECT * FROM agent_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: String): AgentSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: AgentSessionEntity)

    @Update
    suspend fun updateSession(session: AgentSessionEntity)

    @Delete
    suspend fun deleteSession(session: AgentSessionEntity)

    @Query("DELETE FROM agent_sessions")
    suspend fun clearAll()
}

@Database(entities = [SpaceEntity::class, AgentSessionEntity::class], version = 1, exportSchema = false)
abstract class SpaceDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun agentSessionDao(): AgentSessionDao

    companion object {
        @Volatile
        private var INSTANCE: SpaceDatabase? = null

        fun getDatabase(context: Context): SpaceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SpaceDatabase::class.java,
                    "space_agent_db"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

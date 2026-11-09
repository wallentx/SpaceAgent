package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.HfSpace

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val id: String, // e.g. "deepseek-ai/DeepSeek-R1"
    val author: String,
    val name: String,
    val title: String,
    val description: String,
    val sdk: String, // "gradio", "docker", "streamlit", "static"
    val category: String, // "Reasoning", "Code", "Image", "Text", "Audio", "Agent"
    val likes: Int,
    val endpointUrl: String,
    val isFavorite: Boolean = false,
    val isCurated: Boolean = false,
    val demoPrompt: String = "",
    val lastUsedAt: Long = 0L
)

@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val goal: String,
    val spaceId: String,
    val spaceTitle: String,
    val agentType: String, // "REASONING", "CODER", "IMAGE_STUDIO", "GENERAL"
    val status: String, // "IDLE", "PLANNING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"
    val createdAt: Long,
    val completedAt: Long? = null,
    val finalOutput: String? = null,
    val imageUrl: String? = null,
    val stepsJson: String = "[]",
    val executionDurationMs: Long = 0L
)

internal fun SpaceEntity.toHfSpace() = HfSpace(
    id = id, author = author, name = name, title = title, description = description,
    sdk = sdk, category = category, likes = likes, endpointUrl = endpointUrl,
    isFavorite = isFavorite, isCurated = isCurated, demoPrompt = demoPrompt
)

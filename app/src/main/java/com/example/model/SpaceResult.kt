package com.example.model

data class SpaceResult(
    val spaceId: String,
    val apiName: String,
    val outputs: List<SpaceResultOutput>,
    val rawResponse: String
)

data class SpaceResultOutput(val label: String, val content: SpaceResultContent)

data class SpaceExecutionState(
    val spaceId: String,
    val apiName: String,
    val status: SessionStatus,
    val message: String,
    val result: SpaceResult? = null,
    val details: List<String> = emptyList()
)

enum class ResultMediaKind { IMAGE, VIDEO, AUDIO, FILE }

data class SpaceResultFile(
    val url: String,
    val name: String,
    val kind: ResultMediaKind,
    val mimeType: String? = null,
    val sizeBytes: Long? = null
)

sealed interface SpaceResultContent {
    data class Text(val value: String) : SpaceResultContent
    data class File(val file: SpaceResultFile) : SpaceResultContent
    data class Group(val items: List<SpaceResultContent>) : SpaceResultContent
    data class Table(val headers: List<String>, val rows: List<List<String>>) : SpaceResultContent
    data class Json(val value: String) : SpaceResultContent
    data object Empty : SpaceResultContent
}

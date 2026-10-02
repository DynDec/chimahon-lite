package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private const val RETAINED_BY_RELINK = "local_relink_retained"

val Chapter.retainedByRelink: Boolean
    get() = (memo[RETAINED_BY_RELINK] as? JsonPrimitive)?.booleanOrNull == true

fun JsonObject.withRelinkRetention(retained: Boolean): JsonObject = JsonObject(
    if (retained) this + (RETAINED_BY_RELINK to JsonPrimitive(true)) else this - RETAINED_BY_RELINK,
)

package com.stt.benchmark.data

import android.content.Context
import android.content.Intent

/**
 * 마지막 완료 결과를 다시 여는 데 필요한 불투명 type/ID만 앱 private preferences에 보존한다.
 * 전사 원문, 파일 경로, note, 계정 정보는 이 저장소의 입력 계약에 존재하지 않는다.
 */
class CompletedResultTargetStore(context: Context) {
    enum class Type { TRANSCRIPTION_SESSION, RECORDING_GROUP }

    class Target private constructor(
        val type: Type,
        val id: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Target && other.type == type && other.id == id

        override fun hashCode(): Int = 31 * type.hashCode() + id.hashCode()

        override fun toString(): String = "CompletedResultTargetStore.Target(type=$type)"

        companion object {
            fun create(type: Type, id: String): Target? = id
                .takeIf(SAFE_ID::matches)
                ?.let { Target(type, it) }
        }
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(target: Target): Boolean = preferences.edit()
        .putString(KEY_TYPE, target.type.name)
        .putString(KEY_ID, target.id)
        .commit()

    fun load(): Target? {
        val typeName = preferences.getString(KEY_TYPE, null)
        val id = preferences.getString(KEY_ID, null)
        if (typeName == null && id == null) return null
        val target = Type.entries.firstOrNull { it.name == typeName }
            ?.let { type -> Target.create(type, id.orEmpty()) }
        if (target == null) clear()
        return target
    }

    fun clear(): Boolean = preferences.edit().clear().commit()

    fun clearIfMatches(target: Target): Boolean {
        val current = load() ?: return false
        if (current != target) return false
        clear()
        return true
    }

    companion object {
        private const val PREFERENCES_NAME = "completed_result_target"
        internal const val KEY_TYPE = "type"
        internal const val KEY_ID = "id"
        internal val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}

/** 알림에서 MainActivity로 전달하는 완료 결과 allowlist 계약. */
object CompletedResultLaunchContract {
    const val ACTION_OPEN_COMPLETED_RESULT = "com.stt.benchmark.action.OPEN_COMPLETED_RESULT"
    internal const val EXTRA_TARGET_TYPE = "completed_result_type"
    internal const val EXTRA_TARGET_ID = "completed_result_id"

    fun write(intent: Intent, target: CompletedResultTargetStore.Target): Intent = intent
        .setAction(ACTION_OPEN_COMPLETED_RESULT)
        .putExtra(EXTRA_TARGET_TYPE, target.type.name)
        .putExtra(EXTRA_TARGET_ID, target.id)

    fun read(intent: Intent?): CompletedResultTargetStore.Target? {
        if (intent?.action != ACTION_OPEN_COMPLETED_RESULT) return null
        val typeName = intent.getStringExtra(EXTRA_TARGET_TYPE)
        val id = intent.getStringExtra(EXTRA_TARGET_ID).orEmpty()
        val type = CompletedResultTargetStore.Type.entries.firstOrNull { it.name == typeName }
            ?: return null
        return CompletedResultTargetStore.Target.create(type, id)
    }

    fun requestCode(target: CompletedResultTargetStore.Target): Int =
        ("${target.type.name}:${target.id}".hashCode() and Int.MAX_VALUE)
}

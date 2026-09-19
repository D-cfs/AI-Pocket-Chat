package com.situ.aichat.prompt.diary

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 日记交互「睡觉待办队列」——照 [com.situ.aichat.moments.MomentPendingInteractionStore] 的模式。
 *
 * 角色睡觉时产生的日记评论/回复/点赞先入队（SharedPreferences + JSON），
 * 等角色醒来后由 [DiaryGenerationCoordinator.drainPendingDiaryInteractions] 消费。
 *
 * 与朋友圈版一样，用 object 单例；调用方传 Context（内部用 applicationContext）。
 */
object DiaryPendingInteractionStore {
    private const val PREFS = "diary_pending_interactions"
    private const val KEY_QUEUE = "DiaryPendingInteractions"
    private const val TAG = "DiaryPending"

    private val json = Json { ignoreUnknownKeys = true }

    /** 一条待办交互。 */
    @Serializable
    data class PendingDiaryInteraction(
        /** 目标日记 uuid。 */
        val entryUuid: String,
        /** 执行动作的角色 uuid。 */
        val characterUuid: String,
        /** "comment" = 首评, "reply" = 回应用户回复, "reaction" = 点赞。 */
        val actionType: String,
        /** reply 时才有：根评论 id。 */
        val rootCommentId: String? = null,
        /** 入队时间，用于排序/排查。 */
        val queuedAtMillis: Long,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 入队。去重键：(entryUuid, characterUuid, actionType, rootCommentId)。
     * 重复入队是 no-op——worker 重试安全。
     */
    fun add(
        context: Context,
        entryUuid: String,
        characterUuid: String,
        actionType: String,
        rootCommentId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val queue = load(context)
        if (queue.any {
                it.entryUuid == entryUuid &&
                    it.characterUuid == characterUuid &&
                    it.actionType == actionType &&
                    it.rootCommentId == rootCommentId
            }
        ) return
        save(
            context,
            queue + PendingDiaryInteraction(
                entryUuid = entryUuid,
                characterUuid = characterUuid,
                actionType = actionType,
                rootCommentId = rootCommentId,
                queuedAtMillis = nowMillis,
            ),
        )
    }

    /** 读队列；缺失/损坏 → 空列表（对齐朋友圈版 `?? []` 的容错）。 */
    fun load(context: Context): List<PendingDiaryInteraction> {
        val raw = prefs(context).getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            Log.w(TAG, "queue decode failed; resetting", e)
            emptyList()
        }
    }

    /** 写队列；空队列删除 key。 */
    fun save(context: Context, queue: List<PendingDiaryInteraction>) {
        val editor = prefs(context).edit()
        if (queue.isEmpty()) editor.remove(KEY_QUEUE)
        else editor.putString(KEY_QUEUE, json.encodeToString(queue))
        editor.apply()
    }
}

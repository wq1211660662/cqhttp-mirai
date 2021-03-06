package tech.mihoyo.mirai

import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.event.events.MemberJoinRequestEvent
import net.mamoe.mirai.event.events.NewFriendRequestEvent
import net.mamoe.mirai.message.data.recall
import tech.mihoyo.mirai.data.common.*
import tech.mihoyo.mirai.web.queue.CacheSourceQueue
import tech.mihoyo.mirai.util.cqMessageToMessageChains
import tech.mihoyo.mirai.web.queue.CacheRequestQueue

@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
class MiraiApi(val bot: Bot) {
    // Store temp contact information for sending messages
    // QQ : GroupId
    val cachedTempContact: MutableMap<Long, Long> = mutableMapOf()
    val cacheRequestQueue = CacheRequestQueue()
    private val cachedSourceQueue = CacheSourceQueue()

    suspend fun cqSendMessage(params: Map<String, JsonElement>): CQResponseDTO {
        if (params.contains("message_type")) {
            when (params["message_type"]?.content) {
                "private" -> return cqSendPrivateMessage(params)
                "group" -> return cqSendGroupMessage(params)
            }
        } else {
            when {
                params["user_id"] != null -> return cqSendPrivateMessage(params)
                params["group_id"] != null -> return cqSendGroupMessage(params)
                params["discuss_id"] != null -> return cqSendGroupMessage(params)
            }
        }
        return CQResponseDTO.CQInvalidRequest()
    }

    suspend fun cqSendGroupMessage(params: Map<String, JsonElement>): CQResponseDTO {
        val targetGroupId = params["group_id"]!!.long
        val raw = params["auto_escape"]?.booleanOrNull ?: false
        val messages = params["message"]

        val group = bot.getGroup(targetGroupId)
        cqMessageToMessageChains(bot, group, messages, raw)?.let {
            val receipt = group.sendMessage(it)
            cachedSourceQueue.add(receipt.source)
            return CQResponseDTO.CQMessageResponse(receipt.source.id)
        }
        return CQResponseDTO.CQInvalidRequest()
    }

    suspend fun cqSendPrivateMessage(params: Map<String, JsonElement>): CQResponseDTO {
        val targetQQId = params["user_id"]!!.long
        val raw = params["auto_escape"]?.booleanOrNull ?: false
        val messages = params["message"]

        val contact = try {
            bot.getFriend(targetQQId)
        } catch (e: NoSuchElementException) {
            val fromGroupId = cachedTempContact[targetQQId]
                ?: bot.groups.find { group -> group.members.contains(targetQQId) }?.id
            bot.getGroup(fromGroupId!!)[targetQQId]
        }

        cqMessageToMessageChains(bot, contact, messages, raw)?.let {
            val receipt = contact.sendMessage(it)
            cachedSourceQueue.add(receipt.source)
            return CQResponseDTO.CQMessageResponse(receipt.source.id)
        }
        return CQResponseDTO.CQInvalidRequest()
    }

    suspend fun cqDeleteMessage(params: Map<String, JsonElement>): CQResponseDTO {
        val messageId = params["message_id"]?.intOrNull
        messageId?.let {
            cachedSourceQueue[it].recall()
            CQResponseDTO.CQGeneralSuccess()
        }
        return CQResponseDTO.CQInvalidRequest()
    }


    suspend fun cqSetGroupKick(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val memberId = params["user_id"]?.long
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].kick()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetGroupBan(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val memberId = params["user_id"]?.long
        val duration = params["duration"]?.int ?: 30 * 60
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].mute(duration)
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetWholeGroupBan(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val enable = params["enable"]?.booleanOrNull ?: true
        return if (groupId != null) {
            bot.getGroup(groupId).settings.isMuteAll = enable
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetGroupCard(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val memberId = params["user_id"]?.long
        val card = params["card"]?.contentOrNull ?: ""
        val enable = params["enable"]?.booleanOrNull ?: true
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].nameCard = card
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetGroupLeave(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val dismiss = params["enable"]?.booleanOrNull ?: false
        return if (groupId != null) {
            // Not supported
            if (dismiss) return CQResponseDTO.CQMiraiFailure()

            bot.getGroup(groupId).quit()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetGroupSpecialTitle(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val memberId = params["user_id"]?.long
        val specialTitle = params["special_title"]?.contentOrNull ?: ""
        val duration = params["duration"]?.int ?: -1  // Not supported
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].specialTitle = specialTitle
            CQResponseDTO.CQMiraiFailure()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetFriendAddRequest(params: Map<String, JsonElement>): CQResponseDTO {
        val flag = params["flag"]?.contentOrNull
        val approve = params["approve"]?.booleanOrNull ?: true
        val remark = params["remark"]?.contentOrNull
        return if (flag != null) {
            val event = cacheRequestQueue[flag.toLongOrNull()]
            if (event is NewFriendRequestEvent)
                if (approve) event.accept() else event.reject()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetGroupAddRequest(params: Map<String, JsonElement>): CQResponseDTO {
        val flag = params["flag"]?.contentOrNull
        val type = params["type"]?.contentOrNull
        val subType = params["sub_type"]?.contentOrNull
        val approve = params["approve"]?.booleanOrNull ?: true
        val reason = params["reason"]?.contentOrNull

        return if (flag != null) {
            val event = cacheRequestQueue[flag.toLongOrNull()]
            if (event is MemberJoinRequestEvent)
                if (approve) event.accept() else event.reject()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqGetLoginInfo(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQLoginInfo(bot.id, bot.nick)
    }

    fun cqGetFriendList(params: Map<String, JsonElement>): CQResponseDTO {
        val cqFriendList = mutableListOf<CQFriendData>()
        bot.friends.forEach { friend ->
            cqFriendList.add(CQFriendData(friend.id, friend.nick, ""))
        }
        return CQResponseDTO.CQFriendList(cqFriendList)
    }

    fun cqGetGroupList(params: Map<String, JsonElement>): CQResponseDTO {
        val cqGroupList = mutableListOf<CQGroupData>()
        bot.groups.forEach { group ->
            cqGroupList.add(CQGroupData(group.id, group.name))
        }
        return CQResponseDTO.CQGroupList(cqGroupList)
    }

    /**
     * 获取群信息
     * 不支持获取群容量, 返回0
     */
    fun cqGetGroupInfo(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val noCache = params["no_cache"]?.booleanOrNull ?: false

        return if (groupId != null) {
            val group = bot.getGroup(groupId)
            CQResponseDTO.CQGroupInfo(group.id, group.name, group.members.size + 1, 0)
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqGetGroupMemberInfo(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val memberId = params["user_id"]?.long
        val noCache = params["no_cache"]?.booleanOrNull ?: false

        return if (groupId != null && memberId != null) {
            val member = bot.getGroup(groupId)[memberId]
            CQResponseDTO.CQMemberInfo(CQMemberInfoData(member))
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqGetGroupMemberList(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val cqGroupMemberListData = mutableListOf<CQMemberInfoData>()
        return if (groupId != null) {
            val members = bot.getGroup(groupId).members
            members.forEach { member -> cqGroupMemberListData.add(CQMemberInfoData(member)) }
            CQResponseDTO.CQMemberList(cqGroupMemberListData)
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    // https://github.com/richardchien/coolq-http-api/blob/master/src/cqhttp/plugins/web/http.cpp#L375
    suspend fun cqHandleQuickOperation(params: Map<String, JsonElement>): CQResponseDTO {
        try {
            val context = params["context"]?.jsonObject
            val operation = params["operation"]?.jsonObject
            val postType = context?.get("post_type")?.content

            if (postType == "message") {
                val messageType = context["message_type"]?.content

                var reply = operation?.get("reply")?.content
                if (reply != null) {
                    if (messageType == "group" && operation?.get("at_sender")?.booleanOrNull == true) {
                        context["user_id"]?.longOrNull?.apply {
                            reply = "[CQ:at,qq=$this] $reply"
                        }
                    }
                    val nextCallParams = context.toMutableMap()
                    nextCallParams["message"] = JsonPrimitive(reply)
                    return cqSendMessage(nextCallParams)
                }

                if (messageType == "group") {
                    // TODO: 备忘, 暂未支持
                    val isAnonymous = false
                    if (operation?.get("delete")?.booleanOrNull == true) {
                        return cqDeleteMessage(context)
                    }
                    if (operation?.get("kick")?.booleanOrNull == true) {
                        return cqSetGroupKick(context)
                    }
                    if (operation?.get("ban")?.booleanOrNull == true) {
                        @Suppress("ConstantConditionIf")
                        (return if (isAnonymous) {
                            cqSetAnonymousBan(context)
                        } else {
                            cqSetGroupBan(context)
                        })
                    }
                }
            } else if (postType == "request") {
                val requestType = context["request_type"]?.content
                val approveOpt = operation?.get("approve")?.booleanOrNull ?: false
                val nextCallParams = context.toMutableMap()
                nextCallParams["approve"] = JsonPrimitive(approveOpt)
                nextCallParams["remark"] = JsonPrimitive(operation?.get("remark")?.contentOrNull)
                nextCallParams["reason"] = JsonPrimitive(operation?.get("reason")?.contentOrNull)
                if (requestType == "friend") {
                    return cqSetFriendAddRequest(nextCallParams)
                } else if (requestType == "group") {
                    return cqSetGroupAddRequest(nextCallParams)
                }
            }
            return CQResponseDTO.CQInvalidRequest()
        } catch (e: Exception) {
            return CQResponseDTO.CQPluginFailure()
        }
    }

    /**
     * Getting image path, not supported for now
     */
    fun cqGetImage(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQPluginFailure()
    }

    fun cqCanSendImage(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQCanSendImage()
    }

    fun cqCanSendRecord(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQCanSendRecord()
    }

    fun cqGetStatus(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQPluginStatus(CQPluginStatusData(online = bot.isActive, good = bot.isActive))
    }

    fun cqGetVersionInfo(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQVersionInfo(
            CQVersionInfoData(
                coolq_directory = PluginBase.dataFolder.absolutePath,
                plugin_version = "",
                plugin_build_number = ""
            )
        )
    }

    fun cqSetRestartPlugin(params: Map<String, JsonElement>): CQResponseDTO {
        val delay = params["delay"]?.int ?: 0
        return CQResponseDTO.CQGeneralSuccess()
    }

    fun cqCleanDataDir(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQGeneralSuccess()
    }

    fun cqCleanPluginLog(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQGeneralSuccess()
    }

    fun cqGetCookies(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetCSRFToken(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetRecord(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetCredentials(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetStrangerInfo(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSendDiscussMessage(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSetGroupAnonymous(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val enable = params["enable"]?.booleanOrNull ?: true
        return if (groupId != null) {
            // Not supported
            // bot.getGroup(groupId).settings.isAnonymousChatEnabled = enable
            CQResponseDTO.CQMiraiFailure()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetGroupAdmin(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.long
        val memberId = params["user_id"]?.long
        val enable = params["enable"]?.booleanOrNull ?: true
        return if (groupId != null && memberId != null) {
            // Not supported
            // bot.getGroup(groupId)[memberId].permission = if (enable) MemberPermission.ADMINISTRATOR else MemberPermission.MEMBER
            CQResponseDTO.CQMiraiFailure()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }


    fun cqSetDiscussLeave(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSendLike(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSetAnonymousBan(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }
}
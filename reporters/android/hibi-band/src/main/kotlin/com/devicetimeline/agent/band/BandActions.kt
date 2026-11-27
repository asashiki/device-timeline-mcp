// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import android.content.Context
import com.example.healthapp.data.protocol.WearPacketChannel
import com.example.healthapp.data.protocol.proto.BandControls as Wire
import com.example.healthapp.data.protocol.proto.WearPacket
import com.example.healthapp.data.protocol.system.DeviceInfoRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime

internal data class ControlResult(val status:String,val detail:String)

internal object BandActions {
    private val queries=setOf("battery","device-info","alarms","reminders","world-clocks","sleep-schedule",
        "wear-state","workout-types","vibration-patterns","screen-items","widgets","notification-screen",
        "watchfaces","weather-locations","camera-status","heart-settings","oxygen-settings","stress-settings")
    suspend fun execute(ctx:Context,ch:WearPacketChannel,action:String,args:JSONObject):ControlResult {
        if(action=="battery"){
            val battery=DeviceInfoRequest(ch).fetchStatus(6000).battery
            return ControlResult("confirmed","手环电量 ${battery.capacity}%")
        }
        if(action=="device-info"){
            val info=DeviceInfoRequest(ch).fetchInfo(6000)
            return ControlResult("confirmed","${info.model} · ${info.firmwareVersion}")
        }
        if (action == "wake-at") return setWakeAlarm(ch, Instant.parse(args.getString("at")))
        if(action=="buzz" || action=="wake-ring"){
            // The find command has a stop counterpart. Always stop after a short burst.
            val sec = if (action == "wake-ring") 30 else args.optInt("seconds",1).coerceIn(1,6)
            ch.send(Wire.find(true))
            try { delay(sec*1000L) } finally { withContext(NonCancellable){withTimeout(2500){ch.send(Wire.find(false))}} }
            return ControlResult("sent","已发送震动和停止指令")
        }
        val id=args.optInt("id",-1).takeIf { it in 0..255 }
        val packet=when(action){
            "find-stop"->Wire.find(false)
            "notify"->Wire.notification(args.optInt("notificationId",System.currentTimeMillis().toInt()),args.optString("title","Hibi"),args.getString("text"),ctx.packageName)
            "alarm-create","alarm-edit"->Wire.alarm(args.getInt("hour"),args.getInt("minute"),args.optInt("repeatMask",0),
                if(action.endsWith("edit"))requireNotNull(id) else null,args.optBoolean("enabled",true))
            "alarm-delete","reminder-delete"->Wire.remove(action=="reminder-delete",requireNotNull(id))
            "reminder-create","reminder-edit"->Wire.reminder(Instant.parse(args.getString("at")),args.getString("title"),
                if(action.endsWith("edit"))requireNotNull(id) else null)
            "sleep-schedule-set"->Wire.sleepSchedule(args.getBoolean("enabled"),args.getInt("hour"),args.getInt("minute"),args.getInt("endHour"),args.getInt("endMinute"))
            "sync-clock"->Wire.syncClock(ZonedDateTime.now(),android.text.format.DateFormat.is24HourFormat(ctx))
            "notification-screen-set"->Wire.screenOnNotification(args.getBoolean("enabled"))
            "camera-remote-set"->Wire.camera(args.getBoolean("enabled"))
            "language-set"->Wire.language(args.getString("code"))
            else->Wire.get(action)
        }
        // Notifications on the 9 Pro are one-way. A completed send is not a display receipt.
        if (action == "notify") {
            ch.send(packet)
            return ControlResult("sent", "已发到手环")
        }
        val reply=withTimeoutOrNull(6200){ch.request(packet,7000){it.type==packet.type && it.id==packet.id}}
        if(reply==null)return if(action in queries)ControlResult("unconfirmed","手环未返回此功能的数据") else ControlResult("sent","已发送，手环未回执")
        if((reply.errorCode?:0)!=0)return ControlResult("rejected","手环返回 "+WearPacket.errorCodeName(reply.errorCode!!))
        val data=reply.scheduleRaw?:reply.systemRaw?:reply.notificationRaw?:reply.fitnessRaw?:reply.watchfaceRaw?:reply.weatherRaw
        if(action in queries && data==null)return ControlResult("unconfirmed","手环未返回此功能的数据")
        val answer=if(data!=null) summarize(action,data) else null
        if(action in queries && answer==null)return ControlResult("unconfirmed","收到应答，当前固件未提供可读结果")
        return ControlResult("confirmed",answer?:"手环已回执")
    }
    private suspend fun readAlarms(ch: WearPacketChannel): Wire.AlarmList? {
        val query = Wire.get("alarms")
        val reply = withTimeoutOrNull(2600) {
            ch.request(query, 3000) { it.type == query.type && it.id == query.id }
        } ?: return null
        if ((reply.errorCode ?: 0) != 0) return null
        return reply.scheduleRaw?.let(Wire::alarmList)
    }

    private suspend fun setWakeAlarm(ch: WearPacketChannel, target: Instant): ControlResult {
        val zone = java.time.ZoneId.systemDefault()
        val local = target.atZone(zone)
        // A one-shot alarm has no date. Refuse a different next occurrence or a repeated DST hour.
        fun canSet(): Boolean {
            val now = Instant.now().atZone(zone)
            val hourMinute = local.toLocalTime()
            var date = now.toLocalDate()
            if (!hourMinute.isAfter(now.toLocalTime())) date = date.plusDays(1)
            val next = date.atTime(hourMinute)
            return local.second == 0 && local.nano == 0 && zone.rules.getValidOffsets(next).size == 1 &&
                next.atZone(zone).toInstant() == target && target.isAfter(now.toInstant().plusSeconds(10))
        }
        if (!canSet()) return ControlResult("rejected", "这次手环闹钟无法在指定日期和时间响起")
        val before = readAlarms(ch) ?: return ControlResult("unconfirmed", "未能读取手环闹钟，尚未写入")
        val time = local.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        fun matches(slot: Wire.AlarmSlot) = slot.hour == local.hour && slot.minute == local.minute &&
            slot.enabled && slot.repeat == 0 && !slot.smart
        before.slots.firstOrNull(::matches)?.let {
            return ControlResult("confirmed", "$time 已有启用的一次闹钟 #${it.id}")
        }
        val max = before.max
        if (max != null && before.slots.size >= max)
            return setWakeReminder(ch, target, time)
        if (!canSet()) return ControlResult("expired", "已过写入时间")
        val create = Wire.alarm(local.hour, local.minute, 0)
        val ack = withTimeoutOrNull(2200) {
            ch.request(create, 2500) { it.type == create.type && it.id == create.id }
        }
        if ((ack?.errorCode ?: 0) != 0)
            return ControlResult("rejected", "手环拒绝设置闹钟")
        // Firmware may acknowledge the transport before its list is updated, or omit the create ack.
        val oldIds = before.slots.map { it.id }.toSet()
        repeat(2) {
            val created = readAlarms(ch)?.slots?.singleOrNull { it.id !in oldIds && matches(it) }
            if (created != null) return ControlResult("confirmed", "$time 已存入手环，一次闹钟 #${created.id}")
            if (it == 0) delay(300)
        }
        return ControlResult("unconfirmed", "已请求设置，但未从手环读回对应闹钟")
    }
    private suspend fun setWakeReminder(ch: WearPacketChannel, target: Instant, time: String): ControlResult {
        val title = "Hibi 起床"
        val packet = Wire.reminder(target, title)
        val ack = withTimeoutOrNull(2200) { ch.request(packet, 2500) { it.type == packet.type && it.id == packet.id } }
        if ((ack?.errorCode ?: 0) != 0) return ControlResult("rejected", "闹钟已满，手环也拒绝了起床日程")
        val query = Wire.get("reminders")
        repeat(2) {
            val reply = withTimeoutOrNull(2200) { ch.request(query, 2500) { it.type == query.type && it.id == query.id } }
            val reminder = reply?.takeIf { (it.errorCode ?: 0) == 0 }?.scheduleRaw?.let(Wire::reminders)
                ?.firstOrNull { it.time == target && it.title == title && it.once }
            if (reminder != null) return ControlResult("wake-reminder", "已存入手环日程 #${reminder.id}，$time 起床；闹钟已满，到点连着手机可另震 30 秒")
            if (it == 0) delay(300)
        }
        return ControlResult("unconfirmed", "闹钟已满，手环尚未回传这条起床日程")
    }
    private fun summarize(action:String,raw:ByteArray):String? {
        if(action in setOf("alarms","reminders")){
            val rows=Wire.scheduleRows(raw,action=="reminders")?:return null
            return rows.joinToString("\n").ifBlank { if(action=="alarms")"没有闹钟" else "没有提醒" }
        }
        val f=Wire.fields(raw)
        return when(action){
            "watchfaces" -> f.nested(1).firstOrNull()?.let { list ->
                list.nested(1).joinToString("\n") { face ->
                    (face.text(2) ?: face.text(1) ?: "未命名表盘") + if (face.num(3) == 1L) "（使用中）" else ""
                }.ifBlank { "没有已安装表盘" }
            }
            "weather-locations" -> f.nested(4).firstOrNull()?.let { list ->
                list.nested(1).joinToString("、") { it.text(2) ?: it.text(3) ?: "未命名地点" }.ifBlank { "没有天气地点" }
            }
            "world-clocks"->f.nested(11).firstOrNull()?.bytes?.get(1).orEmpty().joinToString("、"){it.toString(Charsets.UTF_8)}.ifBlank {if(f.bytes.containsKey(11))"未设置世界时钟" else return null}
            "wear-state"->f.nested(48).firstOrNull()?.let { row->listOfNotNull(row.num(2)?.let{"电量 ${it}%"},row.num(3)?.let{if(it==1L)"已佩戴" else "未佩戴"},row.num(4)?.let{if(it==1L)"睡了" else "醒着"}).joinToString(" · ").ifBlank {return null} }
            "sleep-schedule"->f.nested(9).firstOrNull()?.let {if(it.num(1)==1L)"睡眠计划已开启" else "睡眠计划已关闭"}
            "camera-status"->f.nested(15).firstOrNull()?.let {if(it.num(1)==1L)"相机遥控已开启" else "相机遥控已关闭"}
            "notification-screen"->f.num(7)?.let{if(it==1L)"通知亮屏已开启" else "通知亮屏已关闭"}
            "vibration-patterns"->f.nested(38).firstOrNull()?.let {row->"已存震动样式 "+row.nested(3).size+" 个"}
            "workout-types"->f.nested(14).firstOrNull()?.let{"手环运动项目 "+it.nested(1).size+" 项"}
            "screen-items"->f.nested(10).firstOrNull()?.let{"表上菜单 "+it.nested(1).size+" 项"}
            "widgets"->f.nested(28).firstOrNull()?.let{"手环已返回组件布局"}
            "heart-settings" -> f.nested(8).singleOrNull()?.let { settings ->
                val disabled = settings.num(1)
                val interval = settings.num(2)
                val rate = when {
                    disabled == 1L -> "自动心率已关闭"
                    disabled != null && disabled != 0L -> "自动心率状态未识别（$disabled）"
                    interval == 0L -> "心率：智能监测"
                    interval != null -> "心率：每 $interval 分钟"
                    disabled == 0L -> "自动心率已开启，间隔未报告"
                    else -> "自动心率未报告"
                }
                // wear_fitness.proto HeartRate. AdvancedMonitoring.enabled is a bool;
                // breathingScore and sleepRespiratoryRate have distinct unsupported/off states.
                val advanced = when (val flag = settings.nested(5).singleOrNull()?.num(1)) {
                    null -> "未报告"
                    0L -> "关闭"
                    1L -> "开启"
                    else -> "未识别（$flag）"
                }
                fun breathing(flag: Long?) = when (flag) {
                    null -> "未报告"
                    0L -> "不支持"
                    1L -> "开启"
                    2L -> "关闭"
                    else -> "未识别（$flag）"
                }
                listOf(rate, "高精度睡眠检测：$advanced", "睡眠呼吸率检测：${breathing(settings.num(11))}",
                    "睡眠呼吸质量检测：${breathing(settings.num(9))}").joinToString("；")
            }
            "oxygen-settings"->f.nested(7).firstOrNull()?.let { settings ->
                when(settings.num(2)){0L->"血氧监测已关闭";1L->"仅睡眠时监测血氧";2L->"全天血氧监测";else->null}
            }
            "stress-settings"->f.nested(10).firstOrNull()?.num(1)?.let { if(it==1L)"全天压力监测已开启" else "全天压力监测已关闭" }
            else->null
        }
    }
}

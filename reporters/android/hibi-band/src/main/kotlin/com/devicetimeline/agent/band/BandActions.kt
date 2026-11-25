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
        if(action=="buzz"){
            // The find command has a stop counterpart. Always stop after a short burst.
            val sec=args.optInt("seconds",1).coerceIn(1,6)
            ch.send(Wire.find(true))
            try { delay(sec*1000L) } finally { withContext(NonCancellable){withTimeout(2500){ch.send(Wire.find(false))}} }
            return ControlResult("sent","已发送震动和停止指令")
        }
        val id=args.optInt("id",-1).takeIf { it in 1..255 }
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
            "heart-settings"->f.nested(8).firstOrNull()?.let { settings ->
                when {settings.num(1)==1L -> "自动心率已关闭"; settings.num(2)==0L -> "心率：智能监测"
                    settings.num(2)!=null -> "心率：每 ${settings.num(2)} 分钟";else->null}
            }
            "oxygen-settings"->f.nested(7).firstOrNull()?.let { settings ->
                when(settings.num(2)){0L->"血氧监测已关闭";1L->"仅睡眠时监测血氧";2L->"全天血氧监测";else->null}
            }
            "stress-settings"->f.nested(10).firstOrNull()?.num(1)?.let { if(it==1L)"全天压力监测已开启" else "全天压力监测已关闭" }
            else->null
        }
    }
}

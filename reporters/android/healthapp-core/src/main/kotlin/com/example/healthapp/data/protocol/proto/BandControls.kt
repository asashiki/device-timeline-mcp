// SPDX-License-Identifier: AGPL-3.0-only
package com.example.healthapp.data.protocol.proto

import java.time.*
import java.time.format.DateTimeFormatter

/** Typed controls from Gadgetbridge 2026.11.1 wear_system, wear_clock and wear_notification. */
object BandControls {
    private fun message(build: ProtoWriter.()->Unit) = ProtoWriter().apply(build).toByteArray()
    private fun system(id:Int, body:ByteArray?=null) = WearPacket(2,id,systemRaw=body)
    private fun schedule(id:Int, body:ByteArray?=null) = WearPacket(17,id,scheduleRaw=body)
    private fun hm(h:Int,m:Int) = message { require(h in 0..23 && m in 0..59); varint(1,h.toLong());varint(2,m.toLong()) }
    private fun date(t:ZonedDateTime) = message {varint(1,t.year.toLong());varint(2,t.monthValue.toLong());varint(3,t.dayOfMonth.toLong())}
    private fun time(t:ZonedDateTime) = message {varint(1,t.hour.toLong());varint(2,t.minute.toLong());varint(3,t.second.toLong());varint(4,(t.nano/1_000_000).toLong())}
    fun find(on:Boolean) = system(18,message {varint(5,if(on) 0L else 1L)})
    fun notification(id:Int, title:String, text:String, pkg:String) = WearPacket(7,0,notificationRaw=message {
      message(3, message { message(1,message {
        string(1,pkg);string(2,"Hibi");string(3,title.take(64));string(4,"");string(5,text.take(240))
        string(6,ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")));varint(7,(id and Int.MAX_VALUE).toLong())
      })})
    })
    fun alarm(h:Int,m:Int,repeatMask:Int,id:Int?=null,enabled:Boolean=true):WearPacket {
      require(repeatMask in 0..127)
      val detail=message {message(2,hm(h,m));varint(3,when(repeatMask){0->0L;127->1L;else->5L});if(repeatMask in 1..126)varint(4,repeatMask.toLong());bool(5,enabled);varint(7,2)}
      return if(id==null)schedule(1,message {message(2,detail)}) else schedule(2,message {message(3,message {varint(1,id.toLong());message(2,detail)})})
    }
    fun reminder(at:Instant, title:String,id:Int?=null):WearPacket {
      val t=at.atZone(ZoneOffset.UTC)
      val detail=message {message(1,date(t));message(2,time(t));varint(3,0);string(5,title.take(64))}
      return if(id==null)schedule(15,message {message(14,detail)}) else schedule(17,message {message(15,message {varint(1,id.toLong());message(2,detail)})})
    }
    fun remove(reminder:Boolean,id:Int):WearPacket {
      require(id in 0..255);return schedule(if(reminder)18 else 4,message {message(if(reminder)17 else 5,message {varint(1,id.toLong())})})
    }
    fun sleepSchedule(on:Boolean,h:Int,m:Int,eh:Int,em:Int)=schedule(9,message {message(9,message {
        bool(1,on);message(2,message {message(1,hm(h,m));message(2,hm(eh,em));varint(3,0)})
    })})
    fun syncClock(t:ZonedDateTime,uses24h:Boolean):WearPacket {
      val standard=t.zone.rules.getStandardOffset(t.toInstant()).totalSeconds/900
      val summer=t.offset.totalSeconds/900-standard
      fun zig(n:Int)=((n shl 1) xor (n shr 31)).toLong()
      return system(3,message {message(4,message {message(1,date(t));message(2,time(t));message(3,message {varint(1,zig(standard));varint(2,zig(summer));string(3,t.zone.id)});bool(4,!uses24h)})})
    }
    fun screenOnNotification(on:Boolean) = WearPacket(7,7,notificationRaw=message {bool(7,on)})
    fun camera(on:Boolean) = system(8,message {message(15,message {bool(1,on)})})
    fun language(code:String) = system(6,message {message(20,message {string(1,code)})})
    fun get(action:String):WearPacket = when(action){
        "watchfaces"->WearPacket(4,0); "battery"->system(1); "device-info"->system(2); "alarms"->schedule(0)
        "reminders"->schedule(14); "sleep-schedule"->schedule(8); "world-clocks"->schedule(10)
        "workout-types"->system(39); "vibration-patterns"->system(46); "screen-items"->system(29)
        "widgets"->system(51); "camera-status"->system(7); "wear-state"->system(78)
        "notification-screen"->WearPacket(7,6); "weather-locations"->WearPacket(10,5)
        "heart-settings"->WearPacket(8,10); "oxygen-settings"->WearPacket(8,8); "stress-settings"->WearPacket(8,14)
        else->error("未知读取项目")
    }
    data class Fields(val ints:Map<Int,List<Long>>,val bytes:Map<Int,List<ByteArray>>) {
        fun num(n:Int)=ints[n]?.firstOrNull()
        fun nested(n:Int)=bytes[n].orEmpty().map {fields(it)}
        fun text(n:Int)=bytes[n]?.firstOrNull()?.toString(Charsets.UTF_8)
    }
    fun fields(raw:ByteArray):Fields {
        val ints=mutableMapOf<Int,MutableList<Long>>();val bytes=mutableMapOf<Int,MutableList<ByteArray>>()
        val r=ProtoReader(raw);while(r.hasNext()){val n=r.nextField();when(r.lastWireType){0->ints.getOrPut(n){mutableListOf()}.add(r.readVarint());2->bytes.getOrPut(n){mutableListOf()}.add(r.readBytes());else->r.skip()}}
        return Fields(ints,bytes)
    }
    data class AlarmSlot(val id: Int, val hour: Int, val minute: Int, val enabled: Boolean, val repeat: Int, val smart: Boolean)
    data class AlarmList(val max: Int?, val slots: List<AlarmSlot>)

    fun alarmList(raw: ByteArray): AlarmList? {
        val list = fields(raw).nested(1).singleOrNull() ?: return null
        val slots = list.nested(1).map { row ->
            val id = row.num(1)?.takeIf { it in 0L..255L } ?: return null
            val detail = row.nested(2).singleOrNull() ?: return null
            val time = detail.nested(2).singleOrNull() ?: return null
            val h = (time.num(1) ?: 0L).takeIf { it in 0L..23L } ?: return null
            val m = (time.num(2) ?: 0L).takeIf { it in 0L..59L } ?: return null
            AlarmSlot(id.toInt(), h.toInt(), m.toInt(), detail.num(5) == 1L,
                detail.num(3)?.toInt() ?: 0, detail.num(7) == 1L)
        }
        if (slots.distinctBy { it.id }.size != slots.size) return null
        return AlarmList(list.num(2)?.toInt(), slots)
    }
    data class LocalReminder(val id: Int, val time: Instant, val title: String, val once: Boolean)
    fun reminders(raw: ByteArray): List<LocalReminder>? {
        val list = fields(raw).nested(10).singleOrNull() ?: return null
        return list.nested(1).map { row ->
            val id = row.num(1)?.takeIf { it in 0L..255L } ?: return null
            val detail = row.nested(2).singleOrNull() ?: return null
            val date = detail.nested(1).singleOrNull() ?: return null
            val time = detail.nested(2).singleOrNull() ?: return null
            val instant = runCatching { LocalDateTime.of(date.num(1)!!.toInt(), date.num(2)!!.toInt(),
                date.num(3)!!.toInt(), (time.num(1) ?: 0).toInt(), (time.num(2) ?: 0).toInt(),
                (time.num(3) ?: 0).toInt()).toInstant(ZoneOffset.UTC) }.getOrNull() ?: return null
            LocalReminder(id.toInt(), instant, detail.text(5) ?: "", (detail.num(3) ?: 0) == 0L)
        }
    }
    fun scheduleRows(raw:ByteArray,reminders:Boolean):List<String>? {
        val list=fields(raw).nested(if(reminders)10 else 1).firstOrNull()?:return null
        return list.nested(1).map { row ->
            val d=row.nested(2).firstOrNull()?:error("手环列表格式未知")
            val at=d.nested(2).firstOrNull()?:error("手环时间缺失")
            val h=at.num(1)?:0;val m=at.num(2)?:0
            "#${row.num(1)}  %02d:%02d".format(h,m)+(if(reminders) "  "+(d.text(5)?:"") else if(d.num(5)==0L)"  已关闭" else "  已启用")
        }
    }
}

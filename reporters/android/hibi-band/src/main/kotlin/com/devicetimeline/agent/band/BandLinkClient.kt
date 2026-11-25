package com.devicetimeline.agent.band

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BandLink(val connected:Boolean=false,val note:String="等待连接",val since:Long=0,val diagnostic:String="",val battery:Int?=null,val batteryAt:Long=0L,val powerMode:String="unknown")
object BandLinkClient {
    val state=MutableStateFlow(BandLink())
    @Volatile private var remote:Messenger?=null
    private val pending=ConcurrentHashMap<String,CompletableDeferred<Pair<String,String>>>()
    private val replies=Messenger(object:Handler(Looper.getMainLooper()){
        override fun handleMessage(msg:Message){
            val d=msg.data
            if(msg.what==5){
                val percent=d.getInt("percent",-1);val at=d.getLong("at",0)
                if(percent in 0..100 && at>0)state.value=state.value.copy(battery=percent,batteryAt=at,powerMode=d.getString("mode")?:"unknown")
                return
            }
            if(msg.what==4){pending.remove(d.getString("id"))?.complete((d.getString("status")?:"failed") to (d.getString("detail")?:""));return}
            if(msg.what==2){
                val ok=d.getBoolean("ok");val label=d.getString("status")?:"等待连接"
                val diag=d.getString("diagnostic").orEmpty()
                val old=state.value
                state.value=old.copy(connected=ok,note=label,since=d.getLong("connectedAt"),diagnostic=diag.ifBlank {old.diagnostic})
            }
        }
    })
    suspend fun maintain(ctx:Context){
        while(currentCoroutineContext().isActive){
            val ended=CompletableDeferred<Unit>()
            val conn=object:ServiceConnection {
                override fun onServiceConnected(name:ComponentName,binder:IBinder){
                    val target=Messenger(binder);remote=target
                    runCatching { target.send(Message.obtain(null,1).apply { replyTo=replies }) }.onFailure {ended.complete(Unit)}
                }
                override fun onServiceDisconnected(name:ComponentName){remote=null;ended.complete(Unit)}
                override fun onBindingDied(name:ComponentName){remote=null;ended.complete(Unit)}
                override fun onNullBinding(name:ComponentName){ended.complete(Unit)}
            }
            val bound=ctx.bindService(Intent(ctx,BandReadService::class.java),conn,Context.BIND_AUTO_CREATE)
            try { if(bound)ended.await() } finally {
                if(bound)runCatching {ctx.unbindService(conn)}
                remote=null;state.value=state.value.copy(connected=false,note="连接已断开",since=0)
                pending.values.forEach {it.complete("unconfirmed" to "连接已中断")};pending.clear()
            }
            delay(5000)
        }
    }
    suspend fun command(action:String,args:JSONObject=JSONObject()):Pair<String,String>{
        val target=remote ?: return "offline" to "手环未连接"
        if(!state.value.connected)return "offline" to state.value.note
        val id=UUID.randomUUID().toString();val out=CompletableDeferred<Pair<String,String>>()
        pending[id]=out
        return try{
            target.send(Message.obtain(null,3).apply {replyTo=replies;data=Bundle().apply {putString("id",id);putString("action",action);putString("args",args.toString())}})
            withTimeoutOrNull(if (action == "wake-ring") 38000L else 14000L){out.await()}?:( "unconfirmed" to "没有手环回执")
        } catch(e:RemoteException){"offline" to "蓝牙服务已断开"} finally {pending.remove(id)}
    }
}

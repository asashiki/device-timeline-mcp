package com.devicetimeline.agent.band

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BandLink(val connected:Boolean=false,val note:String="等待连接",val since:Long=0,val diagnostic:String="",val battery:Int?=null,val batteryAt:Long=0L,val powerMode:String="unknown",val standby:Boolean=false)
object BandLinkClient {
    val state=MutableStateFlow(BandLink())
    @Volatile private var remote:Messenger?=null
    private val pending=ConcurrentHashMap<String,CompletableDeferred<Pair<String,String>>>()
    private const val ISOLATION = "sleep_isolation"
    private const val UNTIL = "sleep_isolation_until"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hibi_band_status", Context.MODE_PRIVATE)
    fun isSleepIsolation(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean(ISOLATION, false) && p.getLong(UNTIL, 0) > System.currentTimeMillis()
    }
    private fun updateReader(target: Messenger, ctx: Context) {
        target.send(Message.obtain(null,1).apply {
            replyTo = replies
            data = Bundle().apply { putBoolean("suspendBetweenSyncs", isSleepIsolation(ctx)) }
        })
    }
    fun setSleepIsolation(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(ISOLATION, enabled)
            .putLong(UNTIL, if (enabled) System.currentTimeMillis() + 86_400_000L else 0L).apply()
        remote?.let { runCatching { updateReader(it, ctx) } }
    }
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
                state.value=old.copy(connected=ok,standby=d.getBoolean("standby"),note=label,since=d.getLong("connectedAt"),diagnostic=diag.ifBlank {old.diagnostic})
            }
        }
    })
    suspend fun maintain(ctx:Context) = coroutineScope {
        val expiry = launch {
            val p = prefs(ctx)
            val updates = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == ISOLATION || key == UNTIL) updates.trySend(Unit)
            }
            p.registerOnSharedPreferenceChangeListener(listener)
            try {
                while (isActive) {
                    if (!p.getBoolean(ISOLATION, false)) { updates.receive(); continue }
                    val remaining = p.getLong(UNTIL,0) - System.currentTimeMillis()
                    if (remaining <= 0) { setSleepIsolation(ctx, false); continue }
                    withTimeoutOrNull(remaining) { updates.receive() }
                }
            } finally {
                p.unregisterOnSharedPreferenceChangeListener(listener)
                updates.close()
            }
        }
        try {
        while(currentCoroutineContext().isActive){
            val ended=CompletableDeferred<Unit>()
            val conn=object:ServiceConnection {
                override fun onServiceConnected(name:ComponentName,binder:IBinder){
                    val target=Messenger(binder);remote=target
                    runCatching { updateReader(target, ctx) }.onFailure {ended.complete(Unit)}
                }
                override fun onServiceDisconnected(name:ComponentName){remote=null;ended.complete(Unit)}
                override fun onBindingDied(name:ComponentName){remote=null;ended.complete(Unit)}
                override fun onNullBinding(name:ComponentName){ended.complete(Unit)}
            }
            val bound=ctx.bindService(Intent(ctx,BandReadService::class.java),conn,Context.BIND_AUTO_CREATE)
            try { if(bound)ended.await() } finally {
                if(bound)runCatching {ctx.unbindService(conn)}
                remote=null;state.value=state.value.copy(connected=false,standby=false,note="连接已断开",since=0)
                pending.values.forEach {it.complete("unconfirmed" to "连接已中断")};pending.clear()
            }
            delay(5000)
        }
        } finally { expiry.cancel() }
    }
    suspend fun command(action:String,args:JSONObject=JSONObject()):Pair<String,String>{
        val target=remote ?: return "offline" to "手环未连接"
        val link = state.value
        if(!link.connected && !link.standby)return "offline" to link.note
        val id=UUID.randomUUID().toString();val out=CompletableDeferred<Pair<String,String>>()
        pending[id]=out
        return try{
            target.send(Message.obtain(null,3).apply {replyTo=replies;data=Bundle().apply {putString("id",id);putString("action",action);putString("args",args.toString())}})
            withTimeoutOrNull(if (action == "wake-ring") 60000L else 37000L){out.await()}?:( "unconfirmed" to "没有手环回执")
        } catch(e:RemoteException){"offline" to "蓝牙服务已断开"} finally {pending.remove(id)}
    }
}

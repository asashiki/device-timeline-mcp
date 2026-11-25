package com.devicetimeline.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Uses the system network provider at walking-block resolution. No GPS or extra wake alarm. */
internal class PhoneLocation(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("phone-location", Context.MODE_PRIVATE)
    private val manager = ctx.getSystemService(LocationManager::class.java)
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { saveFix(location); changed.trySend(Unit) }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }

    private fun saveFix(fix: Location) = synchronized(lock) {
        if (!enabled(ctx) || !hasPermission(ctx) || !fix.hasAccuracy() || fix.accuracy <= 0 ||
            !fix.accuracy.isFinite() || System.currentTimeMillis() - fix.time !in 0..30*60000L) return@synchronized
        val last = prefs.getString("last",null)?.let(::JSONObject)
        if (last != null) {
            val interval = fix.time - last.getLong("time")
            if (interval < INTERVAL) return@synchronized
            val distance=FloatArray(1)
            Location.distanceBetween(last.getDouble("latitude"),last.getDouble("longitude"),fix.latitude,fix.longitude,distance)
            if (distance[0]<100 && interval < 2*60*60000L) return@synchronized
        }
        val point=JSONObject().put("time",fix.time).put("latitude",fix.latitude).put("longitude",fix.longitude).put("accuracyMeters",fix.accuracy.toDouble())
        val queue=JSONArray(prefs.getString("pending","[]"))
        while(queue.length()>=192) queue.remove(0)
        queue.put(point)
        prefs.edit().putString("last",point.toString()).putString("pending",queue.toString()).apply()
    }

    private suspend fun upload() {
        val settings = SettingsStore(ctx).load()
        val base = settings.serverUrl.toHttpUrlOrNull() ?: return
        if (!base.isHttps || settings.token.isBlank()) return
        val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/api/devices/location")
            .query(null).fragment(null).build()
        while (currentCoroutineContext().isActive && enabled(ctx) && hasPermission(ctx)) {
            val point=synchronized(lock){JSONArray(prefs.getString("pending","[]")).optJSONObject(0)} ?: return
            val stamp=point.getLong("time")
            val body=JSONObject(point.toString()).apply {remove("time");put("sampledAt",Instant.ofEpochMilli(stamp).toString())}
            val request=Request.Builder().url(url).header("Authorization","Bearer "+settings.token)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val code=runCatching {client.newCall(request).execute().use { it.code }}.getOrNull() ?: return
            if(code !in 200..299 && code !in setOf(400,422))return
            synchronized(lock){
                val queue=JSONArray(prefs.getString("pending","[]"))
                if(queue.optJSONObject(0)?.optLong("time")==stamp) {
                    queue.remove(0)
                    val edit=prefs.edit().putString("pending",queue.toString())
                    if(code in 200..299) edit.putLong("uploadedFix",stamp)
                    edit.apply()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun run() {
        if (!enabled(ctx) || !hasPermission(ctx)) return
        val providers = manager.allProviders.filter { it == LocationManager.NETWORK_PROVIDER || it == LocationManager.PASSIVE_PROVIDER }
        try {
            val registered = providers.map { provider ->
                runCatching { manager.requestLocationUpdates(provider,INTERVAL,100f,listener,Looper.getMainLooper()) }.isSuccess
            }
            if (registered.none { it }) return
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let(::saveFix)
            while(currentCoroutineContext().isActive && enabled(ctx) && hasPermission(ctx)) {
                upload()
                withTimeoutOrNull(INTERVAL) { changed.receive() }
            }
        } finally { runCatching { manager.removeUpdates(listener) } }
    }

    companion object {
        private const val INTERVAL=15*60000L
        private val lock=Any()
        fun enabled(c:Context)=c.getSharedPreferences("phone-location",Context.MODE_PRIVATE).getBoolean("enabled",false)
        fun setEnabled(c:Context,on:Boolean)=synchronized(lock) {
            val edit=c.getSharedPreferences("phone-location",Context.MODE_PRIVATE).edit()
            if(!on) edit.clear()
            edit.putBoolean("enabled",on).apply()
        }
        fun whenInUse(c:Context)=ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        fun hasPermission(c:Context)=whenInUse(c) && (Build.VERSION.SDK_INT<29 ||
            ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED)
    }
}

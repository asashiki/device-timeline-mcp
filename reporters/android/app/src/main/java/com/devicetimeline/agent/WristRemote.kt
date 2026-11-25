package com.devicetimeline.agent

import android.content.Context
import com.devicetimeline.agent.band.BandLinkClient
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class WristRemote(private val context: Context) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(35, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("wrist-commands", Context.MODE_PRIVATE)

    private suspend fun exchange(path: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val settings = SettingsStore(context).load()
        val origin = requireNotNull(settings.serverUrl.toHttpUrlOrNull())
        require(origin.isHttps && origin.username.isEmpty() && origin.password.isEmpty())
        val url = origin.newBuilder().encodedPath(origin.encodedPath.trimEnd('/') + "/api/devices/wrist/" + path)
            .query(null).fragment(null).build()
        val req = Request.Builder().url(url).header("Authorization","Bearer "+settings.token)
            .post(data.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { response ->
            if (path == "receipt" && response.code == 404) return@withContext JSONObject()
            check(response.isSuccessful)
            JSONObject(response.peekBody(16384).string())
        }
    }
    suspend fun run() {
        while(currentCoroutineContext().isActive) {
            try {
                val pending = prefs.getString("receipt",null)
                if (pending != null) {
                    exchange("receipt",JSONObject(pending))
                    prefs.edit().remove("receipt").commit()
                }
                val link=BandLinkClient.state.value
                val state=JSONObject().put("connected",link.connected).put("note",link.note.take(100))
                    .put("since",link.since).put("version",1)
                link.battery?.takeIf { link.batteryAt>0 }?.let {
                    state.put("battery",it).put("batteryAt",link.batteryAt).put("powerMode",link.powerMode)
                }
                val cmd=exchange("next",state).optJSONObject("command")?:continue
                val id=cmd.getString("id")
                val receipt=JSONObject().put("id",id).put("status","unconfirmed").put("detail","执行时连接中断")
                if (!prefs.edit().putString("receipt",receipt.toString()).commit()) continue
                val (status,detail)=if(System.currentTimeMillis()>cmd.getLong("expiresAt"))
                    "expired" to "指令已过期" else BandLinkClient.command(cmd.getString("action"),cmd.getJSONObject("args"))
                receipt.put("status",status).put("detail",detail.take(1800))
                prefs.edit().putString("receipt",receipt.toString()).commit()
            } catch(e:CancellationException){throw e}
              catch(e:Exception){delay(10000)}
        }
    }
}

package com.devicetimeline.agent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicetimeline.agent.band.BandLink
import com.devicetimeline.agent.band.BandSample
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val pine = Color(0xFF12534C)
private val label = Color(0xFF43564D)
private val rule = Color(0xFFD7DED8)
private val wash = Color(0xFFE3EEE6)

private fun localTime(iso:String?):String? = iso?.let {
    val at = runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()) }.getOrNull() ?: return null
    val today = LocalDate.now()
    val clock = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    when(at.toLocalDate()) {
        today -> clock
        today.minusDays(1) -> "昨天 $clock"
        else -> at.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
    }
}

@Composable
internal fun BandHeader(link:BandLink, status:String, onClick:(()->Unit)?) {
    val hit = if(onClick!=null) Modifier.clickable(role=Role.Button,onClick=onClick) else Modifier
    Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).then(hit).padding(15.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(30.dp,43.dp)) {
            val w=size.width; val h=size.height
            drawRoundRect(Color(0xFF788880),Offset(w*.29f,0f),Size(w*.42f,h),CornerRadius(w*.16f))
            drawRoundRect(Color(0xFF24372F),Offset(w*.12f,h*.19f),Size(w*.76f,h*.62f),CornerRadius(w*.21f))
            drawLine(Color(0xFFC0E6C9),Offset(w*.38f,h*.62f),Offset(w*.61f,h*.38f),w*.075f)
        }
        Column(Modifier.weight(1f)) {
            Text("小米手环 9 Pro",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
            Text(status,color=label,fontSize=15.sp)
        }
        val last = if(!link.connected && link.batteryAt>0) localTime(Instant.ofEpochMilli(link.batteryAt).toString()) else null
        Column(horizontalAlignment=Alignment.End) {
            Text(link.battery?.let { if(link.powerMode=="charging") "充电中 $it%" else "电量 $it%" } ?: "电量待同步",
                color=pine,fontSize=16.sp,fontWeight=FontWeight.Medium)
            if(last!=null)Text(last,color=label,fontSize=15.sp)
        }
    }
}

@Composable
internal fun WristOverview(heart:BandSample?,steps:BandSample?,hours:List<Int?>,sleep:BandSample?) {
    Column(Modifier.fillMaxWidth().background(wash,RoundedCornerShape(18.dp)).padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(if(sleep?.asleepMinutes!=null) "最近睡眠" else "最近睡眠时段",fontSize=18.sp,fontWeight=FontWeight.Medium,color=pine)
        val minutes = sleep?.let { it.asleepMinutes ?: it.value }
        if(minutes!=null) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                if(minutes>=60){BigNumber((minutes/60).toString());Text("小时",fontSize=18.sp,color=pine)}
                BigNumber((minutes%60).toString());Text("分",fontSize=18.sp,color=pine)
            }
            Text(listOfNotNull(localTime(sleep.recordedAt),localTime(sleep.endAt)).joinToString("  —  "),
                color=label,fontSize=17.sp)
        } else Text("还没有睡眠记录",fontSize=22.sp)
    }
    Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("今日步数",fontSize=18.sp,fontWeight=FontWeight.Medium)
        val values=hours.filterNotNull()
        val sum=if(values.isEmpty()) null else values.sum()
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            BigNumber(sum?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—")
            Text("步",color=label,fontSize=18.sp)
            Spacer(Modifier.weight(1f))
            localTime(steps?.recordedAt)?.let { Text("截至 $it",color=label,fontSize=15.sp) }
        }
        if(sum!=null) {
            val peak=(values.maxOrNull() ?: 0).coerceAtLeast(1)
            Canvas(Modifier.fillMaxWidth().height(48.dp).semantics {
                contentDescription = hours.mapIndexedNotNull { h,n -> n?.let { "${h}点 ${n}步" } }.joinToString("，")
            }) {
                val slot=size.width/24;val width=slot*.61f
                hours.forEachIndexed { i,n ->
                    val x=slot*(i+.19f)
                    if(n!=null){
                        val height=(size.height*n.toFloat()/peak).coerceAtLeast(2.dp.toPx())
                        drawRoundRect(pine,Offset(x,size.height-height),Size(width,height),CornerRadius(2.dp.toPx()))
                    }
                }
                drawLine(rule,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                listOf("0 时","12 时","24 时").forEach { Text(it,color=label,fontSize=14.sp) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("最近心率",fontSize=18.sp,fontWeight=FontWeight.Medium)
            localTime(heart?.recordedAt)?.let { Text(it,color=label,fontSize=15.sp) }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(heart?.value?.toString() ?: "—",fontSize=34.sp,fontWeight=FontWeight.SemiBold)
            Text("次/分",color=label,fontSize=16.sp)
        }
    }
}

@Composable
private fun BigNumber(value:String) {
    Text(value,fontSize=43.sp,lineHeight=48.sp,letterSpacing=(-1.5).sp,fontWeight=FontWeight.SemiBold)
}

@Composable
internal fun HibiNavigation(page:String,onPage:(String)->Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        HorizontalDivider(color=rule)
        Row(Modifier.fillMaxWidth()) {
            listOf("今日","手环","设置").forEachIndexed { i,name ->
                val active=page==name
                val color=if(active) pine else Color(0xFF56645D)
                Column(Modifier.weight(1f).selectable(active,role=Role.Tab,onClick={onPage(name)})
                    .padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Canvas(Modifier.size(23.dp)) {
                        val w=size.width;val sw=1.8.dp.toPx()
                        fun point(x:Float,y:Float)=Offset(w*x,w*y)
                        when(i){
                            0 -> {
                                drawRoundRect(color,point(.12f,.12f),Size(w*.76f,w*.76f),CornerRadius(w*.13f),style=Stroke(sw))
                                drawPath(Path().apply{moveTo(w*.23f,w*.54f);lineTo(w*.39f,w*.54f);lineTo(w*.47f,w*.36f);lineTo(w*.57f,w*.67f);lineTo(w*.65f,w*.49f);lineTo(w*.77f,w*.49f)},color,style=Stroke(sw))
                            }
                            1 -> {
                                drawRoundRect(color,point(.28f,.2f),Size(w*.44f,w*.6f),CornerRadius(w*.13f),style=Stroke(sw))
                                drawLine(color,point(.4f,.07f),point(.6f,.07f),sw);drawLine(color,point(.4f,.93f),point(.6f,.93f),sw)
                            }
                            else -> listOf(.2f,.5f,.8f).forEachIndexed { j,y ->
                                drawLine(color,point(.11f,y),point(.89f,y),sw)
                                drawCircle(Color.White,w*.1f,point(if(j==1).33f else .65f,y))
                                drawCircle(color,w*.1f,point(if(j==1).33f else .65f,y),style=Stroke(sw))
                            }
                        }
                    }
                    Text(name,color=color,fontSize=15.sp,fontWeight=if(active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

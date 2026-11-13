package com.team35.nbbang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat

private val Accent = Color(0xFFFFC94D)
private val Dim = Color(0xFF9AA0A6)

private fun won(x: Double) = NumberFormat.getIntegerInstance().format(Meter.won(x)) + "원"

/** 이미 10원 단위로 확정된 청구액 */
private fun wonL(x: Long) = NumberFormat.getIntegerInstance().format(x) + "원"
private fun km(x: Double) = "%.1f km".format(x)
private fun pct(x: Double) = "${(x * 100).toInt()}%"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                Surface(Modifier.fillMaxSize()) {
                    val vm: MeterViewModel = viewModel()
                    if (vm.driving) DrivingScreen(vm.view) else ParkedScreen(vm)
                    vm.vehicleError?.let {
                        Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

/** 주행 중: 미터기처럼 큰 숫자만. 터치할 것 없음 (운전자 주의분산 가이드). */
@Composable
private fun DrivingScreen(v: MeterView) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("주행 중 · 간단 화면", color = Dim, fontSize = 20.sp)
        Text(wonL(v.billedTotal), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text("${km(v.totalKm)} · ${v.wonPerKm}원/km", fontSize = 26.sp, color = Dim)
        if (v.laborOn) {
            Text("운전 강도 ${v.laborLabel} · 운전자 ${pct(v.laborDiscount)} 할인", fontSize = 22.sp, color = Dim)
        }
        Spacer(Modifier.height(8.dp))
        for (r in v.aboard) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.name, fontSize = 34.sp)
                Text(wonL(v.billed[r.id] ?: 0L), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (v.aboard.isEmpty()) Text("탑승한 사람이 없어요", fontSize = 26.sp, color = Dim)
    }
}

/** 정차 중(P): 이름 지정, 단가·통행료, 정산. */
@Composable
private fun ParkedScreen(vm: MeterViewModel) {
    val v = vm.view
    var renaming by remember { mutableStateOf<Rider?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp // 앱 영역 2칸(가로) 이면 두 단
        val left: @Composable () -> Unit = {
            Header(v)
            Spacer(Modifier.height(16.dp))
            SeatGrid(v) { renaming = it }
        }
        val right: @Composable () -> Unit = {
            Settlement(v)
            Spacer(Modifier.height(16.dp))
            Controls(vm)
        }
        if (wide) {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { left() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { right() }
            }
        } else {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                left(); Spacer(Modifier.height(24.dp)); right()
            }
        }
    }

    renaming?.let { r ->
        RenameDialog(r, vm.savedNames, onDone = { name ->
            if (name != null) vm.rename(r.id, name)
            renaming = null
        })
    }
}

@Composable
private fun Header(v: MeterView) {
    Text("N빵 드라이브", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text("같이 탄 거리만큼 자동으로 나눠요 · 정차 중", color = Dim, fontSize = 16.sp)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(wonL(v.billedTotal), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text(km(v.totalKm), fontSize = 24.sp, color = Dim, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** 차 좌석 배치대로 2×2. 탄 자리를 누르면 이름을 바꾼다. */
@Composable
private fun SeatGrid(v: MeterView, onTap: (Rider) -> Unit) {
    val bySeat = v.aboard.associateBy { it.seat }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in listOf(listOf(Seat.DRIVER, Seat.FRONT), listOf(Seat.REAR_LEFT, Seat.REAR_RIGHT))) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (seat in row) {
                    val r = bySeat[seat]
                    Card(
                        modifier = Modifier.weight(1f).heightIn(min = 120.dp)
                            .let { if (r != null) it.clickable { onTap(r) } else it },
                        colors = CardDefaults.cardColors(
                            containerColor = if (r != null) Color(0xFF2B2F36) else Color(0xFF1A1C20),
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(seat.label, color = Dim, fontSize = 15.sp)
                            if (r != null) {
                                Text(r.name + "  ✎", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                Text("${wonL(v.billed[r.id] ?: 0L)} · ${km(r.km)}", fontSize = 18.sp, color = Accent)
                            } else {
                                Text("빈자리", fontSize = 22.sp, color = Dim)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Settlement(v: MeterView) {
    Text("정산", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (v.riders.isEmpty()) {
        Text("탑승하면 자동으로 기록돼요", color = Dim, fontSize = 18.sp)
        return
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF22252B), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 탄 사람 먼저(좌석 순), 내린 사람은 아래로
        for (r in v.riders.sortedWith(compareBy({ !it.aboard }, { it.seat.ordinal }))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(r.name, fontSize = 22.sp)
                    val where = if (r.aboard) "탑승 중" else "${km(r.leftAtKm!!)} 지점에서 내림"
                    Text("${km(r.km)} 함께 · $where", color = Dim, fontSize = 14.sp)
                    if (r.seat == Seat.DRIVER && v.driverSaved >= 5) {
                        Text("운전 수고 할인 −${won(v.driverSaved)}", color = Accent, fontSize = 14.sp)
                    }
                }
                Text(wonL(v.billed[r.id] ?: 0L), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (v.extraCost > 0) Text("통행료·주차 ${won(v.extraCost)} 포함", color = Dim, fontSize = 14.sp)
    }
}

@Composable
private fun Controls(vm: MeterViewModel) {
    val v = vm.view
    val r = vm.rateView

    // 비용 기준
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("차 효율로 자동 단가", fontSize = 20.sp)
        Switch(checked = r.auto, onCheckedChange = { vm.toggleAutoRate() })
    }
    if (r.auto) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${r.energy.label} 단가", fontSize = 18.sp)
            Stepper("${r.pricePerUnit}원/${r.energy.unit}", { vm.changePrice(-10) }, { vm.changePrice(10) })
        }
        val how = if (r.efficiency != null) {
            "%.1f %s (%s) → %d원/km".format(r.efficiency, "km/${r.energy.unit}", r.source, v.wonPerKm)
        } else {
            "효율 측정 중 (2km 이상 주행 후) → 그동안 ${r.manualWonPerKm}원/km"
        }
        Text(how, fontSize = 15.sp, color = Dim)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("km당", fontSize = 18.sp)
            Stepper("${r.manualWonPerKm}원", { vm.changeManualRate(-10) }, { vm.changeManualRate(10) })
        }
    }
    Spacer(Modifier.height(16.dp))

    // 운전자 부담
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("운전자도 같이 내기", fontSize = 20.sp)
        Switch(checked = v.driverPays, onCheckedChange = { vm.toggleDriverPays() })
    }
    if (v.driverPays) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("운전 수고 할인", fontSize = 20.sp)
            Switch(checked = v.laborOn, onCheckedChange = { vm.toggleLabor() })
        }
        if (v.laborOn) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("기본", fontSize = 18.sp)
                Stepper(pct(v.laborBase), { vm.changeLaborBase(-0.05) }, { vm.changeLaborBase(0.05) })
            }
            Text(
                "+ 막히는 길·골목 운전 최대 +15% (브레이크·핸들), 비 오면 +5%, 크루즈 중엔 강도 낮춤 · 최근 ${pct(v.laborDiscount)}",
                fontSize = 15.sp, color = Dim,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("통행료·주차비 (지금 탄 사람끼리)", fontSize = 18.sp, color = Dim)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (w in listOf(1000, 3000, 5000)) {
            OutlinedButton(onClick = { vm.addExtra(w) }) { Text("+${w / 1000},000원", fontSize = 18.sp) }
        }
    }
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.newTrip() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("정산 끝 · 새 운행 시작", fontSize = 20.sp)
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    OutlinedButton(onClick = onMinus) { Text("−", fontSize = 22.sp) }
    Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    OutlinedButton(onClick = onPlus) { Text("+", fontSize = 22.sp) }
}

@Composable
private fun RenameDialog(r: Rider, saved: List<String>, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(r.name) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("${r.seat.label} 이름") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saved.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in saved) OutlinedButton(onClick = { onDone(n) }) { Text(n, fontSize = 18.sp) }
                    }
                }
                OutlinedTextField(value = text, onValueChange = { text = it.take(12) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("취소") } },
    )
}

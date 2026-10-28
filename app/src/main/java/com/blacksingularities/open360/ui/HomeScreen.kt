package com.blacksingularities.open360.ui

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksingularities.open360.R
import com.blacksingularities.open360.data.Settings
import com.blacksingularities.open360.export.PanoItem
import com.blacksingularities.open360.export.PanoStore
import com.blacksingularities.open360.update.Update
import com.blacksingularities.open360.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    settings: Settings,
    refreshKey: Int,
    onCapture: () -> Unit,
    onOpen: (Uri, Boolean) -> Unit,
    picking: Boolean = false,
    onCancel: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var items by remember { mutableStateOf<List<PanoItem>?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Update?>(null) }

    LaunchedEffect(refreshKey) {
        items = withContext(Dispatchers.IO) { PanoStore.list(context) }
    }
    LaunchedEffect(settings.updates) {
        if (settings.updates && !picking) update = Updates.check()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onOpen(uri, false)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.brand_monochrome), null, Modifier.size(44.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open360", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (picking) {
                    RoundButton(Icons.Outlined.Close, "Anuluj", background = Color.Transparent) { onCancel() }
                } else {
                    RoundButton(Icons.Outlined.Settings, "Ustawienia", background = Color.Transparent) { showSettings = true }
                }
            }
        }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.PanelHigh)
                    .padding(20.dp),
            ) {
                Text(if (picking) "Wybierz panoramę" else "Panorama 360° na żywo", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (picking) {
                        "Dotknij zapisanej panoramy, aby ją przesłać, albo zrób nową – po zapisaniu trafi od razu do aplikacji, która prosi o plik."
                    } else {
                        "Wodź telefonem – sfera wypełnia się na ekranie w czasie rzeczywistym. " +
                            "Pełna sfera, pas horyzontu albo dowolny fragment."
                    },
                    color = Palette.TextDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Nowa panorama", Icons.Outlined.CameraAlt, Modifier.fillMaxWidth()) { onCapture() }
                if (!picking) {
                    Spacer(Modifier.height(10.dp))
                    OutlineButton("Otwórz zdjęcie 360°", Icons.Outlined.PhotoLibrary, Modifier.fillMaxWidth()) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
        }
        update?.let { u ->
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dostępna wersja ${u.version}", fontWeight = FontWeight.Bold)
                        Text("Pobierz i zainstaluj nowy plik APK.", color = Palette.TextDim, fontSize = 13.sp)
                    }
                    Pill("Pobierz", icon = Icons.Outlined.Download, active = true) { runCatching { uriHandler.openUri(u.apkUrl) } }
                }
            }
        }
        item {
            Text("Twoje panoramy", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        val list = items
        if (list != null && list.isEmpty()) {
            item {
                Text(
                    "Tu pojawią się zapisane panoramy (folder Zdjęcia › Open360).",
                    color = Palette.TextDim, fontSize = 14.sp,
                )
            }
        }
        if (list != null) {
            items(list, key = { it.uri.toString() }) { p -> PanoCard(p) { onOpen(p.uri, true) } }
        }
        item {
            Credits(Modifier.padding(top = 20.dp, bottom = 24.dp).navigationBarsPadding())
        }
    }

    if (showSettings) {
        SettingsSheet(settings, null, 8192, object : SettingsEffects {}) { showSettings = false }
    }
}

@Composable
private fun PanoCard(item: PanoItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { PanoStore.thumbnail(context, item.uri, 960) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(item.name) { onClick() }
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.PanelHigh),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f).background(Color(0xFF111111))) {
            thumb?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(Icons.Outlined.PhotoLibrary, null, Modifier.align(Alignment.Center), tint = Palette.TextFaint)
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateFormat.format("d MMM yyyy, HH:mm", Date(item.date)).toString(),
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f),
            )
            Text(
                String.format(Locale.US, "%d×%d · %.1f MB", item.width, item.height, item.size / 1_048_576f),
                style = MonoDigits, color = Palette.TextDim,
            )
        }
    }
}

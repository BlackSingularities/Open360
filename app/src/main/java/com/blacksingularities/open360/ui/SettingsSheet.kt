package com.blacksingularities.open360.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksingularities.open360.BuildConfig
import com.blacksingularities.open360.camera.CameraInfo
import com.blacksingularities.open360.data.Settings
import java.util.Locale
import kotlin.math.roundToInt

/** Co trzeba odświeżyć po zmianie ustawienia. */
interface SettingsEffects {
    fun cameraChanged() {}
    fun canvasChanged() {}
    fun exposureChanged() {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    camera: CameraInfo?,
    maxTexture: Int,
    effects: SettingsEffects,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Palette.Panel, scrimColor = Palette.Scrim) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("Ustawienia", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            SectionTitle("Jakość")
            val sizes = settings.canvasSizes(maxTexture)
            ChoiceRow(
                "Rozdzielczość panoramy", sizes, settings.effectiveCanvas(maxTexture),
                { "${it / 1024}K" },
                { canvasDetail(it) },
            ) {
                settings.canvasWidth = it
                effects.canvasChanged()
                if (settings.frameHeight == 0) effects.cameraChanged()
            }
            val auto = Settings.autoFrameHeight(settings.effectiveCanvas(maxTexture))
            ChoiceRow(
                "Rozdzielczość klatek", listOf(0) + Settings.FRAME_HEIGHTS, settings.frameHeight,
                { if (it == 0) "Auto" else "${it}p" },
                { h ->
                    val base = if (h == 0) "Dobierana do panoramy (teraz ${auto}p)." else "Stała – krótszy bok klatki co najmniej ${h}px."
                    val now = camera?.let { " Aparat daje ${it.frameH}×${it.frameW}." } ?: ""
                    val heavy = if ((if (h == 0) auto else h) >= 2560) " Duże klatki – podgląd może być płynny nieco mniej." else ""
                    base + now + heavy
                },
            ) {
                settings.frameHeight = it
                effects.cameraChanged()
            }

            SectionTitle("Aparat")
            if (camera != null && camera.evMax > camera.evMin) {
                var ev by remember { mutableFloatStateOf(settings.exposure.toFloat()) }
                SliderRow(
                    "Kompensacja ekspozycji", ev, camera.evMin.toFloat()..camera.evMax.toFloat(),
                    { String.format(Locale.US, "%+.1f EV", it.roundToInt() * camera.evStep) },
                    "Jaśniej lub ciemniej niż automatyka. Na czas zbierania ekspozycja i biel są blokowane.",
                    steps = (camera.evMax - camera.evMin - 1).coerceAtLeast(0),
                    onChange = { ev = it },
                    onDone = {
                        settings.exposure = ev.roundToInt()
                        effects.exposureChanged()
                    },
                )
            }
            if (camera != null && camera.minZoom < 1f) {
                ToggleRow("Obiektyw szerokokątny", "Mniej kadrów na całą sferę (${String.format(Locale.US, "%.1f", camera.minZoom)}×), ale mniej szczegółów.", settings.wide) {
                    settings.wide = it
                    effects.cameraChanged()
                }
            }
            if (camera == null) {
                Text("Opcje aparatu pojawią się na ekranie robienia panoramy.", fontSize = 13.sp, color = Palette.TextDim)
            }

            SectionTitle("Automatycznie")
            val autoItems = listOf(
                "Odrzucanie rozmazanych klatek – z ruchu telefonu i z ostrości samego obrazu",
                "Krótszy czas naświetlania podczas zbierania (ostrzejsze klatki)",
                "Dokładne dopasowanie każdej klatki (ułamek piksela)",
                "Szwy między środkami kadrów, bez duchów",
                "Wyrównanie ekspozycji i winietowania",
                "Kalibracja kąta widzenia i opóźnienia czujnika",
                "Korekta migawki szczelinowej",
                "Kadrowanie, wypełnianie dziur i wyostrzanie przy zapisie",
            )
            autoItems.forEach { t ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", color = Palette.TextFaint, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(t, color = Palette.TextDim, fontSize = 13.sp)
                }
            }

            SectionTitle("Ogólne")
            ToggleRow("Dźwięk i wibracje", "Migawka w trybie punktów, ostrzeżenie przy zbyt szybkim ruchu.", settings.feedback) { settings.feedback = it }
            ToggleRow("Sprawdzaj aktualizacje", "Informacja o nowej wersji na ekranie głównym.", settings.updates) { settings.updates = it }
            Spacer(Modifier.height(8.dp))
            OutlineButton("Przywróć domyślne", Icons.Outlined.RestartAlt) {
                settings.resetAll()
                effects.canvasChanged()
                effects.cameraChanged()
                effects.exposureChanged()
            }

            SectionTitle("O aplikacji")
            Text("Open360 ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
            Text(
                "Panoramy 360° tworzone na żywo z aparatu. Zszywanie na GPU w projekcji równoprostokątnej; " +
                    "ważenie od środka kadru i wyrównanie ekspozycji wzorowane na OpenPano " +
                    "(Yuxin Wu, licencja MIT).",
                color = Palette.TextDim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Credits()
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun canvasDetail(w: Int): String {
    val mpx = w.toLong() * (w / 2) / 1_000_000
    val base = "$w × ${w / 2} px · $mpx Mpx · obowiązuje od następnej panoramy"
    return when {
        w >= 16384 -> "$base · bez cofania · wymaga bardzo wolnego, płynnego prowadzenia"
        w >= 8192 -> "$base · prowadź spokojnie"
        else -> base
    }
}

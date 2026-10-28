package com.blacksingularities.open360.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import com.blacksingularities.open360.camera.OrientationSource
import com.blacksingularities.open360.data.BlendMode
import com.blacksingularities.open360.data.ExportCrop
import com.blacksingularities.open360.data.HoleFill
import com.blacksingularities.open360.data.Settings
import java.util.Locale
import kotlin.math.roundToInt

/** Co trzeba odświeżyć po zmianie ustawienia. */
interface SettingsEffects {
    fun cameraChanged() {}
    fun gridChanged() {}
    fun canvasChanged() {}
    fun sensorChanged() {}
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

            SectionTitle("Zszywanie")
            ChoiceRow("Łączenie klatek", BlendMode.entries, settings.blend, { it.label }, { it.detail }) { settings.blend = it }
            SliderRow(
                "Szerokość przenikania", settings.feather, 0.05f..0.8f, { "${(it * 100).roundToInt()}%" },
                "Jak daleko od brzegu kadru waga rośnie do pełnej. Więcej = gładsze szwy.",
            ) { settings.feather = it }
            ToggleRow("Korekta obrazem", "Dopasowuje każdą klatkę do zszytej panoramy – usuwa dryf żyroskopu i drobne przesunięcia.", settings.imageAlign) { settings.imageAlign = it }
            ToggleRow("Wyrównanie ekspozycji", "Kompensuje różnice jasności między klatkami (jak gain compensation w OpenPano).", settings.autoGain) { settings.autoGain = it }
            SliderRow(
                "Korekta winietowania", settings.vignette, 0f..0.8f, { "${(it * 100).roundToInt()}%" },
                "Rozjaśnia rogi kadru, żeby szwy nie tworzyły ciemnych pasów.",
            ) { settings.vignette = it }
            SliderRow(
                "Maksymalna prędkość ruchu", settings.maxSpeed, 10f..120f, { "${it.roundToInt()}°/s" },
                "W trybie malowania szybsze klatki są pomijane (rozmycie ruchu).",
            ) { settings.maxSpeed = it }
            SliderRow(
                "Zakładka punktów", settings.pointOverlap, 0.15f..0.6f, { "${(it * 100).roundToInt()}%" },
                "Ile sąsiednie zdjęcia w trybie punktów nachodzą na siebie.",
                onChange = { settings.pointOverlap = it },
                onDone = { effects.gridChanged() },
            )
            ToggleRow("Automatyczne zakończenie", "Po zebraniu wszystkich punktów wstrzymaj zbieranie.", settings.autoFinish) { settings.autoFinish = it }

            SectionTitle("Jakość")
            val sizes = listOf(2048, 4096, 6144, 8192).filter { it <= maxTexture }.ifEmpty { listOf(2048) }
            ChoiceRow(
                "Rozdzielczość panoramy", sizes, settings.canvasWidth.coerceAtMost(sizes.last()),
                { "${it / 1024}K" },
                { "$it × ${it / 2} px · zmiana obowiązuje od następnej panoramy" + if (it > 6144) " · bez cofania" else "" },
            ) {
                settings.canvasWidth = it
                effects.canvasChanged()
            }
            ChoiceRow(
                "Rozdzielczość klatek", listOf(720, 960, 1440), settings.frameHeight, { "${it}p" },
                { "Wyższa – ostrzejsza panorama, większe obciążenie." },
            ) {
                settings.frameHeight = it
                effects.cameraChanged()
            }

            SectionTitle("Aparat i czujniki")
            ToggleRow("Blokada ekspozycji i bieli", "Podczas zbierania jasność i barwy nie zmieniają się między kierunkami.", settings.lockExposure) {
                settings.lockExposure = it
                effects.exposureChanged()
            }
            if (camera != null && camera.evMax > camera.evMin) {
                var ev by remember { mutableFloatStateOf(settings.exposure.toFloat()) }
                SliderRow(
                    "Kompensacja ekspozycji", ev, camera.evMin.toFloat()..camera.evMax.toFloat(),
                    { String.format(Locale.US, "%+.1f EV", it.roundToInt() * camera.evStep) },
                    steps = (camera.evMax - camera.evMin - 1).coerceAtLeast(0),
                    onChange = { ev = it },
                    onDone = {
                        settings.exposure = ev.roundToInt()
                        effects.exposureChanged()
                    },
                )
            }
            if (camera != null && camera.minZoom < 1f) {
                ToggleRow("Obiektyw szerokokątny", "Mniej kadrów na całą sferę (${String.format(Locale.US, "%.1f", camera.minZoom)}×).", settings.wide) {
                    settings.wide = it
                    effects.cameraChanged()
                }
            }
            var fov by remember { mutableFloatStateOf(settings.fovScale) }
            SliderRow(
                "Kalibracja kąta widzenia", fov, 0.85f..1.15f,
                { v -> camera?.let { String.format(Locale.US, "%.2f× · %.0f°×%.0f°", v, it.fovH / settings.fovScale * v, it.fovV / settings.fovScale * v) } ?: String.format(Locale.US, "%.2f×", v) },
                "Gdy krawędzie klatek nie pasują przy obrocie – zmniejsz lub zwiększ.",
                onChange = { fov = it },
                onDone = {
                    settings.fovScale = fov
                    effects.cameraChanged()
                },
            )
            SliderRow(
                "Opóźnienie synchronizacji", settings.syncDelayMs.toFloat(), 0f..120f, { "${it.roundToInt()} ms" },
                "Przesunięcie czasu klatki względem czujnika. Gdy obraz „ciągnie się” za ruchem – zwiększ.",
            ) { settings.syncDelayMs = it.roundToInt() }
            ChoiceRow("Źródło orientacji", OrientationSource.entries, settings.source, { it.label }, { it.detail }) {
                settings.source = it
                effects.sensorChanged()
            }

            SectionTitle("Podgląd")
            SliderRow("Oddalenie podglądu", settings.viewZoomOut, 1f..3f, { String.format(Locale.US, "%.1f×", it) }, "Ile panoramy widać wokół kadru kamery.") { settings.viewZoomOut = it }
            SliderRow("Krycie kadru na żywo", settings.liveOpacity, 0.2f..1f, { "${(it * 100).roundToInt()}%" }) { settings.liveOpacity = it }
            ToggleRow("Siatka sfery", "Linie co 15° i horyzont w nieuchwyconych miejscach.", settings.showGrid) { settings.showGrid = it }
            ToggleRow("Poziomica", "Pochylenie telefonu na środku ekranu.", settings.showLevel) { settings.showLevel = it }
            ToggleRow("Wibracje", null, settings.haptics) { settings.haptics = it }
            ToggleRow("Dźwięk migawki", "W trybie punktów.", settings.sounds) { settings.sounds = it }

            SectionTitle("Eksport")
            SliderRow("Jakość JPEG", settings.jpegQuality.toFloat(), 70f..100f, { "${it.roundToInt()}" }) { settings.jpegQuality = it.roundToInt() }
            ChoiceRow("Puste miejsca", HoleFill.entries, settings.holeFill, { it.label }) { settings.holeFill = it }
            ChoiceRow("Kadrowanie", ExportCrop.entries, settings.crop, { it.label }, { it.detail }) { settings.crop = it }
            ToggleRow("Metadane 360°", "Znaczniki GPano – Google Zdjęcia, Facebook i inne pokażą zdjęcie jako sferę.", settings.writeXmp) { settings.writeXmp = it }

            SectionTitle("Przeglądarka")
            ToggleRow("Automatyczny obrót", "Panorama powoli obraca się sama.", settings.autoRotate) { settings.autoRotate = it }
            ToggleRow("Rozglądanie telefonem", "Domyślnie steruj widokiem ruchem telefonu.", settings.viewerGyro) { settings.viewerGyro = it }

            SectionTitle("Ogólne")
            ToggleRow("Sprawdzaj aktualizacje", "Informacja o nowej wersji na ekranie głównym.", settings.updates) { settings.updates = it }
            Spacer(Modifier.height(8.dp))
            OutlineButton("Przywróć domyślne", Icons.Outlined.RestartAlt) {
                settings.resetStitching()
                effects.cameraChanged()
                effects.gridChanged()
                effects.sensorChanged()
            }

            SectionTitle("O aplikacji")
            Text("Open360 ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
            Text(
                "Panoramy 360° tworzone na żywo z aparatu. Zszywanie na GPU w projekcji równoprostokątnej; " +
                    "metody łączenia, ważenie od brzegu kadru i wyrównanie ekspozycji wzorowane na OpenPano " +
                    "(Yuxin Wu, licencja MIT).",
                color = Palette.TextDim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Credits()
            Spacer(Modifier.height(28.dp))
        }
    }
}

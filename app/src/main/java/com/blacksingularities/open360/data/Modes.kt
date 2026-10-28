package com.blacksingularities.open360.data

/** Ciągłe malowanie albo zdjęcia w punktach siatki. */
enum class CaptureMode(val label: String, val detail: String) {
    CONTINUOUS("Malowanie", "Panorama rośnie na żywo, gdy wodzisz telefonem."),
    POINTS("Punkty", "Celuj w kropki – zdjęcie robi się samo, gdy kadr się ustabilizuje."),
}

/** Jaki fragment sfery ma powstać (siatka punktów i licznik pokrycia). */
enum class CaptureRange(val label: String, val detail: String, val latMin: Float, val latMax: Float, val lonSpan: Float) {
    SPHERE("Pełna sfera", "360° × 180° – zenit i nadir", -90f, 90f, 360f),
    NO_NADIR("Bez nadiru", "360° bez samego dołu (statyw, stopy)", -60f, 90f, 360f),
    BAND("Pas horyzontu", "360° wokół, ±30° w pionie", -30f, 30f, 360f),
    HALF("Pół panoramy", "180° przed sobą, ±45° w pionie", -45f, 45f, 180f),
}

# Open360

Panoramy 360° na żywo z aparatu telefonu (Android 10+). Wodzisz telefonem, a sfera wypełnia się na ekranie w czasie rzeczywistym — nie trzeba robić całej sfery, zapisujesz w dowolnej chwili.

## Możliwości

- **Podgląd na żywo** – kadr kamery nałożony na rosnącą panoramę, siatka sfery w pustych miejscach, poziomica.
- **Zszywanie na GPU** (OpenGL ES 3) do płótna równoprostokątnego 2K–8K.
- **Łączenie klatek**: najostrzejsza, miękkie przenikanie (średnia ważona odległością od brzegu kadru), tylko puste miejsca, nadpisywanie.
- **Korekta obrazem** – każda klatka jest dopasowywana (NCC) do już zszytej panoramy, co usuwa dryf żyroskopu.
- **Wyrównanie ekspozycji**, blokada AE/AWB, korekta winietowania, kompensacja EV, obiektyw szerokokątny.
- **Tryby**: malowanie albo punkty (siatka celów, automatyczne zdjęcie po ustabilizowaniu kadru).
- **Zakresy**: pełna sfera, bez nadiru, pas horyzontu, pół panoramy.
- **Eksport** JPEG z metadanymi GPano (Google Zdjęcia, Facebook), wypełnianie dziur, kadrowanie do treści.
- **Wybór pliku dla innych aplikacji** – przy przesyłaniu zdjęcia w przeglądarce (lub gdziekolwiek trzeba wybrać plik) można wskazać panoramę z Open360 albo zrobić nową.
- **Przeglądarka**: perspektywa, mała planeta, tunel, mapa; rozglądanie telefonem, automatyczny obrót, tryb VR, zapis widoku jako zdjęcia.

## Budowanie

```bash
gradle :app:assembleRelease
```

Wydania budują się automatycznie wyłącznie po wypchnięciu tagu `v*` (np. `v1.0.0`).

## Podziękowania

Metody łączenia klatek, ważenie od brzegu kadru i wyrównanie ekspozycji są wzorowane na projekcie OpenPano (Yuxin Wu, licencja MIT).

---

**Pomysł i wykonanie: Adam Rędzikowski · [redzikowski.com](https://redzikowski.com)**

<a href="https://buycoffee.to/adamredzikowski" target="_blank"><img src="https://buycoffee.to/static/img/share/share-button-dark--pl.png" style="width: 351px; height: 92px" width="351" height="92" alt="Postaw kawę dla adamredzikowski na buycoffee.to"></a>

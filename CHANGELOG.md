# Zmiany

## 1.1.0

- Dokładniejsze dopasowanie klatek: szukanie przesunięcia do ±4° w poziomie i pionie oraz przechyłu do ±3°, w trzech coraz drobniejszych krokach (1° → 0,5° → 0,25°).
- Dwukrotnie dokładniejsza panorama odniesienia (0,25°/px) i gęstsze próbkowanie klatki.
- Klatki sprzeczne z tym, co już zszyte, nie są malowane – koniec „rozjeżdżania się” tła przy obrocie dookoła.
- Samokalibracja kąta widzenia aparatu z dopasowania obrazem (nowa opcja, domyślnie włączona).
- Obsługa klatek z aparatu w dowolnej orientacji bufora.

## 1.0.0

- Panoramy 360° tworzone na żywo: kadr kamery wtapia się w sferę tam, gdzie celujesz.
- Zszywanie na GPU w projekcji równoprostokątnej (2K–8K), cztery sposoby łączenia klatek.
- Korekta pozy obrazem (usuwa dryf żyroskopu), wyrównanie ekspozycji, korekta winietowania.
- Tryby: malowanie i punkty (siatka celów z automatycznym zdjęciem); zakresy: pełna sfera, bez nadiru, pas horyzontu, pół panoramy.
- Podgląd w trakcie: na żywo, mapa, mała planeta; cofanie ostatniego fragmentu.
- Eksport JPEG z metadanymi GPano, wypełnianie dziur, kadrowanie do treści.
- Przeglądarka: perspektywa, mała planeta, tunel, mapa, rozglądanie telefonem, automatyczny obrót, tryb gogli VR, zapis widoku jako zdjęcia.

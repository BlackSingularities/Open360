# Zmiany

## 2.0.0

**Ostrzej i czyściej**
- Rozmazane klatki nie trafiają do panoramy: rozmycie liczone z prędkości obrotu × czasu naświetlania (w pikselach panoramy) oraz mierzone w samym obrazie – klatka mniej ostra niż to, co już zszyte w tym miejscu, jest odrzucana (łapie też nieostrość i drgania, których żyroskop nie widzi).
- Ostrzeżenia o zbyt szybkim ruchu: pasek tempa z limitem prędkości (biały / pomarańczowy / czerwony), czerwony komunikat „Za szybko!”, informacja o odrzuconej klatce i krótka wibracja ostrzegawcza; licznik odrzuconych klatek.
- Na czas zbierania krótszy czas naświetlania przy wyższej czułości (ta sama jasność) – mniej rozmycia; ostrość zablokowana, stabilizacja optyczna wyłączona.
- Dokładne dopasowanie każdej zszywanej klatki metodą Gaussa-Newtona na sferze (obrót 3D, skala ogniskowej, fotometria, wagi Hubera) – dokładność ułamka piksela zamiast kroku 0,25°.
- Korekta migawki szczelinowej (rolling shutter) i poza z chwili środka naświetlania.
- Nowe szwy: każdy fragment z klatki, w której był najbliżej środka kadru, z wąskim pasem przenikania; przy ruchomych obiektach przejście ostre zamiast „duchów”. Ostrzejsza klatka wypiera rozmytą.
- Klatki kluczowe zamiast zszywania wszystkiego: mniej szwów i mniej okazji do rozmycia.
- Klatki z kamery mipmapowane i filtrowane anizotropowo – bez schodków i migotania przy mniejszych panoramach.
- Wyrównanie ekspozycji w świetle liniowym, samouczące się winietowanie, samokalibracja kąta widzenia i opóźnienia czujnika.
- Zapis: wypełnianie dziur metodą push-pull (gładko, bez bloków) i delikatne, adaptacyjne wyostrzanie.

**Większe**
- Panoramy 16K (16384 × 8192, 134 Mpx) na telefonach z co najmniej ~6 GB pamięci – płótno w kafelkach, zapis strumieniowy wielowątkowym koderem JPEG.
- Klatki do 2560p; domyślnie rozdzielczość klatek dobierana do rozdzielczości panoramy.
- Klatki YUV prosto z aparatu (konwersja na GPU) – mniej pracy procesora przy dużych klatkach.
- Mniejsze zużycie pamięci grafiki (bez drugiej kopii płótna).

**Prościej**
- Ustawienia skrócone do: rozdzielczość panoramy, rozdzielczość klatek, kompensacja ekspozycji, obiektyw szerokokątny, dźwięk i wibracje, aktualizacje. Reszta dobiera się sama.

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

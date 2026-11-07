# Zmiany

## 2.3.0

- Globalne dopasowanie przy zapisie: wszystkie zachowane klatki są dopasowywane naraz. W parach nachodzących się klatek śledzone są punkty o silnej teksturze (piramidalny Lucas-Kanade z testem w obie strony i RANSAC), a bundle adjustment wyznacza obroty wszystkich klatek, kąt widzenia i dystorsję obiektywu. Klatki nie rozjeżdżają się już o kilka stopni, bo o ich położeniu nie decyduje głównie żyroskop.
- Szwy omijają ruchome obiekty i miejsca, w których klatki się nie zgadzają (graph-cut na siatce 0,5°), zamiast przecinać autobusy i słupy w połowie drogi między środkami kadrów.
- Wyrównanie jasności między wszystkimi klatkami naraz (także między osobnymi seriami zdjęć z inną ekspozycją).
- Model dystorsji obiektywu (beczka/poduszka) w geometrii klatki i w shaderach.
- Samokalibracja kąta widzenia i dystorsji podczas robienia zdjęć, zapamiętywana dla każdego obiektywu. Skala jest mocno przyciągana do danych aparatu, a z panoram z dużą paralaksą kalibracja nie jest zapisywana.
- Na żywo obraz może dosunąć klatkę o 1,6° (wcześniej 0,35°) – podgląd lepiej trzyma szwy przy błędach czujnika.
- Naprawiony kąt widzenia obiektywu szerokokątnego (powiększenie < 1×): ogniskowa innego obiektywu nie jest już łączona z rozmiarem matrycy głównego.
- Ostrzeżenie po zapisie, gdy bliskie przedmioty przesuwały się względem tła (obracanie się z telefonem zamiast wokół obiektywu).

## 2.2.5

- Minimalistyczny ekran główny: tytuł, przyciski „Nowa panorama” i „Otwórz zdjęcie 360°” oraz lista panoram – bez dużego panelu z opisem.

## 2.2.4

- Bez żadnych wzmianek o GitHubie w aplikacji: nowy adres serwera aktualizacji, komunikaty błędów bez nazw zewnętrznych serwerów.

## 2.2.3

- Komunikat o nowej wersji bez listy zmian, linków i informacji, skąd pobierana jest aktualizacja.

## 2.2.2

- Dopasowane kolorystycznie tła ikon aplikacji i spójne zasoby graficzne.
- Nowa ikona aplikacji, warianty systemowe i odświeżona grafika na stronie projektu.

## 2.2.0

- Naprawione „domykanie się” koła za wcześnie przy obracaniu się w miejscu z telefonem w ręce: dopasowanie obrazem sumowało paralaksę bliskich przedmiotów (aparat porusza się po okręgu), więc panorama „myślała”, że obrót jest o kilkanaście procent większy, i nakładała obraz z początku zbyt wcześnie. Teraz położenie klatek wyznacza żyroskop, a obraz poprawia tylko jego powolny dryf i drobne lokalne przesunięcia.
- Kąt widzenia z faktycznej ogniskowej aparatu (telefony z kilkoma obiektywami podawały listę ogniskowych), bez samokalibracji z obrazu, która mogła utrwalić błędną skalę.

## 2.1.0

- Domyślnie panorama 8K i klatki 1440p.
- Limit prędkości obrotu nigdy poniżej 3°/s – drżenie ręki nie odrzuca już klatek przy słabym świetle i dużych rozdzielczościach.
- Open360 jako źródło plików: gdy przeglądarka lub inna aplikacja prosi o wybranie zdjęcia (np. przy przesyłaniu na stronę), można wskazać zapisaną panoramę albo od razu zrobić nową – trafi prosto do tej aplikacji.
- Aktualizacje pobierają się w aplikacji (z paskiem postępu) i od razu otwierają instalator – bez przechodzenia do GitHuba w przeglądarce.
- Z ustawień zniknęła lista funkcji działających automatycznie.
- Wydanie zawiera jeden plik: Open360-<wersja>.apk.

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

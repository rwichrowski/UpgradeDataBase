# UpgradeDataBase

Narzędzie do masowego wdrażania skryptów SQL (procedury składowane, funkcje, triggery) na wielu bazach danych MS SQL Server jednocześnie.

## Do czego służy

Pozwala uruchomić zestaw skryptów `.sql` na dowolnej liczbie baz danych w jednym przebiegu. Przydatne przy wdrożeniach na środowiskach DEV / TEST / PROD, gdy te same procedury lub funkcje trzeba zaktualizować na wielu serwerach.

Narzędzie automatycznie obsługuje sytuację, gdy skrypt zawiera `ALTER`, a obiekt jeszcze nie istnieje w bazie (lub odwrotnie — `CREATE`, a obiekt już istnieje). W takim przypadku próbuje zamienić `ALTER` na `CREATE` (lub `CREATE` na `ALTER`) i ponawia wykonanie.

## Wymagania

- Java 11+
- Maven
- Dostęp sieciowy do docelowych instancji MS SQL Server

## Konfiguracja

Przed uruchomieniem utwórz dwa pliki konfiguracyjne w katalogu roboczym (są ignorowane przez git — nie trafią do repozytorium).

### `targets.txt` — lista baz docelowych

Każda linia to jedna baza danych w formacie:

```
ENV;jdbc:sqlserver://HOST:PORT;DatabaseName=DBNAME;user=USER;password=PASS
```

Linie zaczynające się od `#` są komentarzami i są pomijane.

**Przykład:**

```
# Środowisko deweloperskie
DEV;jdbc:sqlserver://localhost:1433;DatabaseName=MyAppDev|sa|tajne_haslo

# Środowisko testowe
TEST;jdbc:sqlserver://test-srv:1433;DatabaseName=MyAppTest|deploy_user|haslo123

# Produkcja
PROD;jdbc:sqlserver://prod-srv:1433;DatabaseName=MyApp|deploy_user|haslo_prod
```

Format linii: `NAZWA_SRODOWISKA;URL_JDBC|UZYTKOWNIK|HASLO`

- `NAZWA_SRODOWISKA` — dowolna etykieta wyświetlana w logach (np. `DEV`, `TEST`, `PROD`)
- `URL_JDBC` — standardowy connection string JDBC dla SQL Server
- `UZYTKOWNIK` — login SQL
- `HASLO` — hasło SQL

### `scripts_paths.txt` — lista skryptów SQL do wykonania

Każda linia to pełna lub względna ścieżka do pliku `.sql`. Skrypty są wykonywane w kolejności od góry.

Linie zaczynające się od `#` są komentarzami i są pomijane.

**Przykład:**

```
# Procedury
C:\sql\updates\dbo.usp_GetOrders.sql
C:\sql\updates\dbo.usp_SaveOrder.sql

# Funkcje
C:\sql\updates\dbo.ufn_CalcTotal.sql
```

## Uruchomienie bez IDE (PowerShell)

IntelliJ nie jest potrzebny — wystarczy JDK 11+ i Maven w `PATH`. Sprawdzenie:

```powershell
java -version
mvn -v
```

### 1. Kompilacja

W katalogu projektu:

```powershell
cd C:\git\_priv\UpgradeDataBase
mvn clean package
```

Maven pobiera zależności (sterownik `mssql-jdbc`, `commons-io`) i buduje **jeden samowystarczalny jar** (plugin `maven-shade-plugin`):

```
target\UpgradeDataBase-1.0-SNAPSHOT.jar           <- ten uruchamiasz (klasy + sterownik JDBC + Main-Class)
target\original-UpgradeDataBase-1.0-SNAPSHOT.jar  <- sam kod bez zależności, do pominięcia
```

Źródła leżą w `src/com/...` (nie w standardowym `src/main/java`), dlatego `pom.xml` ma ustawione `<sourceDirectory>src</sourceDirectory>`.

### 2. Pliki konfiguracyjne

W katalogu, **z którego uruchamiasz** program (domyślnie katalog projektu), muszą leżeć `targets.txt` i `scripts_paths.txt` — format opisany w sekcji [Konfiguracja](#konfiguracja). Program szuka ich po ścieżce względnej, więc liczy się bieżący katalog PowerShella, a nie położenie jara.

```powershell
New-Item targets.txt -ItemType File        # jeśli jeszcze nie ma
New-Item scripts_paths.txt -ItemType File
notepad targets.txt
notepad scripts_paths.txt
```

### 3. Uruchomienie

```powershell
cd C:\git\_priv\UpgradeDataBase
java -jar .\target\UpgradeDataBase-1.0-SNAPSHOT.jar
```

Jar można też skopiować w dowolne miejsce (np. `C:\Tools\UpgradeDataBase\`) razem z oboma plikami `.txt` i uruchamiać stamtąd — nie potrzebuje Mavena ani repozytorium, tylko Javy.

Gdy polskie znaki w konsoli wychodzą krzaczkami, przed uruchomieniem przełącz stronę kodową na UTF-8:

```powershell
chcp 65001
java "-Dstdout.encoding=UTF-8" -jar .\target\UpgradeDataBase-1.0-SNAPSHOT.jar
```

### Alternatywa: prosto ze źródeł przez Maven

Bez budowania jara (kompiluje i od razu uruchamia):

```powershell
mvn compile exec:java "-Dexec.mainClass=com.diligentia.SqlExecutor"
```

W PowerShellu parametr `-D...` trzeba ująć w cudzysłów w całości, inaczej PowerShell rozbije go na dwa argumenty.

## Format wyjścia

```
=== ENV: DEV ===
OK  | DEV | dbo.usp_GetOrders.sql
OK  | DEV | dbo.usp_SaveOrder.sql (ALTER->CREATE)
ERR | DEV | dbo.ufn_CalcTotal.sql | Invalid object name 'dbo.SomeTable'

=== ENV: TEST ===
OK  | TEST | dbo.usp_GetOrders.sql
...

Done.
```

- `OK` — skrypt wykonany pomyślnie
- `OK (ALTER->CREATE)` / `OK (CREATE->ALTER)` — wykonano po automatycznej zamianie słowa kluczowego
- `ERR` — błąd SQL; obok komunikat błędu z serwera

## Porównywanie baz i repozytorium (`diff`)

Drugi tryb tego samego jara: pobiera z bazy wszystkie procedury, funkcje, widoki i triggery (jedno zapytanie do `sys.sql_modules`), zapisuje je jako pliki i porównuje z drugą bazą albo z katalogiem repozytorium.

### Okno (GUI)

```powershell
java -jar .\target\UpgradeDataBase-1.0-SNAPSHOT.jar diff
```

W IntelliJ: konfiguracja *Application*, main class `com.diligentia.SqlExecutor`, *Program arguments* `diff`, *Working directory* — katalog z `targets.txt`.

- **Lewa / Prawa** — lista podpowiada środowiska z `targets.txt` i ostatnio używane katalogi; można też wpisać ścieżkę albo wybrać ją przyciskiem `…`. `⇅ Zamień` zamienia strony.
- **Gałąź git** — obok strony będącej katalogiem w repozytorium widać `gałąź: …` (zielono dla `master`/`main`, pomarańczowo dla innych). Odczyt z `.git/HEAD`, odświeżany przy zmianie strony i po powrocie do okna — więc po przełączeniu gałęzi w IDE etykieta jest aktualna.
- **Porównaj bazy danych** (Enter) — porównuje procedury, funkcje, widoki i triggery; pobiera bazy na bieżąco (w tle, postęp na pasku stanu) i wypełnia tabelę. Gdy katalog repozytorium jest na gałęzi innej niż `master`/`main`, najpierw pyta, czy porównywać z tą gałęzią. Gałąź trafia też do nagłówków kolumn, `report.txt` i planu wdrożenia (z ostrzeżeniem, jeśli zmieniła się od porównania).
- **Filtry** — szukanie po nazwie, typ obiektu i statusy z licznikami; identyczne są domyślnie ukryte. Kolumny sortują się kliknięciem w nagłówek.
- **Dwuklik / Enter na wierszu** — WinMerge z różnicami tego obiektu (znormalizowane kopie, więc widać tylko prawdziwe różnice). Dla obiektu jednostronnego druga strona jest pusta.
- **Prawy przycisk** — otwarcie pliku źródłowego (plik z repo albo zrzutu bazy), **historia pliku w Git Extensions** (wszystkie commity zmieniające plik; aktywne tylko dla pliku leżącego w repozytorium git; skrót **H** na zaznaczonym wierszu), kopiowanie nazwy obiektu i ścieżek.
- **Porównaj słowniki** — porównanie danych tabel słownikowych między dwiema bazami, opis niżej w [Porównanie słowników](#porównanie-słowników-dane-tabel).
- **Dół okna** — `Ostrzeżenia (n)`, `Raport` (report.txt), `Wszystko w WinMerge` (porównanie katalogów z samymi rozjechanymi obiektami).

WinMerge i Git Extensions są szukane w `Program Files`, `%LOCALAPPDATA%\Programs` i w `PATH`; gdy ich nie ma, okno zapyta o `WinMergeU.exe` / `GitExtensions.exe` i zapamięta wybór. Ostatnie strony, opcje i rozmiar okna są zapamiętywane w rejestrze użytkownika (Java Preferences).

Lista środowisk w oknie i w `diff compare` obejmuje także **zakomentowane** linie `targets.txt` — porównać można z każdym zdefiniowanym środowiskiem, niezależnie od tego, które jest akurat aktywne do wdrożenia.

### Wdrożenie wersji z repozytorium

Dostępne tylko, gdy jedna strona porównania to baza, a druga katalog repozytorium — wtedy pod tabelą pojawia się pasek **Wdrożenie z repozytorium**.

1. Zaznacz obiekty w kolumnie `✔` (checkbox mają tylko obiekty, które są w repo; `Zaznacz widoczne` zaznacza wszystko, co przechodzi bieżące filtry).
2. Oba przyciski najpierw pokazują plan (listę plików, uwagi i co zostanie nadpisane), a po potwierdzeniu zapisują:
   - `scripts_paths.txt` — ścieżki wybranych plików z repo (bez duplikatów, w kolejności: funkcje, widoki, procedury, triggery),
   - `targets.txt` — aktywne zostaje tylko środowisko docelowe (odkomentowane, gdy było zakomentowane), pozostałe definicje środowisk są komentowane `# `; inne linie bez zmian.
3. Dalej zależnie od przycisku:
   - **`Wdróż teraz (n) → ENV`** — od razu wykonuje skrypty tak jak `SqlExecutor` bez argumentów. Log `OK` / `ERR` pojawia się na żywo w osobnym oknie, na końcu jest podsumowanie i przycisk `Porównaj ponownie`. W oknie potwierdzenia domyślnie zaznaczone jest `Anuluj`.
   - **`Tylko przygotuj pliki (n)`** — kończy na zapisie plików; wdrożenie uruchamiasz sam (`SqlExecutor` bez argumentów w IntelliJ albo `java -jar`), potem `Porównaj bazy danych`.

Uwaga: plik z kilkoma obiektami rozdzielonymi `GO` jest oznaczany ostrzeżeniem — `SqlExecutor` usuwa `GO` i wykonuje cały plik jako jeden batch, co dla kilku `CREATE` kończy się błędem.

### Porównanie słowników (dane tabel)

Przycisk **Porównaj słowniki** porównuje **dane** tabel konfiguracyjnych między dwiema bazami — obie strony muszą być środowiskami z `targets.txt` (danych słownikowych nie ma w repozytorium). Porównywane tabele i klucze, po których dopasowywane są wiersze:

| Tabela | Klucz dopasowania | Pomijane kolumny | Podział |
|---|---|---|---|
| `Dict` | `DictCatId`, `Value` | `id` (IDENTITY) | osobny zakres na każdy słownik, z nazwą z `DictCat.Desc` |
| `Parameters` | `Name` | — | — |
| `TRCORE_TruckTypes` | `TypeDesc` | `Id` | — |
| `StoreAreas` | `Name` | `WMSObjectId` | — |
| `LogicalAreas` | `Name` | `WMSObjectID` | — |
| `SUTypes` | `Desc` | `Id` | — |

Klucze są naturalne, bo identyfikatory IDENTITY w każdej bazie są inne. Lista jest w `DataTableSpec.defaults()` — kolejna tabela to jedna linia.

Widok zamiast tabeli obiektów:

- **Lewy panel — zakresy**: tabela albo słownik (`Dict 12 – Typy dokumentów`) z licznikami `Różne / T. lewa / T. prawa / Ident.`. Domyślnie ukryte zakresy bez różnic. Na czerwono zakres, którego tabeli brakuje po jednej stronie.
- **Prawy górny — wiersze zakresu**: status, klucz (pogrubiony) i kolumny. Różniąca się komórka jest pomarańczowa i pokazuje `lewa → prawa`; wiersze jednostronne lekko podbarwione. `Tylko kolumny z różnicami` (domyślnie włączone) chowa kolumny, w których w tym zakresie nic się nie różni.
- **Prawy dolny — szczegół wiersza**: `Kolumna | Lewa | Prawa` dla wszystkich kolumn, różnice podświetlone.
- **Szukaj** filtruje zakresy i wiersze po wartościach z obu stron (np. nazwa parametru albo `TextCode`); checkboxy statusów jak w porównaniu obiektów.
- `Ignoruj białe znaki` / `Ignoruj wielkość liter` działają też na wartości. `NULL` różni się od pustego tekstu. Kolumny binarne (`Image`) porównywane po długości i SHA-1.
- Kolumna obecna tylko po jednej stronie jest pomijana i zgłaszana w `Ostrzeżenia`; tam też powtórzone klucze.
- `Raport` otwiera `diff\<lewa>__vs__<prawa>\data_report.txt` — tylko różnice, dla zmienionych wierszy same zmienione kolumny (`ShortDesc: 'a' -> 'b'`).

#### Skrypt synchronizujący

Pasek pod tabelami generuje skrypt T-SQL, który w bazie docelowej ustawia dane jak w źródłowej — **tylko jako plik, nic nie jest wykonywane**.

1. Zaznacz wiersze w kolumnie `✔` (checkbox mają tylko wiersze z różnicą). `Zaznacz widoczne` — widoczne po filtrach w bieżącym zakresie, `Zaznacz wszystkie różnice` — we wszystkich tabelach i słownikach. Zaznaczenie obejmuje wiele zakresów naraz; ile wierszy jest zaznaczonych w danym zakresie, widać w kolumnie `✔` na liście po lewej.
2. `Skrypt LEWA → PRAWA (n)` albo `Skrypt PRAWA → LEWA (n)` — kierunek wybierasz przyciskiem. Skrypt zapisuje się do `diff\<lewa>__vs__<prawa>\sync_<źródło>_to_<cel>.sql` i pokazuje w oknie (`Otwórz plik`, `Kopiuj do schowka`).

Co jest w skrypcie (dla wybranego kierunku):

| Wiersz | Instrukcja |
|---|---|
| tylko w źródle | `IF NOT EXISTS (...) INSERT` — wszystkie kolumny poza IDENTITY |
| w obu, różni się | `UPDATE` tylko zmienionych kolumn, `WHERE` po kluczu z bazy docelowej |
| tylko w celu | `DELETE` po kluczu |

- W obrębie tabeli kolejność `DELETE`, `UPDATE`, `INSERT` (zwolnienie unikalnych nazw przed wstawieniem).
- Na początku strażnik `IF DB_NAME() <> N'<baza z targets.txt>'` — skrypt uruchomiony na innej bazie nic nie zrobi (`SET NOEXEC ON`).
- Całość w `BEGIN TRAN` z `SET XACT_ABORT ON`, na końcu **`ROLLBACK TRAN`** — uruchom, sprawdź wynik, potem zamień na `COMMIT TRAN`.
- Liczby bez cudzysłowu, daty w formacie ISO (`'2026-11-13T05:58:00.0'`), tekst jako `N'...'`.
- Kolumny binarne (`TRCORE_TruckTypes.Image`) nie są przenoszone — w skrypcie jest komentarz `UWAGA`. Wiersze tabeli, której nie ma w bazie docelowej, są pominięte i wypisane w nagłówku.
- Kolumny z ID innych tabel (`LogicalAreas.StoreId`, `TRCORE_TruckTypes.IdDoc`) przechodzą 1:1 — sprawdź, czy te ID znaczą to samo w bazie docelowej. `INSERT` do `Dict` wymaga istniejącego wpisu w `DictCat`.

### Polecenia konsolowe

```powershell
# zrzut obiektów do snapshots\<ENV>\ (bez nazwy - wszystkie aktywne środowiska z targets.txt)
java -jar .\target\UpgradeDataBase-1.0-SNAPSHOT.jar diff snapshot "PROD"

# baza vs repozytorium
java -jar .\target\UpgradeDataBase-1.0-SNAPSHOT.jar diff compare "PROD" C:\repo\SQL

# baza vs baza, z otwarciem wyniku w WinMerge
java -jar .\target\UpgradeDataBase-1.0-SNAPSHOT.jar diff compare DEV "PROD" --open

# słowniki (dane tabel Dict, Parameters, ...) baza vs baza
java -jar .\target\UpgradeDataBase-1.0-SNAPSHOT.jar diff data DEV "PROD"
```

Strona porównania to **nazwa środowiska z `targets.txt`** (pobierana na bieżąco) albo **ścieżka katalogu** z plikami `.sql` — także wcześniejszego zrzutu z `snapshots\`. Opcje: `-w` ignoruje różnice w białych znakach, `-i` wielkość liter, `--open` otwiera WinMerge.

### Wynik

```
ROZNI SIE    | PROCEDURE | dbo.GUI_GetOrders   | PROD 2026-11-10 14:22 <-> C:\git\...\dbo.GUI_GetOrders.sql
TYLKO LEWA   | FUNCTION  | dbo.fn_Old          | PROD 2025-01-03 09:00
TYLKO PRAWA  | VIEW      | dbo.vw_New          | C:\git\...\dbo.vw_New.View.sql

Podsumowanie: rozni sie 1, tylko lewa 1, tylko prawa 1, szyfrowany 0, identyczne 1840
```

- `diff\<lewa>__vs__<prawa>\left` i `\right` — znormalizowane pliki **tylko** obiektów różnych i jednostronnych; WinMerge w trybie porównania katalogów pokazuje dokładnie tę listę, a dwuklik otwiera różnice w obiekcie. Na końcu raportu jest gotowa komenda WinMerge.
- `diff\...\report.txt` — ten sam raport co w konsoli, z ostrzeżeniami (duplikaty obiektu, pliki bez procedury).
- `snapshots\<ENV>\` — treść obiektów 1:1 z bazy, plik na obiekt (`dbo.X.StoredProcedure.sql`).

Oba katalogi są w `.gitignore` i przy każdym przebiegu ich pliki `.sql` są nadpisywane.

### Co jest uznawane za różnicę

Przed porównaniem obie strony są normalizowane, żeby nie zgłaszać szumu:

- tekst przed nagłówkiem obiektu (`SET ANSI_NULLS`, `/****** Object: ... ******/`, komentarze) jest pomijany,
- `ALTER` / `CREATE OR ALTER` / `PROC` → `CREATE PROCEDURE`, nazwa → `[schemat].[nazwa]` (brak schematu = `dbo`), wielkość liter w nazwie bez znaczenia,
- końce linii, spacje na końcach linii i puste linie na początku/końcu.

Pliki repozytorium są czytane rekurencyjnie; plik może zawierać kilka obiektów rozdzielonych `GO`. Gdy ten sam obiekt występuje kilka razy, liczy się ostatnie wystąpienie (ostrzeżenie w raporcie). Kodowanie rozpoznawane po BOM (UTF-8, UTF-16 z SSMS), bez BOM UTF-8, a w razie błędu windows-1250.

### Ograniczenia

- Tylko procedury, funkcje, widoki i triggery tabel — bez tabel, indeksów, typów i triggerów DDL.
- Login z `targets.txt` potrzebuje `VIEW DEFINITION`; obiekty `WITH ENCRYPTION` mają status `SZYFROWANY`.
- Skrypt wdrożony przez ten program bez `GO` po procedurze zostaje w bazie razem z tym, co stało za nią w pliku (np. `GRANT`) — porównanie pokaże to jako różnicę, bo w bazie faktycznie tak jest.

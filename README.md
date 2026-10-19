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

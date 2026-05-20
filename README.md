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

## Uruchomienie

```bash
mvn compile exec:java -Dexec.mainClass="com.diligentia.SqlExecutor"
```

lub po zbudowaniu jara:

```bash
java -jar target/UpgradeDataBase-1.0-SNAPSHOT.jar
```

Pliki `targets.txt` i `scripts_paths.txt` muszą znajdować się w katalogu, z którego uruchamiasz program.

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

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**UpgradeDataBase** is a Java utility for bulk-deploying SQL scripts (stored procedures, functions, triggers) to multiple MS SQL Server databases in a single run. Designed for multi-environment deployments (e.g. DEV/TEST/PROD across multiple countries).

## Build & Run

```powershell
# Build
mvn clean package

# Run from source (development)
mvn compile exec:java -Dexec.mainClass="com.diligentia.SqlExecutor"

# Run packaged JAR
java -jar target/UpgradeDataBase-1.0-SNAPSHOT.jar
```

Requires Java 11+ and Maven 3.x. No test suite exists.

## Configuration Files (git-ignored)

Both files must be present in the working directory at runtime:

**`targets.txt`** — one database per line:
```
ENV_NAME;jdbc:sqlserver://HOST:PORT;databaseName=DBNAME;encrypt=false|USERNAME|PASSWORD
# Lines starting with # are comments (use to disable a target)
```

**`scripts_paths.txt`** — one absolute SQL file path per line:
```
C:\path\to\dbo.MyProcedure.StoredProcedure.sql
# commented-out paths are skipped
```

## Architecture

The entire application is a single file: `src/com/diligentia/SqlExecutor.java`.

**Execution flow:**
1. Read `targets.txt` → list of `DbTarget` objects (name, JDBC URL, credentials)
2. Read `scripts_paths.txt` → list of validated `File` objects
3. For each target × each script: connect via JDBC and execute

**Key behaviors:**
- SQL is normalized before execution: strips everything before the first `CREATE`/`ALTER` keyword and removes `GO` statements (not valid in JDBC)
- **Auto-recovery**: if `ALTER` fails because the object doesn't exist (SQL errors 208, 15151, 4902), it retries with `CREATE`. If `CREATE` fails because the object already exists (error 2714), it retries with `ALTER`. The output labels these as `OK (ALTER->CREATE)` or `OK (CREATE->ALTER)`.

**Output format:**
```
=== ENV: PRODUKCJA Polska ===
OK  | PRODUKCJA Polska | dbo.MyProc.StoredProcedure.sql
OK  | PRODUKCJA Polska | dbo.MyFunc.sql (ALTER->CREATE)
ERR | PRODUKCJA Polska | dbo.Bad.sql | <error message>
...
Done.
```

## Dependencies

- `mssql-jdbc:10.2.0.jre8` — MS SQL Server JDBC driver
- `commons-io:2.21.0` — Apache Commons IO

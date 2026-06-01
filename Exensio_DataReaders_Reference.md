# Exensio Data Readers — Comprehensive Reference Guide

*Derived from: Exensio Data Readers documentation (Release 4.1+)*  
*PDF Solutions, Inc.*

---

## Table of Contents

1. [Overview & Architecture](#1-overview--architecture)
2. [DpLoad.pl — Automated Data Loading](#2-dploadpl--automated-data-loading)
3. [Database Model & Programs](#3-database-model--programs)
4. [Normalization & Alignment](#4-normalization--alignment)
5. [Format File Language (All Readers)](#5-format-file-language-all-readers)
6. [ASCII Data Reader (Chapter 2)](#6-ascii-data-reader)
7. [LEH Data Reader (Chapter 3)](#7-leh-data-reader)
8. [WEH Data Reader (Chapter 4)](#8-weh-data-reader)
9. [FAB Data Reader (Chapter 5)](#9-fab-data-reader)
10. [STDF3 Data Reader (Chapter 6)](#10-stdf3-data-reader)
11. [STDF4 Worksheet Reader (Chapter 7)](#11-stdf4-worksheet-reader)
12. [Defect Reader (Chapter 8)](#12-defect-reader)
13. [LTX77 Data Reader (Chapter 9)](#13-ltx77-data-reader)
14. [BitMap Reader (Chapter 10)](#14-bitmap-reader)
15. [Events Reader (Chapter 11)](#15-events-reader)
16. [Built-in Function Quick Reference](#16-built-in-function-quick-reference)
17. [Command-Line Options Quick Reference](#17-command-line-options-quick-reference)
18. [Common Errors & Troubleshooting](#18-common-errors--troubleshooting)

---

## 1. Overview & Architecture

### What Are Data Readers?

Exensio data readers import raw datalog files into the analysis environment. They organize data into a standard form regardless of original file format. Data readers access data directly from files or through a database (Informix, Oracle, Cassandra).

### Two Types of Readers

| Type | Description | How They Run |
|------|-------------|--------------|
| **Database (dB) Readers** | Import formatted data into the database for access through Exensio data retrieval | Background, via DpLoad.pl or command line |
| **Worksheet (WS) Readers** | Import formatted data directly into the Exensio–Yield environment | Interactive, via Tools > Import |

### Reader List

| Reader | Code | Type | Chapter | Data Type |
|--------|------|------|---------|-----------|
| ASCII | dbascii | dB | 2 | General parametric |
| LEH | leh | dB | 3 | Parametric (LEH format) |
| WEH | weh | dB | 4 | Parametric (WEH format) |
| FAB | fab | dB | 5 | FDC/process data |
| STDF3 | — | WS | 6 | Standard Test Data Format v3 |
| STDF4 | — | WS/dB | 7 | Standard Test Data Format v4 |
| Defect | defect | dB | 8 | KLARF/Tencor defect data |
| LTX77 | — | WS | 9 | LTX77 test data |
| BitMap | bitmap | dB | 10 | Logical bit fault data |
| Events | events | dB | 11 | FDC/process event data |

### Data Flow

```
Raw Data File ─→ Reader ─→ Format File (.fmt) ─→ Database (Oracle/Informix)
                                                        │
                                                    Exensio–Yield
                                                    waferMAP, dataMINE
```

### DpLoad.pl Reader Codes (for file tracking)

| Reader | pgc_key |
|--------|---------|
| dbascii | -100 |
| bitmap | -101 |
| events | -102 |
| fab | -103 |
| leh | 13 |
| defect | 14 |
| weh | 19 |
| cv | 26 |

---

## 2. DpLoad.pl — Automated Data Loading

### Usage

```bash
DpLoad.pl configFile [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `-once` | Process data files once and exit | Run continuously |
| `-run command` | Execute command on processed files | — |
| `-notify script` | Execute script when file goes to NotProcessed | — |
| `-log [logfile]` | Enable logging | Off |
| `-log_db db` | Enable database logging | Off |
| `-reverse` | Load newest files first | Oldest first |
| `-nosort` | Load by filename order | Oldest first |
| `-files num` | Files per iteration per config entry | 20 (or `$IDS_FILES_PER_LOOP`) |
| `-sleep val` | Sleep seconds after config loop | 10 (or `$IDS_SLEEP_SECONDS`) |
| `-cleanup val` | Age (days) of Processed files to remove | 0 (never) |
| `-modtime val` | Age (seconds) of files before processing | 30 (or `$IDS_MOD_TIME`) |
| `-rm_spaces` | Replace spaces in filenames with underscores | Off |
| `-warn` | Move datafile to Warnings dir with .warn file | — |
| `-mtime` | Sort by modification time (vs. access time) | Access time |
| `-timeout val` | Max runtime in seconds | — |

### Configuration File Format

```
SearchDir:ReaderPath:Extension:ReaderOptions
```

Examples:
```
/data/staging:/bin/dbascii:res:-class 1 -fmt myfile.fmt -db mydb -db_accept
/data/staging:/bin/defect:kla:-fmt klarf.fmt -db mydb -db_accept
NA:/somedir/UpStat:NA:-from 0 -to 2 dbname1 dbname2
```

### Special Options in Config File

| Keyword | Description |
|---------|-------------|
| `DPIMPORT` | Auto-generate format file via `dpimport_fmt.pl` |
| `-DPIMPORT_LOWERCASE` | Force lot/wafer IDs to lowercase |
| `-DPIMPORT_UPPERCASE` | Force lot/wafer IDs to uppercase |
| `-DPIMPORT_NOBINCOLOR` | Ignore bin colors in dpimport_fmt |

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `IDS_FILES_PER_LOOP` | Files per iteration per entry | 5 |
| `IDS_SLEEP_SECONDS` | Sleep time between iterations | 10s |
| `IDS_CLEANUP_DAYS` | Days before deleting Processed files | 30 |
| `IDS_RM_UNUSED_DAYS` | Days before deleting UnusedFiles | 10 |
| `IDS_MOD_TIME` | Seconds to wait before processing new file | 10 |

### File Tracking Error Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 90 | Not processed → NotProcessed dir |
| 4 | Rework → ReworkedFiles dir |
| 9 | Rejected → UnusedFiles dir |
| 10 | Not accepted, kept in staging |
| 80 | Loaded with warnings |

### How to Run

```bash
# With log
DpLoad.pl ./DpLoad.pl cfgfile.cfg > MyLog &

# Without log
DpLoad.pl cfgfile.cfg > /dev/null &

# One-time run
DpLoad.pl cfgfile.cfg -once
```

---

## 3. Database Model & Programs

### Program

A **program** is a set of parametric tests stable over time with a constant set of test limits. Each program generates entries in the `PROGRAM` table and instantiates tables:

```
RES...  WAF...  LOT...  LIM...  DEF...
         PROGRAM
BIN_LOG  HIST_BIN  CONDITION
```

### Program Types

| Type | Description |
|------|-------------|
| **static** (default) | Tables never change; extra tests in future files are ignored |
| **semi_dynamic** | Like static, but fills OP_LOG fields if available |
| **dynamic** | Tables auto-expand for new tests (computationally expensive) |

### Program Classes

32 predefined classes reserved by Exensio. Key classes:

| Class # | Description |
|---------|-------------|
| 1 | Wafer Sort |
| 4 | Bin Map |
| 5 | PCM |
| 7 | MultiBin |

Program classes control which tables are populated (RES, LOT, WAF, LIM, BIN_LOG, HIST_BIN) and which statistics are calculated.

### Statistics Available per Program Class

`cnt`, `avg`, `stdev`, `q1`, `q2`, `q3`, `p1`, `p5`, `p10`, `p90`, `p95`, `p99`, `nlo`, `nuo`, `nls`, `nus`, `lol`, `uol`, `min`, `max`, `sum`, `ss`

### Limits

Eight types of limits:
- **LSL** / **HSL** — Low/High Spec Limits
- **LPL** / **HPL** — Low/High Production Limits
- **LOL** / **HOL** — Low/High Outlier Limits
- **LWL** / **HWL** — Low/High What-If Limits

Limits can be in data files (inserted only when program is new) or separate files (can update existing programs with `-limitsonly`).

Multiple limit sets supported via `DbHistLimits` built-in function.

### Bin Summaries

Stored in `BIN_LOG` (lot/wafer level) and `HIST_BIN` (program level).

**Yield Model**: `y = m * e^(-A*d0)`

Where:
- `m` — cluster factor (scale to exponential fit)
- `d0` — defect density
- `y1` through `y5` — yields for die groups 1x1, 2x1, 1x2, 2x2, 4x4

Prerequisites for yield calculations: ≥20 occurrences of die groups.

---

## 4. Normalization & Alignment

### What Is Normalization?

Normalization transforms raw data and wafer configuration so that:

1. All inspection data oriented to a global database orientation
2. Die index (0,0) includes the physical wafer center
3. Positive X is right, positive Y is up

### Center Die

The center die contains the physical wafer center. If wafer center lies on a die edge (after normalization), lower and left edges belong to that die; upper and right edges belong to neighbors.

### How the Defect Reader Determines Center Die

Using the 8th and 9th arguments in `vDbWmapCfg()` — the vector from wafer center to lower left corner of die (0,0):
- If vector dimensions < die dimensions → wafer center is in die (0,0), no shifting
- Otherwise, reader shifts die indexes so the vector is < die dimensions and negative
- If wafer center is within 200 microns of die edge, reader forces center to nearest edge

### Key KLARF Fields for Wafer Configuration

| KLARF Field | Determines |
|-------------|------------|
| `SampleSize` | Wafer diameter |
| `SampleOrientationMarkType` | Flat type (NOTCH/FLAT) |
| `OrientationMarkLocation` | Testing orientation (DOWN/UP/LEFT/RIGHT) |
| `DiePitch` | Die dimensions (width × height) |
| `SampleCenterLocation` | Distance from lower-left of die to wafer center |

### Reticle Offset Normalization

Reticle row/column offsets are normalized with wafer rotation. Column numbers increase to the right, row numbers increase going down — regardless of wafer orientation.

### BinMap Alignment

The ASCII reader supports `-dfalign` to align a binmap program to a defect program. Only ONE binmap can be aligned per defect program. If UpStat finds multiple binmap programs aligned to one defect program, it returns an error.

### UpStat

UpStat updates lot-level database statistics (LOT... tables). It calculates:
- Lot-level q1, q2, q3 (requires all raw data for a lot)
- Lot-level yield parameters (m, d0, y1–y5) in BIN_LOG

Rows are added to `UPDATE_STATS` when:
1. Multiple data files for the same lot
2. Program/lot masks provided on command line
3. Lot has a source (parent) lot

---

## 5. Format File Language (All Readers)

### Structure

```
SCRIPT format_name
CONST        ← Constants block (optional)
  ...
VAR          ← Variables block (required)
  ...
BEGIN        ← Main processing block (required)
  ...
END
```

### Data Types

| Type | Size | Notes |
|------|------|-------|
| `integer` | 4 bytes | — |
| `real` | 4 bytes | — |
| `char` | 1 byte | — |
| `string` | max 254 chars | Events reader: 255 chars; others: 64 |
| `boolean` | — | `true` or `false` |

### Constants

```
CONST
  job_nam = "MyJob"      // String
  NumOfBins = 32          // Integer
```

### Variable Declaration

```
VAR
  TestNum Integer
  TestName String
  VCC[6] integer          // 1D array
  Pin[2,4] string         // 2D array
```

Arrays auto-reallocate to double size when bounds exceeded.

### Conditions & Indexes

```
Variable1 Cond          // Condition
Variable1 KeyCond       // Key condition
Variable1 LimCond       // Limit condition
Variable1 Index         // Index
Variable1 KeyIndex      // Key index
Variable1 Result        // Result
```

**dB readers** first three conditions must be:
1. TestName (String)
2. Unit (String)
3. TestNum (Integer)

**Minimum**: one key condition + one key index.

### Operators

| Type | Operators |
|------|-----------|
| Arithmetic | `+`, `-`, `*`, `/`, `mod` |
| Assignment | `=` |
| Logical | `OR`, `AND`, `NOT` |
| Relational | `EQ`/`=`, `NE`/`<>`, `GE`/`>=`, `GT`/`>`, `LE`/`<=`, `LT`/`<` |

### Loop Control

```
For i = 1 To 10
  statements
End For

While condition
  statements
End While
```

### Conditional Control

```
If condition
  if-statements
Else If condition2
  elseif-statements
Else
  else-statements
End If
```

### Comments

```
/* block comment */
// line comment
```

### Special Constants

| Name | Value |
|------|-------|
| `'NL'` | New Line |
| `'HT'` | Horizontal Tab |
| `'VT'` | Vertical Tab |
| `'FF'` | Form Feed |
| `'CR'` | Carriage Return |
| `'SQ'` | Single Quote |

---

## 6. ASCII Data Reader

### Running

```bash
# Compile format file without executing
dbascii datafile -fmt format.fmt

# Run with database output
dbascii datafile -db mydb -fmt format.fmt -db_accept

# With options
dbascii datafile -db mydb -fmt format.fmt -db_accept -class 1 -normalize
```

### Key Command-Line Options

| Option | Description |
|--------|-------------|
| `-db database` | Target database |
| `-fmt formatfile` | Format file to use |
| `-db_accept` | Auto-set accept_data flag |
| `-class n` | Set program class |
| `-normalize` | Normalize XY coordinates |
| `-dfalign` | Align binmap to defect program |
| `-limitsonly` | Update limits only (no data) |
| `-file_path path` | Path for OpenFile() |
| `-outliers [n]` | Filter outliers (box-plot) |
| `-dboutliers` | Filter outliers (database limits) |
| `-lowercase` / `-uppercase` | Force lot/wafer case |
| `-rework_action n` | Set rework action (1-4) |
| `-nores` | Disable RES table loading |
| `-arg string` | Pass string to format file |

### Key Built-in Functions — Database

| Function | Description |
|----------|-------------|
| `DbProgram` / `vDbProgram(str)` | Set test program name (first DB function to call) |
| `DbFab` / `vDbFab(str)` | Set fab |
| `DbTechnology` / `vDbTechnology(str)` | Set technology |
| `DbProcess` / `vDbProcess(str)` | Set process |
| `DbProduct` / `vDbProduct(str)` | Set product |
| `DbStage` / `vDbStage(str)` | Set stage |
| `DbLot` / `vDbLot(str)` | Set lot ID |
| `DbSrcLot` / `vDbSrcLot(str)` | Set source lot ID |
| `DbStep` / `vDbStep(str)` | Set step ID |
| `DbResultTime` / `vDbResultTime(str, fmt)` | Set result timestamp |
| `DbSetupTime` / `vDbSetupTime(str, fmt)` | Set setup timestamp |
| `DbTester` / `vDbTester(str)` | Set tester name |
| `DbTesterType` / `vDbTesterType(str)` | Set tester type |
| `DbEquip` / `vDbEquip(str, n)` | Set equipment |
| `DbOperator` / `vDbOperator(str)` | Set operator |
| `DbCustomer` / `vDbCustomer(str)` | Set customer |

### Key Built-in Functions — Wafer Map

| Function | Description |
|----------|-------------|
| `vDbWmapCfg(name, wfSize, units, flat, flatType, dieWd, dieHt, cx, cy, posX, posY, rows, cols, rowOff, colOff)` | Configure wafer map (15 args) |
| `DbWaferIndex(str)` | Identify wafer index |
| `DbDieXYIndexes(str, str)` | Identify die X/Y indexes |
| `DbBinIndex(str)` | Identify bin index |
| `DbBinTest(str)` | Identify bin test |
| `DbNormalizeMap` | Normalize without defect alignment |
| `DbAlignDefect` | Normalize and align with defect data |
| `DbAlignDefectOnly` | Align only (data already normalized) |

### Key Built-in Functions — File Navigation

| Function | Description |
|----------|-------------|
| `Goto(str)` | Search forward for word |
| `GoBackTo(str)` | Search backward for word |
| `GotoEOF` | Move to end of file |
| `GotoBOF` | Move to beginning of file |
| `SkipLines(n)` | Skip N lines (+ forward, - backward) |
| `SkipWords(n)` | Skip N words (+ forward, - backward) |
| `SkipChars(n)` | Skip N chars (+ forward, - backward) |
| `NotEndOfFile` | Returns false at EOF |
| `ErrorCode` | 0 if last function succeeded, 1 if failed |

### Key Built-in Functions — Data Retrieval

| Function | Description |
|----------|-------------|
| `GetInt` | Read current word as integer |
| `GetReal` | Read current word as real |
| `GetWord` | Read current word as string |
| `GetLine` | Read to end of line |
| `GetQuotedWord(char)` | Read quoted string |
| `GetChars(n)` | Read N characters |
| `GetLeftChars(n)` | First N chars of current word |
| `GetRightChars(n)` | Last N chars of current word |
| `GetMidChars(start, n)` | Middle N chars starting at position |
| `GetWordAfter(char)` | Text after character in current word |
| `GetWordBefore(char)` | Text before character in current word |
| `ExtractString` | Current word minus leading numbers |
| `LogResult(result)` | Log result to data table |
| `vLogResult(result, real)` | Log value as result |

### Key Built-in Functions — String Operations

| Function | Description |
|----------|-------------|
| `StrCat(s1, s2)` | Concatenate strings |
| `StrToInt(s)` | String to integer |
| `StrToReal(s)` | String to real |
| `IntToStr(i)` | Integer to string |
| `RealToStr(r)` | Real to string |
| `ToLower(s)` / `ToUpper(s)` | Change case |
| `IsNumber(s)` / `IsString(s)` | Type check |
| `Right(s, n)` / `Left(s, n)` / `Mid(s, start, n)` | Substring |
| `After(s, c)` / `Before(s, c)` | Split at character |
| `StrTrim(s, c)` | Trim leading/trailing char |
| `StrLen(s)` | String length |

### Key Built-in Functions — Separators & Invalid Data

| Function | Description |
|----------|-------------|
| `AddSep(char)` | Add separator (max 5 user-defined) |
| `DelSep(char)` | Delete separator |
| `ClearSep` | Clear all user separators |
| `AddInvReal(r)` / `AddInvInteger(i)` / `AddInvString(s)` | Add to invalid data list |
| `DelInvReal(r)` / `DelInvInteger(i)` / `DelInvString(s)` | Remove from invalid data list |

### Key Built-in Functions — Limits

| Function | Description |
|----------|-------------|
| `LogLimits` | Log current limits to LIM table |
| `LimFile(str)` | Open limits file after data file |
| `ClearLimits` | Reset all limits to invalid |
| `DbHistLimits` | Insert historical limits with `-limitsonly` |

### Key Built-in Functions — Debugging

| Function | Description |
|----------|-------------|
| `Print(...)` | Print to screen |
| `PrintToFile(file, ...)` | Append to file |
| `ExitScript(str)` | Exit with error message |

---

## 7. LEH Data Reader

### Running

```bash
leh datafile -db mydb -fmt format.fmt -db_accept
```

### Key Options

| Option | Description |
|--------|-------------|
| `-db database` | Target database |
| `-fmt formatfile` | Format file |
| `-db_accept` | Auto-set accept_data |
| `-class n` | Set program class |
| `-nores` | Disable RES table loading |
| `-lowercase` / `-uppercase` | Force lot/wafer case |

### Unique Features

- **DbNoRes**: Disables creating/loading RES tables (prog_type = 40). Helps with performance when loading LEH data where LOG tables provide the same data.
- **DbIndexes**: Declares standard LEH indexes (Lot, Wafer, TP_vers, Die_X, Die_Y)
- Program names ending in `_sum` for summaries

### Raw Data Table Structure

| Column | Type | Description |
|--------|------|-------------|
| lot | Index | Lot identifier |
| wafer | Index | Wafer identifier |
| row_type | Index | Row type |
| Results... | Result | Test parameters |

---

## 8. WEH Data Reader

### Running

```bash
weh datafile -db mydb -fmt format.fmt -db_accept
```

### Key Options

Same as LEH reader. Additionally:

| Option | Description |
|--------|-------------|
| `-skip_invalid_records` | Handle invalid/duplicate FAR records |

### Raw Data Table Structure

| Column | Type | Description |
|--------|------|-------------|
| lot | Index | Lot identifier |
| wafer | Index | Wafer identifier |
| row_type | Index | Row type |
| Results... | Result | Test parameters |

---

## 9. FAB Data Reader

### Running

```bash
fab datafile -db mydb -fmt format.fmt -db_accept
```

### Key Options

| Option | Description |
|--------|-------------|
| `-db database` | Target database |
| `-fmt formatfile` | Format file |
| `-db_accept` | Auto-set accept_data |
| `-class n` | Set program class |
| `-limitsonly` | Update limits only |
| `-outliers [n]` | Filter outliers |
| `-dboutliers` | Filter outliers (database limits) |
| `-arg string` | Pass string to format file |

### Key Built-in Functions (Fab-specific)

| Function | Description |
|----------|-------------|
| `DbFabSite` | Set fab site |
| `DbFabWaf` | Set fab wafer |
| `DbFabLot` | Set fab lot |
| `DbEquipNum(n)` | Set equipment number |
| `DbRecipe` / `vDbRecipe(str)` | Set recipe name |
| `DbProcStep` / `vDbProcStep(str)` | Set process step |
| `DbCustName` / `vDbCustName(str)` | Set customer name |

---

## 10. STDF3 Data Reader

### Worksheet Reader Interface

Accessed via Tools > Import > STDF3.

The STDF3 format file specifies:
- Conditions (C), Key Conditions (KC), Indexes (K), Key Indexes (KI)
- Device number, Bin number, Lot, Wafer, die_X, die_Y

### Format File Example

```
Cond C
DevNum KI
Lot KI
BinNum I
```

### Database Impact

- HIST_BIN/BIN_LOG: Updated if bin index exists
- LOT/WAFER: Updated if lot/wafer IDs exist
- All files must have a test program belonging to a program class (`-class` option)

---

## 11. STDF4 Worksheet Reader

### Worksheet Reader Interface

Accessed via Tools > Import > STDF4.

### Key Built-in Functions (STDF4-specific)

| Function | Description |
|----------|-------------|
| `DbBinSum` | Indicate bin summary condition and tests |
| `DbBinIndex` | Identify bin index |
| `DbDieXYIndexes` | Identify die X/Y indexes |

### stdf4ascii Utility

Imports STDF4 data from a database:

```bash
stdf4ascii -db database [options]
```

---

## 12. Defect Reader

### Running

```bash
# Basic run
defect datafile.kla -db mydb -fmt klarf.fmt -db_accept

# With options
defect datafile.kla -db mydb -fmt klarf.fmt -db_accept -class 14 -upstat
```

### Key Command-Line Options

| Option | Description |
|--------|-------------|
| `-db database` | Target database |
| `-fmt formatfile` | Format file |
| `-db_accept` | Auto-set accept_data |
| `-class n` | Set program class |
| `-v` | Print version |
| `-defectsize` | Pixel size for gallery (small/medium/large) |
| `-wafbackground [0-255]` | Wafer shade in gallery (default 240) |
| `-upstat` | Run UpStat with -defectonly after loading |
| `-wafext [Kbytes]` | WAF table extent (default 256KB) |

### KLARF File Structure

```
FileVersion 1 1;
FileTimestamp ...;
InspectionStationID "" "TTYPE" "TSTR";
SampleType WAFER;
ResultTimestamp ...;
LotID "LOT1";
SampleSize 1 200;                    ← Wafer diameter (mm)
SetupID "PROG1LYR1" ...;
StepID "LYR1";
SampleOrientationMarkType NOTCH;     ← Flat type
OrientationMarkLocation DOWN;        ← Flat orientation
DiePitch 11410.1220 11409.7725;      ← Die width × height (microns)
DieOrigin 0 0;
WaferID "21";
Slot 11;
SampleCenterLocation 11453.3863 8608.0686;  ← Center location
ClassLookup 0;                       ← Class definitions
DefectClusterSpec ...;
DefectClusterSetup ...;
RemovedDieList ...;                  ← Removed (not inspected) die
SampleTestPlan ...;                  ← Inspected die list
AreaPerTest 2.06294e+010;            ← Test area
DefectRecordSpec 17 DEFECTID XREL YREL XINDEX YINDEX XSIZE YSIZE
    DEFECTAREA DSIZE CLASSNUMBER TEST CLUSTERNUMBER ROUGHBINNUMBER
    FINEBINNUMBER REVIEWSAMPLE IMAGECOUNT IMAGELIST;
DefectList
    1 5425.4215 4277.5917 1 8 0.6242 0.6242 0.9204 0.6242 0 1 0 0 333 1 0 0
    ...;
SummarySpec 5 TESTNO NDEFECT DEFDENSITY NDIE NDEFDIE;
SummaryList
    1 10 0.048474 198 4;
EndOfFile;
```

### KLARF DefectRecordSpec Fields

| Field | Type | Description |
|-------|------|-------------|
| DEFECTID | Integer | Unique defect index |
| XREL | Real | X position (microns) |
| YREL | Real | Y position (microns) |
| XINDEX | Integer | Die X index |
| YINDEX | Integer | Die Y index |
| XSIZE | Real | Defect X size |
| YSIZE | Real | Defect Y size |
| DEFECTAREA | Real | Defect area |
| DSIZE | Real | Defect size indicator |
| CLASSNUMBER | Integer | Manual classification |
| TEST | Integer | Test number |
| CLUSTERNUMBER | Integer | Cluster ID |
| ROUGHBINNUMBER | Integer | Rough bin |
| FINEBINNUMBER | Integer | Fine bin |
| REVIEWSAMPLE | Integer | Review sample flag |
| IMAGECOUNT | Integer | Number of images |
| IMAGELIST | Integer | Image list |

### Defect Reader Built-in Functions

| Function | Description |
|----------|-------------|
| `vDbDefect(idx, xInd, yInd, xPos, yPos, xSize, ySize, area, size, cluster, intensity, testNum)` | Store defect record |
| `vDbAddDefectClass(idx, classNum, classType)` | Add defect classification (1=manual, 2=rough, 3=fine) |
| `vDbSetCriticalDfClass(classNum, classType)` | Set critical defect class |
| `vDbDefectSummary(testNum, defCnt, area, density, inspDie, defDie)` | Store defect summary |
| `dBDumpLayer()` | Dump layer info to database |
| `DbUpdateDefect` | Enable defect updates |
| `DbSampleType` | Read sample type (WAFER) |
| `vDbSlotNum(int)` | Set slot number |
| `vDbToleranceDimensions(major, minor, orient)` | Set tolerance ellipse |
| `DbDfTagAction(type, ddThreshold, dcThreshold)` | Set defect tag action |
| `vAddToClassLUT(num, name)` | Add to class lookup table |
| `vAddToLUT(num, type)` | Add to lookup table |
| `IsProgramNew` | Returns TRUE if program doesn't exist yet |

### Tencor Binary Conversion

```bash
# Convert Tencor SFS-7x00 binary to ASCII
tencor2ascii /path/to/data tff
# Creates ASCII files with .ten extension
# Moves binary files to BinaryFiles subdirectory
```

### size_limits.txt

Used by UpStat for size bin summaries:

```
SizeType 2
SizeUnit "micron"
LowerSizeBin0 0.0
UpperSizeBin0 0.01
LowerSizeBin1 0.01
UpperSizeBin1 0.1
...
```

---

## 13. LTX77 Data Reader

### Running

```bash
# Worksheet reader
Tools > Import > LTX77

# Database reader
ltx77 datafile -db mydb -fmt format.fmt -db_accept -class n
```

### Format File Structure

```
Cond C          // Condition
DevNum KI       // Key Index
Lot KI          // Key Index
BinNum I        // Index
FileName KI     // Key Index (data file name)
die_X KI        // Key Index
die_Y KI        // Key Index
```

### Available Conditions/Index Keywords

`DevNum`, `BinNum`, `Cond`, `FileName`, `TesterNum`, `HeadNum`, `Lot`, `Wafer`, `die_X`, `die_Y`

---

## 14. BitMap Reader

### Running

```bash
# Compile format file only
bitmap -fmt formatfile

# Run with database
bitmap datafile.bit -db mydb -fmt format.fmt -db_accept -class n
```

### Key Command-Line Options

| Option | Description |
|--------|-------------|
| `-db database` | Target database |
| `-fmt formatfile` | Format file |
| `-db_accept` | Auto-set accept_data |
| `-class n` | Set program class |
| `-resext [Kb]` | MEM_RES table extent (default 128MB) |
| `-wafext [Kb]` | MEM_WAF table extent (default 16MB) |
| `-lotext [Kb]` | MEM_LOT table extent (default 1MB) |
| `-b2dext [Kb]` | MEM_B2D/MEM_P2D table extent |
| `-indexes [dbspace]` | Index dbspace |
| `-img` | Generate thumbnail images |
| `-addpatts` | Add existing pattern to pattern set |
| `-arg string` | Pass string to format file |
| `-maxtime [seconds]` | Max runtime |
| `-multimemconfig` | Allow multiple memory configs per run |

### BitMap Data File Format

```
<BOH>
PRODUCT:ASIC_1
TECHNOLOGY:DEEP_SUBMICRON
LOTID:l2101
WAFERID:l2101_07
WAFERNUM:07
<EOH>

<BWC>                              ← Wafer Map Configuration
WAFER_ORIENTATION:B
WAFER_DIAMETER:200000
FLAT_TYPE:Notch
DIE_WIDTH:7079.951171875
DIE_HEIGHT:12899.6015625
CENTER_DIEX:18
CENTER_DIEY:11
RETICLE_ROW:3
RETICLE_COL:2
UNIT:micron
<EWC>

<BMC>                              ← Memory Cell Macro
MEMORYNAME:DP_a
MEMORYTYPE:Dual_Port
MEMORYROWS:256
MEMORYCOLUMNS:512
BUS_WIDTH:8
BLOCK_COUNT:8
...
<ECB>...</ECB>                     ← Block definitions
<EMC>

<BOM>                              ← Memory Instance
MEMORYNUM:0
INSTANCENAME:DP_a_1
ROW_ORIENTATION:D
COL_ORIENTATION:R
X1:4046  Y1:4054  X2:5122  Y2:4594
<EOB>...</EOB>                     ← Block defs for instance
<EOM>

<BOT>                              ← Test Results
TESTNUM:0
TESTNAME:Chkbrd_Vmin
DIEX:5
DIEY:10
INSTANCENAME:DP_a_1
<EOT>

<BFL>                              ← Fail Pattern List
ROW:Row1:0:5:1:5:63:60            ← Row pattern
COL:Col1:0:0:446:255:446:222      ← Column pattern
BOX:Array:0:11:0:22:13:101        ← Box pattern
SPOT:SB:0:44:20:44:20:1           ← Spot (single bit)
OTHER:Missed:0:24:0:44:13:20      ← Unclassified bits
CROSS:Cross1:0:0:0:0:0:-1         ← Composite: Cross
  ROW:Row1:0:3:1:3:63:60
  COL:Col1:0:0:55:255:55:222
ENDC
FOG:FogSB:0:0:0:0:0:3             ← Composite: Fog
  SPOT:SB:0:22:20:22:20:1
  SPOT:SB:0:28:20:28:20:1
ENDC
MUROW:MultiRow1:0:7:1:13:63:180   ← Multi-row
MUCOL:MultiCol1:0:0:11:13:22:663  ← Multi-column
<EFL>
```

### Fail Pattern Types

| Type | Description | Format |
|------|-------------|--------|
| `ROW` | Row pattern | `ROW:name:inv:minRow:minCol:maxRow:maxCol:failCnt` |
| `COL` | Column pattern | `COL:name:inv:minRow:minCol:maxRow:maxCol:failCnt` |
| `BOX` | Box pattern | `BOX:name:inv:minRow:minCol:maxRow:maxCol:failCnt` |
| `SPOT` | Single bit | `SPOT:name:inv:row:col:row:col:failCnt` |
| `OTHER` | Unclassified | `OTHER:name:inv:minRow:minCol:maxRow:maxCol:failCnt` |
| `CROSS` | Composite | `CROSS:name:inv:0:0:0:0:failCnt` + components + `ENDC` |
| `FOG` | Composite | `FOG:name:inv:minRow:minCol:maxRow:maxCol:failCnt` + components + `ENDC` |
| `MUROW` | Multi-row | `MUROW:name:inv:0:0:0:0:failCnt` + component rows + `ENDC` |
| `MUCOL` | Multi-column | `MUCOL:name:inv:0:0:0:0:failCnt` + component cols + `ENDC` |

### BitMap-specific Built-in Functions

| Function | Description |
|----------|-------------|
| `vDBAddMemCellClass(...)` | Create memory cell macro description |
| `vDbMemCellBlockConfig(...)` | Define block within memory cell |
| `vDbMemConfig(...)` | Create memory instance description |
| `vDbMemBlockConfig(...)` | Define block within memory instance |
| `vdbPatternSet(setName, cellName, isDefault)` | Specify pattern set |
| `vdbSetDefaultTest(instanceName)` | Set default test for instance |
| `vDbDieMemory(dieX, dieY, devNum, memName, memNum)` | Create MEM_LOG entry |
| `vDBLogRow(...)` | Log row fail pattern |
| `vDBLogColumn(...)` | Log column fail pattern |
| `vDBLogBox(...)` | Log box fail pattern |
| `vDBLogSpot(...)` | Log spot fail pattern |
| `vDBLogCustom(...)` | Log custom pattern |
| `vDBLogMiss(...)` | Log unclassified (OTHER) pattern |
| `vDBLogCross(...)` | Log cross composite pattern |
| `vDBLogFog(...)` | Log fog composite pattern |
| `vDBLogMultiRow(...)` | Log multi-row pattern |
| `vDBLogMultiColumn(...)` | Log multi-column pattern |
| `vDBEndComposite` | End composite pattern components |
| `DbCreateDefaultZones()` | Create default zones (RW, CL, CR, RD, QD, SP, Z9) |
| `DbCircularZones(type, n)` | Create circular zones (area/distance) |
| `DbRadiusZones(n)` | Create radial zones |

### Raw Bitmap File Info (in `<BOT>` section)

```
RAW_FILE_NAME:"file.wsf":"":"":""
RAW_FILE_PATH:"path/":"sub/":"/dir/":""
ROOT_FILE_PATH:"/root/":"path/":"":""
RAW_FILE_SETUP:index:startCol:startRow:"transf":"setupName"
```

---

## 15. Events Reader

### Running

```bash
# Compile format file only
events -fmt formatfile

# Run with database
events datafile -db mydb -fmt format.fmt -db_accept -class n

# With Windows service
install_service.bat    // Install
net start Event_Reader // Start
net stop Event_Reader  // Stop
uninstall_service.bat  // Uninstall
```

### Key Command-Line Options

| Option | Description |
|--------|-------------|
| `-db database` | Target database |
| `-fmt formatfile` | Format file |
| `-db_accept` | Auto-set accept_data |
| `-class n` | Set program class |
| `-arg string` | Pass string to format file |

### Events-specific Built-in Functions

| Function | Description |
|----------|-------------|
| `DbProgClass(int)` | Set program class |
| `DbLotTag(str, int)` | Tag lot (1=NoAction, 2=Bad, 3=Scrap, 4=Experiment) |
| `DbSrcLotTag(str, int)` | Tag source lot |
| `DbWfNum(str, int)` | Set wafer number |

### EVENTS File Format Specification (Oracle Only)

```
<DATALOSSLOT>
<DATA>
lot_id|src_lot_id|fab_name|equip_name|...
...
</DATA>
</DATALOSSLOT>
```

### Event Reader Windows Service

- **Service Name**: `Event_Reader`
- **Installation Directory**: `D:\EventReader` (or custom)
- **Configuration**: `service.xml`

---

## 16. Built-in Function Quick Reference

### File Navigation (All Readers)

| Function | Returns | Description |
|----------|---------|-------------|
| `Goto(str)` | — | Search forward for word |
| `GoBackTo(str)` | — | Search backward for word |
| `GotoEOF` | — | Move to end of file |
| `GotoBOF` | — | Move to beginning of file |
| `SkipLines(n)` | — | Skip N lines (+/- direction) |
| `SkipWords(n)` | — | Skip N words (+/- direction) |
| `SkipChars(n)` | — | Skip N characters (+/- direction) |
| `NotEndOfFile` | Boolean | False at EOF |
| `ErrorCode` | Integer | 0=success, 1=failure |
| `GetLineLen` | Integer | Chars from pointer to EOL |

### Data Retrieval (All Readers)

| Function | Returns | Description |
|----------|---------|-------------|
| `GetInt` | Integer | Current word as integer |
| `GetReal` | Real | Current word as real |
| `GetWord` | String | Current word |
| `GetPrevWord` | String | Previous word |
| `GetPrevInt` | Integer | Previous word as integer |
| `GetPrevReal` | Real | Previous word as real |
| `GetLine` | String | To end of line |
| `GetQuotedWord(char)` | String | Quoted string |
| `GetChar` | Char | First char of current word |
| `GetChars(n)` | String | N characters |
| `GetLeftChars(n)` | String | First N chars of word |
| `GetRightChars(n)` | String | Last N chars of word |
| `GetMidChars(start, n)` | String | Middle N chars |
| `GetWordAfter(char)` | String | After character |
| `GetWordBefore(char)` | String | Before character |
| `ExtractString` | String | Word minus leading numbers |

### Database (All Readers)

| Function | Description |
|----------|-------------|
| `DbProgram` / `vDbProgram(str)` | Set program name |
| `DbFab` / `vDbFab(str)` | Set fab |
| `DbTechnology` / `vDbTechnology(str)` | Set technology |
| `DbProcess` / `vDbProcess(str)` | Set process |
| `DbProduct` / `vDbProduct(str)` | Set product |
| `DbLot` / `vDbLot(str)` | Set lot ID |
| `DbSrcLot` / `vDbSrcLot(str)` | Set source lot |
| `DbStage` / `vDbStage(str)` | Set stage |
| `DbStep` / `vDbStep(str)` | Set step |
| `DbEquip` / `vDbEquip(str, n)` | Set equipment |
| `DbTester` / `vDbTester(str)` | Set tester name |
| `DbTesterType` / `vDbTesterType(str)` | Set tester type |
| `DbOperator` / `vDbOperator(str)` | Set operator |
| `DbCustomer` / `vDbCustomer(str)` | Set customer |
| `DbResultTime` / `vDbResultTime(str, fmt)` | Set result timestamp |
| `DbSetupTime` / `vDbSetupTime(str, fmt)` | Set setup timestamp |
| `DbProgRel` / `vDbProgRel(str, fmt)` | Set program release date |
| `DbProgRev` / `vDbProgRev(str)` | Set program revision |
| `vDbProgGroup(str)` | Set program group |
| `vDbProgProcess(str)` | Associate program with process |
| `DbWaferIndex(str)` | Declare wafer index |
| `DbDieXYIndexes(str, str)` | Declare die X/Y indexes |
| `DbBinIndex(str)` | Declare bin index |
| `DbBinTest(str)` | Declare bin test |
| `DbBinSum` | Declare bin summary |
| `DbWfNum(str, int)` | Set wafer ID and number |

### Mathematical (All Readers)

| Function | Returns | Description |
|----------|---------|-------------|
| `Abs(real)` | Real | Absolute value |
| `Sqr(real)` | Real | Square root |
| `POW(real, int)` | Real | Power |
| `RealToInt(real)` | Integer | Real to integer |
| `IntToReal(int)` | Real | Integer to real |
| `ScaleFactor(int)` | — | Set scaling factor |

### Debugging (All Readers)

| Function | Description |
|----------|-------------|
| `Print(...)` | Print to screen |
| `PrintToFile(file, ...)` | Append to file |
| `ExitScript(str)` | Exit with error (empty string = no error file) |
| `System(str)` | Execute UNIX command |

---

## 17. Command-Line Options Quick Reference

### Common Options (Most Readers)

| Option | Description |
|--------|-------------|
| `-v` | Print version |
| `-u` | Print usage |
| `-db database` | Target database |
| `-fmt formatfile` | Format file |
| `-db_accept` | Auto-set accept_data |
| `-class n` | Set program class |
| `-file_path path` | Base path for OpenFile() |
| `-arg string` | Pass arbitrary string to format file |
| `-lowercase` | Force lot/wafer to lowercase |
| `-uppercase` | Force lot/wafer to uppercase |
| `-outliers [n]` | Filter outliers (box-plot) |
| `-dboutliers` | Filter outliers (database limits) |
| `-rework_action n` | Set rework action (1-4) |
| `-start-time` | Include start-time in rework detection |
| `-end-time` | Include end-time in rework detection |
| `-srclot` | Get source lot from filename |
| `-wfnum` | Get wafer number from filename |
| `-res_aging [days]` | Results aging |
| `-stats_aging [days]` | Statistics aging |
| `-maxtime [seconds]` | Max runtime |

### Table Extent Options

| Option | Default | Range |
|--------|---------|-------|
| `-resext [Kb]` | 5012 Kb | 64–10024 Kb |
| `-lotext [Kb]` | 64 Kb | 16–1024 Kb |
| `-defext [Kb]` | 256 Kb | 32–5012 Kb |
| `-wafext [Kb]` | 256 Kb | 32–5012 Kb |

### ASCII-specific Options

| Option | Description |
|--------|-------------|
| `-normalize` | Normalize XY coordinates |
| `-dfalign` | Align binmap to defect program |
| `-limitsonly` | Update limits only |
| `-nores` | Disable RES table loading |
| `-validate_wmcfg` | Validate wafer map config from file vs. DB |

### BitMap-specific Options

| Option | Description |
|--------|-------------|
| `-resext [Kb]` | MEM_RES extent (default 128MB) |
| `-wafext [Kb]` | MEM_WAF extent (default 16MB) |
| `-lotext [Kb]` | MEM_LOT extent (default 1MB) |
| `-b2dext [Kb]` | MEM_B2D/MEM_P2D extent |
| `-img` | Generate thumbnail images |
| `-addpatts` | Add pattern to pattern set |
| `-multimemconfig` | Multiple memory configs per run |

---

## 18. Common Errors & Troubleshooting

### "ExecSkipWords: Trying to read beyond valid file length"

**Cause**: The format file tried to read past the end of the data file. This happens when:
- The format file expects more fields per line than the data file provides
- Missing optional fields (e.g., cluster, roughbin, finebin in KLARF)
- Mismatch between `DefectRecordSpec` field count and actual data

**Fix**: Check that the `DefectRecordSpec` in the KLARF file matches what the format file reads. Use `ErrorCode` checks and `NotEndOfFile` guards.

### "Found 2 unique substrate sizes for a single defect program"

**Cause**: `GetSubstrateSize` found different die grid dimensions (`fld_rows` × `fld_cols`) within a single defect program's wafer records.

**Fix**: Ensure all wafers in a defect program have the same `DiePitch` (die dimensions). A defect program has a 1:1 relationship with its wafer configuration.

### "Exit called from file ora/dbdefect.pc at line NNN"

**Cause**: A database-level assertion failed in the defect reader's Oracle stored procedure. Usually preceded by a specific error message.

**Fix**: Address the preceding error message first.

### "No die dimensions provided"

**Cause**: The format file didn't find `DiePitch` in the KLARF file.

**Fix**: Ensure the KLARF file contains a valid `DiePitch` line with width and height values.

### "Could not find defectlist"

**Cause**: The KLARF file doesn't contain a `DefectList` section.

**Fix**: Verify the KLARF file has a `DefectList` keyword before the defect records.

### "Could not find summaries in KLARF file"

**Warning**: The KLARF file doesn't have a `SummaryList`. This is a warning, not a fatal error. Defect summaries (dd, def_cnt, insp_die, def_die) won't be loaded.

### Multiple BinMap Programs Aligned to One Defect Program

**Cause**: UpStat found more than one binmap program aligned to a single defect program.

**Fix**: Only one binmap program should be aligned to a defect program (via `-dfalign`). Other binmap programs should use `DbNormalizeMap` instead of `DbAlignDefect`.

### Oracle Rollback Recovery

If a reader exits unexpectedly (lost connection, Ctrl+C), the rollback statements are stored in `DP_ROLLBACK_STMTS_...` table. Recovery is automatic on next run.

### Invalid Data Handling

Data points between -1e-38 and 1e-38 are treated as tester error codes and loaded as NULL.

### File Tracking Status Codes

| Code | Meaning |
|------|---------|
| -1 | Loading in progress |
| 0 | Success |
| 90 | Not processed |
| 4 | Rework |
| 9 | Rejected |
| 10 | Not accepted |
| 80 | Loaded with warnings |
| 91 | Invalid error file format |

---

## Quick Reference Card

### Date Format Tokens

| Token | Description |
|-------|-------------|
| `dd` | Day (2-digit) |
| `ddd` | Day of week (3-letter, not for Oracle) |
| `mm` | Month (2-digit) |
| `mmm` | Month (3-letter) |
| `yy` | Year (2-digit, 2000s) |
| `yyyy` | Year (4-digit) |

### Flat/Notch Location Mapping

| KLARF Value | DB Value |
|-------------|----------|
| DOWN | B (Bottom) |
| UP | T (Top) |
| LEFT | L |
| RIGHT | R |

| Flat Type | DB Value |
|-----------|----------|
| NOTCH | N |
| FLAT | F |

### Wafer Orientation / Position

| Value | Meaning |
|-------|---------|
| R | Right |
| L | Left |
| T | Top |
| U | Up |
| B | Bottom |
| D | Down |

### Defect Class Types

| Type | Description |
|------|-------------|
| 1 | Manual classification |
| 2 | Rough bin |
| 3 | Fine bin / critical |

### Lot Tag Values

| Value | Meaning |
|-------|---------|
| 1 | No Action |
| 2 | Bad |
| 3 | Scrap |
| 4 | Experiment |

---

*End of Reference Guide*

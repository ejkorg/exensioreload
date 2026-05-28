# Exensio Data Readers — Simplified Summary

Purpose
- Readers import raw datalog files into Exensio (database or worksheet) and normalize/format for analysis.

Reader types
- Database readers: load formatted data into Informix/Oracle/Cassandra (RES..., WAF..., LOT..., LIM..., DEF...).
- Worksheet readers: import directly into the Exensio–Yield session.

Key tools
- DpLoad.pl: automated loader. Usage: `DpLoad.pl configFile` with options like `-once`, `-run`, `-notify`, `-log`, `-log_db`, `-parallel`, `-freeze`, `-files`, `-sleep`, `-cleanup`.
- DpLoadMgr.pl: manages multiple DpLoad.pl instances (start/stop/status/kill).
- UpStat: computes lot-level stats and bin-model parameters (m, d, y values).

DpLoad configuration (essentials)
- Config lines: `SearchDir:FullPathOfReader:Extension:ReaderOptions:`
- Special extension `NA` for non-file tools.
- DpLoad creates staging subdirs: `Wd`, `Processed`, `NotProcessed`, `UnUsedFiles`, `ReworkFiles`, `Warnings`.
- Parallelization: add `-parallel N` and optional `-freeze S` to reader options.
- File tracking: `-log_db dbname` stores loader events in DP_LOG.

Error handling and exit codes
- Readers produce `err.jnk` with standardized format (first line `errors\twarnings`).
- Special reader exit codes (used by DpLoad):
  - `0` success, `4` rework (move to ReworkFiles), `9` unused (move to UnUsedFiles), `10` keep file (not accepted), `11` Informix deadlock handler, `12` non-reader tool no-op.

Format files (overview)
- A format file (.fmt) guides a reader how to parse an ASCII datalog.
- Blocks: `SCRIPT`, `CONST`, `VAR` (variables, conditions, indexes, results), `BEGIN` ... `END` (main processing).
- Variable types: `integer`, `real`, `char`, `string`, `boolean`; arrays supported (1D, 2D).
- Conditions vs Indexes:
  - Conditions: identify parameter columns (e.g., TestName, Unit, TestNum).
  - Indexes: identify rows (e.g., Program, Lot, Wafer, X/Y for maps).
- Results: declare parameters to log (types include int, short, real, char, fixed char[n], string).

Limits and scaling
- Limits types: `LSL`, `HSL`, `LPL`, `HPL`, `LOL`, `HOL`, `LWL`, `HWL`, `TGT`, plus `FailBin`.
- `LogLimits()` writes limits to LIM...; `-limitsonly` updates limits without loading data.
- `ScaleFactor()` sets parameter scaling; legal values: -15, -12, -9, -6, -3, -2, 0, 2, 3, 6, 9, 12, 15.

ASCII reader essentials
- Key built-in functions: file navigation (`Goto`, `GotoEOF`), token retrieval (`GetWord`, `GetInt`, `GetReal`), string helpers (`ToLower`, `ToUpper`, `StrTrim`), logging (`LogResult`, `vLogResult`), DB helpers (`DbProgram`, `DbLot`, `DbWaferIndex`, `DbDieXYIndexes`, `DbFab`, `DbProduct`, `DbProcess`, etc.).
- Use `FileName` to extract values from the data file name.
- Separators: default newline/tab/CR; can add up to 5 extra single-char separators via `AddSep()`.

Normalization, alignment and binmaps
- Normalization: center die = (0,0), orientation fixed; optional for binmaps (`-normalize` or `DbNormalize`).
- Defect alignment (`-dfalign` or `DbDefect`) aligns binmaps to defect data to enable kill-ratio stats.
- Binmap storage: X/Y become key conditions (columns), wafer ID becomes row key; sampling may apply to large wafers (Informix/Oracle thresholds).

Rework and consolidation
- Rework actions (`rework_action`) control append/overwrite/no-load behavior; readers can detect rework by existing Lot/Wafer entries.
- Wafer/OP_LOG flags: `rework_flag` increments per rework.
- Wafer consolidation and equipment metadata can be controlled via `DbWaferAppend`, `vDbConsEquipment`, and related built-ins.

Errors & troubleshooting (quick)
- If no `err.jnk` created: DpLoad treats as success for exit 0, else moves file to NotProcessed and creates a diagnostic error.
- Use `-v` on readers to show version string required by `-log_db` logging.

Quick references
- Typical DpLoad command:
  `DpLoad.pl cfgfile.cfg -log -files 100 -log_db tracking_db`
- Re-run a file after format changes: load once to update program schema, then reload the file (DpLoad handles this when Accept_Data not set).

Further reading
- See the full original document for detailed built-in function signatures, complete DpLoad options, and per-reader chapters (ASCII, STDF3/4, Defect, Bitmap, Events, Fab, LTX77).

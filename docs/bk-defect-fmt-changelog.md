# bk-defect-fmt.fmt — Fix Log

## Jun-01-2026: KLARF 1.8 format file bug fixes and cleanup

Six bugs fixed in `docs/bk-defect-fmt.fmt` (script `klarf_18`). One additional fix was applied in a prior session (IMAGEINFO/POLARITY parsing). All changes are documented below.

---

### 1. IMAGEINFO/POLARITY parsing — runtime crash (`ExecSkipWords`)

**Severity**: Critical — caused `Run Time Error: ExecSkipWords: Trying to read beyond valid file length`

**Root cause**: The `ImageList IMAGEINFO` column in KLARF 1.8 contains multi-token data:

```
Images 2 { "file.jpg" "JPG" 1 "106", "file2.jpg" "JPG" 1 "106" }
```

The format file split parsing across two handlers incorrectly:

- The `IMAGEINFO` handler only read the `"Images"` keyword and set `do_imagelist = TRUE`, but did **not** consume the count or image entries.
- The `POLARITY` handler then picked up the leftover IMAGEINFO data: `img_cnt = GetInt` read `"2"` (the IMAGEINFO count, not the actual POLARITY value), then entered an image loop that consumed **5 tokens per image instead of 4** (`SkipWords(2)` instead of `SkipWords(1)`).
- This caused the parser to read past the end of the file, particularly on the last defect record.

**Fix**: Moved image list consumption into the `IMAGEINFO` handler so it fully reads the `ImageList` block (count + all image entries with correct `SkipWords(1)` per image). The `POLARITY` handler now reads its actual integer value into a temp variable `num`, and only sets `img_cnt`/`img_list` when `do_imagelist` is FALSE.

---

### 2. `Item[30]` array overflow

**Severity**: High — array bounds violation on KLARF files with >30 columns after YSIZE

**Root cause**: The `Item[]` array was declared as `Item[30]` but KLARF 1.8 files can have 46 columns, leaving 39 items after YSIZE. Per the Exensio format language, arrays auto-reallocate to double size on overflow, but this is unreliable and wasteful.

**Fix**: Increased to `Item[100]`.

---

### 3. TestSummaryList variable types — data truncation

**Severity**: Medium — fractional values in haze and test area fields silently truncated to integers

**Root cause**: The KLARF 1.8 `TestSummaryList` defines these columns as `float`:

- `HAZEREGION`, `HAZEAVERAGE`, `HAZESTDDEV`, `HAZEMEDIAN`, `HAZEPEAK`, `AREAPERTEST`

But the format file declared the corresponding variables (`haze_reg`, `haze_avg`, `haze_std`, `haze_med`, `haze_peak`, `areapertest`) as `integer` and read them with `GetInt`. Any fractional values were silently truncated.

**Fix**: Changed declarations from `integer` to `real` and `GetInt` to `GetReal`.

---

### 4. `NotEndOfFile` check order in summary loop

**Severity**: Medium — potential crash reading past end of file during summary parsing

**Root cause**: The summary loop condition was:

```
While (IsNumber(GetWord) AND NotEndOfFile)
```

`IsNumber(GetWord)` was evaluated **before** `NotEndOfFile`. If the parser was at EOF, `GetWord` would attempt to read past the valid file boundary before the EOF guard could prevent it.

**Fix**: Swapped the evaluation order:

```
While (NotEndOfFile AND IsNumber(GetWord))
```

---

### 5. Missing `NotEndOfFile` guard in defect loop

**Severity**: Medium — potential crash if defect data ends unexpectedly

**Root cause**: The defect record loop had no EOF protection:

```
While (IsNumber(str)) OR (str = "TiffFileName")
```

If the file ended mid-record (corrupted/truncated KLARF), the parser would attempt reads past EOF.

**Fix**: Added EOF guard:

```
While ((IsNumber(str)) OR (str = "TiffFileName")) AND NotEndOfFile
```

---

### 6. Dead duplicate `IMAGEINFO` handler

**Severity**: Low — unreachable dead code (no runtime impact, but misleading)

**Root cause**: A second `Else If Item[j] = "IMAGEINFO"` block existed after the `CN_CONFIDENCE` handler. It was unreachable because the first `IMAGEINFO` handler always matched first. Likely a copy-paste remnant.

**Fix**: Removed the dead block.

---

### 7. Redundant `GoToBOF` + `GoTo("WaferRecord")` before `DefectList` search

**Severity**: Low — unnecessary file re-scanning; incorrect behavior for multi-wafer KLARF files

**Root cause**: Before searching for `DefectList`, the code did:

```
GoToBOF
GoTo("WaferRecord")
GoTo("DefectList")
```

This reset the cursor to the beginning of the file and re-found the **first** `WaferRecord` on every iteration of the record loop. For single-wafer files this was wasteful but harmless; for multi-wafer files it would re-parse the same record.

Since the cursor is already positioned within the correct `WaferRecord` (after reading `AreaPerTest`, `SampleCenterLocation`, `DieOrigin`), a forward-only `GoTo("DefectList")` is sufficient.

**Fix**: Removed the `GoToBOF` + `GoTo("WaferRecord")` lines, keeping only `GoTo("DefectList")`.

---

### Files modified

| File | Changes |
|------|---------|
| `docs/bk-defect-fmt.fmt` | All 7 fixes applied |

### Testing notes

- Verified against `docs/bk-defect-sample.klarf` (59 defects, 46 columns, ImageList data)
- Defects 13 and 33 use `IMAGEINFO = "N"` (no images) — confirmed these paths are unaffected
- All remaining defects use `IMAGEINFO = "Images 2 { ... }"` — confirmed image list parsing consumes exactly the right number of tokens

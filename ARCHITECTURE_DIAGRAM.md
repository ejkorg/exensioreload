# Wafer-Level Preflight Check - Architecture Diagrams

## System Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                        UI Layer (Frontend)                     │
│  - Lot Verification Dialog                                    │
│  - Display wafers with schema info                           │
│  - CSV export with wafer data                                │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         │ HTTP POST /verify-lots
                         │
┌────────────────────────▼─────────────────────────────────────┐
│                  API Layer (Controller)                       │
│  SenderController.verifyLots()                              │
│  - Receives LotVerificationRequest                          │
│  - Orchestrates discovery + parallel check                  │
│  - Returns LotVerificationResponse                          │
└────────────────────────┬─────────────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
    ┌─────────┐  ┌──────────────┐  ┌──────────────────┐
    │Detect   │  │Discovery     │  │Parallel Schema   │
    │Wafer    │  │Service       │  │Check Service     │
    │Level    │  │              │  │                  │
    │Class    │  │Query local   │  │Thread A: PROD    │
    └────┬────┘  │database for  │  │Thread B: SANDBOX │
         │       │wafers        │  └────────┬─────────┘
         │       └──────┬───────┘           │
         │              │                   │
         └──────────────┼───────────────────┘
                        │
                        ▼
        ┌──────────────────────────────┐
        │ Response Processing          │
        │ - Aggregate wafers by lot    │
        │ - Remove duplicates          │
        │ - Transform to DTO           │
        └──────────────┬───────────────┘
                       │
                       ▼
        ┌──────────────────────────────┐
        │ LotVerificationResponse      │
        │ {                            │
        │   lots: {                    │
        │     LOT123: {                │
        │       found: true,           │
        │       schema: "PRODUCTION",  │
        │       wafers: [W01, W02...] │
        │     }                        │
        │   }                          │
        │ }                            │
        └──────────────┬───────────────┘
                       │
                       ▼
        ┌──────────────────────────────┐
        │ Return to UI                 │
        └──────────────────────────────┘
```

---

## Discovery Phase

```
┌─────────────────────────────────────────────────────────┐
│ INPUT: lotIds=[LOT123], pgcKey=1                       │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ WaferDiscoveryService.discoverWafersForLots()          │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────────────┐
    │ SQL Query:                                 │
    │ SELECT DISTINCT UPPER(TRIM(w.wf_id))     │
    │ FROM op_log ol                            │
    │ JOIN lot l ON l.lot_key = ol.lot_key     │
    │ LEFT JOIN wf_log wfl                      │
    │ LEFT JOIN wafer w                         │
    │ WHERE ol.pgc_key = 1                      │
    │   AND l.lot_id IN ('LOT123')              │
    │   AND w.wf_id IS NOT NULL                 │
    └────────────────┬─────────────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────────────┐
    │ Database Results:                          │
    │ LOT123 | W01                               │
    │ LOT123 | W02                               │
    │ LOT123 | W03                               │
    │ LOT123 | W04                               │
    │ LOT123 | W05                               │
    └────────────────┬─────────────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────────────┐
    │ Process Results:                           │
    │ - Extract wafer IDs                        │
    │ - Convert to uppercase                     │
    │ - Remove duplicates                        │
    │ - Sort                                     │
    └────────────────┬─────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ OUTPUT: [W01, W02, W03, W04, W05]                       │
└─────────────────────────────────────────────────────────┘
```

---

## Parallel Check Phase

```
┌──────────────────────────────────────────────────┐
│ INPUT:                                           │
│ lotIds=[LOT123]                                 │
│ waferIds=[W01, W02, W03, W04, W05]             │
└────────────────┬─────────────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────────┐
│ ParallelSchemaCheckService.checkLotsParallel() │
└────────────────┬─────────────────────────────────┘
                 │
    ┌────────────┴────────────┐
    │                         │
    ▼                         ▼
┌──────────────────┐   ┌──────────────────┐
│ Thread A:        │   │ Thread B:        │
│ PRODUCTION       │   │ SANDBOX          │
└────────┬─────────┘   └────────┬─────────┘
         │                      │
         │ CompletableFuture    │
         │ async execution      │
         │                      │
    ┌────▼─────────────────────▼────┐
    │ Create PreCheckRequest         │
    │ (PRODUCTION)      (SANDBOX)    │
    │ Call               Call        │
    │ ExensioPreCheck    ExensioPreCheck
    │ Service.check()    Service.check()
    └────┬─────────────────────┬────┘
         │                     │
         ▼                     ▼
    ┌──────────────┐     ┌──────────────┐
    │ PROD Results │     │ SBX Results  │
    │              │     │              │
    │ Found:       │     │ Found:       │
    │ LOT123       │     │ LOT123       │
    │ W01: PROD    │     │ W01: SANDBOX │
    │ W02: PROD    │     │ W02: SANDBOX │
    │ W03: PROD    │     │ W04: SANDBOX │
    │              │     │ W05: SANDBOX │
    └────┬─────────┘     └────┬─────────┘
         │                    │
         └────────┬───────────┘
                  │
                  ▼ (Consolidate)
    ┌─────────────────────────────┐
    │ Merge Results:              │
    │                             │
    │ PROD has: W01, W02, W03    │
    │ SANDBOX has: W01, W02,     │
    │              W04, W05      │
    │                             │
    │ Merged:                     │
    │ W01 (PROD)                  │
    │ W02 (PROD)                  │
    │ W03 (PROD)                  │
    │ W04 (SANDBOX) - new        │
    │ W05 (SANDBOX) - new        │
    └────────────┬────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────┐
│ OUTPUT: Consolidated ExensioPreCheckResponse│
│ {                                            │
│   lotsFound: [LOT123]                       │
│   lotsNotFound: []                          │
│   rows: [                                   │
│     {LOT123, PROD, W01}                     │
│     {LOT123, PROD, W02}                     │
│     {LOT123, PROD, W03}                     │
│     {LOT123, SANDBOX, W04}                  │
│     {LOT123, SANDBOX, W05}                  │
│   ]                                         │
│ }                                            │
└──────────────────────────────────────────────┘
```

---

## Consolidation Phase

```
┌─────────────────────────────────────────────────┐
│ PROD Response:                SANDBOX Response: │
│ ┌──────────────┐             ┌──────────────┐  │
│ │LOT123 - PROD │             │LOT123 - SBX  │  │
│ │W01: PROD     │             │W01: SANDBOX  │  │
│ │W02: PROD     │             │W02: SANDBOX  │  │
│ │W03: PROD     │             │W04: SANDBOX  │  │
│ │              │             │W05: SANDBOX  │  │
│ └──────┬───────┘             └──────┬───────┘  │
│        │                            │          │
└────────┼────────────────────────────┼──────────┘
         │                            │
         └────────────┬───────────────┘
                      │
                      ▼
         ┌──────────────────────────┐
         │ Consolidation Logic:     │
         │                          │
         │ For each lot:            │
         │ 1. Start with PROD rows  │
         │ 2. Add SANDBOX if unique │
         │ 3. Keep PROD priority    │
         │ 4. Collect all wafers    │
         └────────────┬─────────────┘
                      │
                      ▼
         ┌──────────────────────────────────┐
         │ Final Consolidated Row Set:      │
         │                                  │
         │ LOT123, PROD, W01                │
         │ LOT123, PROD, W02                │
         │ LOT123, PROD, W03                │
         │ LOT123, SANDBOX, W04 (new)      │
         │ LOT123, SANDBOX, W05 (new)      │
         │                                  │
         │ Aggregated Wafers:               │
         │ LOT123: [W01, W02, W03, W04, W05]│
         └────────────┬─────────────────────┘
                      │
                      ▼
         ┌──────────────────────────────┐
         │ Return LotVerificationResult │
         │ {                            │
         │   found: true               │
         │   schema: "PRODUCTION"      │
         │   wafers: [W01-W05]        │
         │ }                            │
         └──────────────────────────────┘
```

---

## Decision Tree

```
                    ┌─ User Input ─┐
                    │ Lot + DataType│
                    └────────┬──────┘
                             │
                    ┌────────▼────────┐
                    │ Get pgcKey from │
                    │ dataType        │
                    └────────┬────────┘
                             │
                    ┌────────▼────────────────┐
                    │ Is wafer-level class?  │
                    │ (1, 4, 5, or 14)       │
                    └────────┬────────────────┘
                    ┌─────NO─┴─YES──┐
                    │               │
        ┌───────────▼────┐    ┌─────▼──────────────┐
        │ Use Standard   │    │ Has wafer filter?  │
        │ Check          │    │ (user provided)    │
        └────────────────┘    └─────┬──────────────┘
                          ┌─────NO──┴──YES──┐
                          │                 │
                    ┌─────▼──────┐  ┌──────▼─────┐
                    │ Discover   │  │ Use        │
                    │ Wafers     │  │ Provided   │
                    └─────┬──────┘  └──────┬─────┘
                          │                │
                    ┌─────▼────────────────▼─────┐
                    │ Execute Parallel Check     │
                    │ (PROD + SANDBOX)           │
                    └─────┬──────────────────────┘
                          │
                    ┌─────▼───────────┐
                    │ Consolidate     │
                    │ Results         │
                    └─────┬───────────┘
                          │
                    ┌─────▼──────────────┐
                    │ Return Response    │
                    │ with Wafers        │
                    └────────────────────┘
```

---

## Class Dependency Diagram

```
┌──────────────────────┐
│ SenderController     │
└──────────┬───────────┘
           │
           │ uses
           │
  ┌────────┴────────────────────┐
  │                             │
  ▼                             ▼
┌─────────────────┐     ┌──────────────────────┐
│ WaferDiscovery  │     │ ParallelSchemaCheck  │
│ Service         │     │ Service              │
└─────────────────┘     └──────────┬───────────┘
  │ queries                       │
  │ Exensio DB                    │ uses
  │                               │
  │                       ┌───────▼────────────┐
  │                       │ ExensioPreCheck    │
  │                       │ Service            │
  │                       │ (existing)         │
  │                       │                    │
  │                       │ queries via HTTP   │
  │                       └───────┬────────────┘
  │                               │
  └───────────────┬───────────────┘
                  │
                  ▼ (uses)
         ┌────────────────────┐
         │ ExensioAuthService │
         │ (existing)         │
         │                    │
         │ manages tokens     │
         └────────┬───────────┘
                  │
                  ▼ (calls)
         ┌────────────────────┐
         │ Exensio HTTP       │
         │ /v1/key/raw-sql    │
         │ endpoint           │
         └────────────────────┘
```

---

## Sequence Diagram - Parallel Check

```
Client          Controller           Discovery        Parallel         PROD         SANDBOX
  │                  │                 │               │                │               │
  │─Verify Lots───→  │                 │               │                │               │
  │                  │                 │               │                │               │
  │                  │─Detect Wafer    │               │                │               │
  │                  │ Level Class─────→               │                │               │
  │                  │                 │               │                │               │
  │                  │─Discover Wafers─────────────────→               │               │
  │                  │                 │               │                │               │
  │                  │←Result: [W01-W05]───────────────┤               │               │
  │                  │                 │               │                │               │
  │                  │─Parallel Check─────────────────→               │               │
  │                  │                 │               │                │               │
  │                  │                 │               │─Check PROD─────→             │
  │                  │                 │               │                │             │
  │                  │                 │               │                │   Check─────→
  │                  │                 │               │                │    SANDBOX   │
  │                  │                 │               │                │             │
  │                  │                 │               │←Result: Found──┤             │
  │                  │                 │               │                │             │
  │                  │                 │               │                │         ←Result: Found
  │                  │                 │               │                │             │
  │                  │←Both Complete────────────────────┤               │               │
  │                  │                 │               │                │               │
  │                  │─Consolidate─────→               │                │               │
  │                  │                 │               │                │               │
  │                  │←Merged Result────┤               │                │               │
  │                  │                 │               │                │               │
  │←Response─────────┤                 │               │                │               │
  │  (with wafers)   │                 │               │                │               │

Legend:
→ = synchronous call
← = return
─ = message
```

---

## Timing Diagram

```
Sequential (Old):
├─────────┬──────────┬──────────┬──────────┬──────────┤
│ Disc    │   PROD   │  SANDBOX │          │          │
│ 50ms    │  200ms   │  180ms   │          │          │
│         │          │          │          │          │
└─────────┴──────────┴──────────┴──────────┴──────────┘
                Total: 430ms

Parallel (New):
├─────────┬──────────────────────────────────────┤
│ Disc    │ PROD (200ms)                         │
│ 50ms    │ SANDBOX (180ms)  [parallel]          │
│         │                                       │
└─────────┴──────────────────────────────────────┘
                Total: 250ms (~42% faster)
```

---

## Error Handling Flow

```
             ┌─ Start Parallel Check ─┐
             │                        │
      ┌──────▼──────┐        ┌────────▼──────┐
      │ PROD Check  │        │ SANDBOX Check │
      └──────┬──────┘        └────────┬──────┘
             │                        │
      ┌──────▼──────┐        ┌────────▼──────┐
      │ Success?   │        │ Success?      │
      └──┬───────┬──┘        └──┬────────┬───┘
     YES │       │ NO       YES │        │ NO
        │       │             │        │
   ┌────▼─┐  ┌──▼───┐     ┌───▼──┐  ┌──▼───┐
   │Use   │  │Log   │     │Use   │  │Log   │
   │PROD  │  │Error │     │SANDBOX│ │Error │
   │Result│  │Try SBX     │Result│  │Return│
   └────┬─┘  │      │     └───┬──┘  │Error │
        │    │      │         │     │      │
        │    └──┬───┘         │     └──┬───┘
        │       │             │        │
        └───┬───┴─────────┬───┴────┬───┘
            │             │        │
        ┌───▼─────────┬───▼──┐  ┌─▼────┐
        │Has PROD?    │Has SBX? │Both   │
        │             │         │Failed?│
        └─┬───────┬───┴─┬──┬────┴──┬────┘
       YES│       │NO  YES NO     YES
          │       │    │   │       │
       ┌──▼┐   ┌──▼──┐│ ┌─▼────┐ ┌▼──────┐
       │Use│   │Use  ││ │Use   │ │Not    │
       │PROD   │Empty││ │SANDBOX│ │Found  │
       │       │List ││ │Result│ │Error  │
       └──────────────┘└────────┘ └───────┘
```

---

## This completes the implementation with full architectural documentation.

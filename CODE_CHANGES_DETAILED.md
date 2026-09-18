# Detailed Code Changes

## Backend Changes

### File: `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/UserAdminController.java`

#### Change 1: Add Security Import

**Location:** Line 17 (after other imports)

```diff
+ import org.springframework.security.access.prepost.PreAuthorize;
```

#### Change 2: Add Class-Level Authorization

**Location:** Line 32 (before class declaration)

```diff
  @RestController
  @RequestMapping("/api/admin/users")
+ @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('SUPER_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
  public class UserAdminController {
```

#### Change 3: Fix Role Format in `/admin/users/roles` Endpoint

**Location:** Line 674 (in `getAvailableRoles()` method)

```diff
  @GetMapping("/roles")
  public ResponseEntity<List<String>> getAvailableRoles() {
-     List<String> roles = List.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN");
+     List<String> roles = List.of("USER", "ADMIN", "SUPER_ADMIN");
      return ResponseEntity.ok(roles);
  }
```

---

## Frontend Changes

### File: `frontend/src/app/admin/user-form-dialog.component.ts`

#### Change 1: Update Imports

**Location:** Lines 1-10

```diff
  import { CommonModule } from '@angular/common';
  import { Component, Inject, inject, OnInit, signal } from '@angular/core';
  import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
  import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
- import { MatIconModule } from '@angular/material/icon';
- import { MatSlideToggleModule } from '@angular/material/slide-toggle';
  import { GlassInputComponent } from '../shared/components/glass-input.component';
  import { GlassSelectComponent } from '../shared/components/glass-select.component';
+ import { GlassIconComponent } from '../shared/components/glass-icon.component';
  import { ToastService } from '../shared/services/toast.service';
  import { User, UserService } from './user.service';
```

#### Change 2: Update Component Imports Array

**Location:** Lines 13-23

```diff
  @Component({
    selector: 'app-user-form-dialog',
    standalone: true,
    imports: [
      CommonModule,
      ReactiveFormsModule,
      MatDialogModule,
-     MatIconModule,
-     MatSlideToggleModule,
      GlassInputComponent,
      GlassSelectComponent,
+     GlassIconComponent,
    ],
```

#### Change 3: Replace Material Icon in Template

**Location:** Template `<mat-icon>` → `<app-glass-icon>`

```diff
  <div class="dialog-header">
-   <mat-icon class="header-icon">{{ data.mode === 'create' ? 'person_add' : 'edit' }}</mat-icon>
+   <app-glass-icon [name]="data.mode === 'create' ? 'person_add' : 'edit'" [size]="24" class="header-icon"></app-glass-icon>
    <h2>{{ data.mode === 'create' ? 'Create New User' : 'Edit User Profile' }}</h2>
  </div>
```

#### Change 4: Replace Material Slide Toggle in Template

**Location:** Template in options-row

```diff
  <div class="options-row">
    <div class="toggle-group">
-     <mat-slide-toggle formControlName="enabled" color="primary">Enable Account</mat-slide-toggle>
+     <label class="checkbox-label">
+       <input type="checkbox" formControlName="enabled" class="checkbox-input" />
+       <span class="checkbox-custom"></span>
+       <span class="checkbox-text">Enable Account</span>
+     </label>
      <span class="toggle-help">Allows the user to authenticate through the portal.</span>
    </div>
  </div>
```

#### Change 5: Replace Mat Icon in Save Button

**Location:** Template footer section

```diff
  <button
    class="save-btn"
    [disabled]="userForm.invalid || loading()"
    (click)="onSubmit()"
    [class.is-loading]="loading()"
  >
    <span *ngIf="!loading()">{{ data.mode === 'create' ? 'Create User' : 'Save Changes' }}</span>
    <span *ngIf="loading()">Saving...</span>
-   <mat-icon *ngIf="!loading()">{{ data.mode === 'create' ? 'add' : 'check' }}</mat-icon>
+   <app-glass-icon *ngIf="!loading()" [name]="data.mode === 'create' ? 'add' : 'check'" [size]="16"></app-glass-icon>
  </button>
```

#### Change 6: Update SCSS

**Location:** Entire styles array - Replace with new glassmorphism SCSS that includes:

- Improved `.checkbox-custom` styling
- Better button hover states
- Enhanced focus rings
- Mobile responsive adjustments
- Proper `.checkbox-input:checked` states

---

### File: `frontend/src/app/admin/admin-pipeline-config.component.ts`

#### Change 1: Remove Material Import

**Location:** Line 4

```diff
  import { CommonModule } from '@angular/common';
  import { Component, inject, OnInit, signal } from '@angular/core';
  import { FormControl, ReactiveFormsModule } from '@angular/forms';
- import { MatDialogModule } from '@angular/material/dialog';
  import { debounceTime, distinctUntilChanged } from 'rxjs';
```

#### Change 2: Update Component Imports

**Location:** Lines 18-27

```diff
  @Component({
    selector: 'app-admin-pipeline-config',
    standalone: true,
    imports: [
      CommonModule,
      ReactiveFormsModule,
-     MatDialogModule,
      GlassSelectComponent,
      GlassIconComponent,
      GlassTooltipDirective,
      GlassPaginationComponent,
    ],
```

---

### File: `frontend/src/app/admin/admin-etl-server-config.component.ts`

#### Change 1: Remove Material Import

**Location:** Line 4

```diff
  import { CommonModule } from '@angular/common';
  import { Component, inject, OnInit, signal } from '@angular/core';
  import { FormControl, ReactiveFormsModule } from '@angular/forms';
- import { MatDialogModule } from '@angular/material/dialog';
  import { debounceTime, distinctUntilChanged } from 'rxjs';
```

#### Change 2: Update Component Imports

**Location:** Component decorator

```diff
  @Component({
    selector: 'app-admin-etl-server-config',
    standalone: true,
    imports: [
      CommonModule,
      ReactiveFormsModule,
-     MatDialogModule,
      GlassSelectComponent,
      GlassIconComponent,
      GlassTooltipDirective,
      GlassPaginationComponent,
    ],
```

---

### File: `frontend/src/app/admin/admin-db-connection-config.component.ts`

#### Change 1: Remove Material Import

**Location:** Line 4

```diff
  import { CommonModule } from '@angular/common';
  import { Component, inject, OnInit, signal } from '@angular/core';
  import { FormControl, ReactiveFormsModule } from '@angular/forms';
- import { MatDialogModule } from '@angular/material/dialog';
  import { debounceTime, distinctUntilChanged } from 'rxjs';
```

#### Change 2: Update Component Imports

**Location:** Component decorator

```diff
  @Component({
    selector: 'app-admin-db-connection-config',
    standalone: true,
    imports: [
      CommonModule,
      ReactiveFormsModule,
-     MatDialogModule,
      GlassSelectComponent,
      GlassIconComponent,
      GlassTooltipDirective,
      GlassPaginationComponent,
    ],
```

---

## Key SCSS Addition (user-form-dialog.component.ts)

New checkbox styling added to styles array:

```scss
.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  cursor: pointer;
  user-select: none;
}

.checkbox-input {
  display: none;
}

.checkbox-custom {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: rgba(255, 255, 255, 0.05);
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  position: relative;
}

.checkbox-input:checked + .checkbox-custom {
  background: var(--accent-color);
  border-color: var(--accent-color);
}

.checkbox-input:checked + .checkbox-custom::after {
  content: '✓';
  color: white;
  font-size: 14px;
  font-weight: bold;
}

.checkbox-label:hover .checkbox-custom {
  border-color: rgba(255, 255, 255, 0.4);
}

.checkbox-text {
  font-weight: 500;
  color: white;
}
```

---

## Summary Table

| File                                    | Type     | Changes          | Impact              |
| --------------------------------------- | -------- | ---------------- | ------------------- |
| UserAdminController.java                | Backend  | 3 changes        | Authorization fixed |
| user-form-dialog.component.ts           | Frontend | 6 changes + SCSS | Material UI removed |
| admin-pipeline-config.component.ts      | Frontend | 2 changes        | Material UI removed |
| admin-etl-server-config.component.ts    | Frontend | 2 changes        | Material UI removed |
| admin-db-connection-config.component.ts | Frontend | 2 changes        | Material UI removed |

**Total Changes:** 15
**Compilation Errors:** 0 ✅
**Lines Added:** ~50
**Lines Removed:** ~15
**Net Lines:** +35

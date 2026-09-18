# Agent 02: TamperDetectionEngine Adversarial Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 02 — TamperDetectionEngine Adversarial Auditor  
**Target:** `TamperEvent.kt`, `TamperDetectionEngine.kt`  
**Status:** COMPLETE (Adversarial Static Attack)

---

## 1. Executive Summary

This adversarial audit focused exclusively on attacking the detection model defined in `TamperEvent.kt` and implemented in `TamperDetectionEngine.kt`.

The engine was evaluated for theoretical correctness, boundary conditions, linguistic resilience, false-positive vulnerability, and false-negative bypass susceptibility.

**Core Findings:**
1. **Critical False Positive on Device Admin List:**
   In `evaluateSettings` Sub-route B (`TamperDetectionEngine.kt:142`):
   ```kotlin
   if (isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction))
   ```
   Viewing the general list of Device Admin apps (where LockKeeper is listed) triggers `TamperType.DISABLE_DEVICE_ADMIN`, blocking the user from even browsing Device Administrators or deactivating *unrelated* apps (e.g., Google Find My Device).
2. **Severe Traversal Pruning (False Negatives):**
   DFS traversal is strictly cut off at `MAX_TRAVERSAL_DEPTH = 6` or `MAX_TRAVERSED_NODES = 45`. Standard Android Settings view hierarchies (especially Jetpack Compose, Material3, or OEM custom layouts like MIUI/OneUI) routinely contain 80–250+ nodes and depths exceeding 8 levels. If the header, search bar, or top navigation chips consume the first 45 nodes, all uninstall/force-stop action buttons are pruned, resulting in complete bypass (false negative).
3. **Fragile Package Whitelist:**
   Only `com.android.settings`, a small hardcoded list of package installers (`PACKAGE_INSTALLER_PACKAGES`), and 3 OEM security apps (`com.miui.securitycenter`, `com.samsung.android.lool`, `com.coloros.safecenter`) are checked. Any third-party launcher, alternative package installer, or OEM management app outside this list operates without detection.
4. **Keyword & Language Fragility:**
   Keywords for actions are hardcoded for 6–8 languages. Major world languages (e.g., Russian, Japanese, Korean, Arabic, Turkish, Vietnamese, Indonesian, Polish) are missing for uninstall, force stop, and clear data. On devices set to these languages where resource IDs differ from AOSP, detection completely fails.
5. **Dead Code & Decorative Enums:**
   `TamperSource.LAUNCHER`, `TamperSource.PLAY_STORE`, `TamperSource.OTHER`, `TamperType.SECURITY_SETTINGS`, and `TamperType.UNKNOWN` are declared in `TamperEvent.kt` but are NEVER emitted anywhere in `TamperDetectionEngine.kt`.

---

## 2. In-Depth Component Analysis

### 2.1 Package Filtering (`TamperDetectionEngine.kt:62-90`)
The entry point restricts evaluation to three specific package categories:
```kotlin
// 1. Settings Evaluation
if (packageName == "com.android.settings") {
    return evaluateSettings(className, lowerClass, rootNode, isDeviceAdminActive)
}
// 2. Package Installer Evaluation
if (isPackageInstaller(packageName)) {
    return evaluatePackageInstaller(packageName, className, lowerClass, rootNode)
}
// 3. OEM Security Center Evaluation
if (isOemSecurityCenter(packageName)) {
    return evaluateOemSecurityCenter(packageName, className, lowerClass, rootNode)
}
return null
```

#### Adversarial Flaws:
- **Exact Match on Settings:** Only `"com.android.settings"` is matched. Devices utilizing vendor-specific settings packages (e.g., Transsion devices running `com.transsion.settings` or customized vendor ROMs) completely bypass all settings tamper detection.
- **Exclusion of Launchers:** Despite `TamperSource.LAUNCHER` existing in `TamperEvent.kt`, `evaluate()` returns `null` immediately for ANY launcher package (e.g., Nova Launcher `com.teslacoilsw.launcher`, Niagara Launcher `bitpit.launcher`, Lawnchair `ch.deletescape.lawnchair`, Samsung Home `com.sec.android.app.launcher`, Pixel Launcher `com.google.android.apps.nexuslauncher`). If a launcher provides its own internal uninstall confirmation dialog, it is ignored 100% of the time.
- **Exclusion of Google Play Store:** Google Play Store (`com.android.vending`) is not evaluated. An attacker can uninstall LockKeeper directly from the Play Store "Manage apps & device" screen without encountering any gate.

---

### 2.2 Node Traversal Engine (`TamperDetectionEngine.kt:280-308`)
```kotlin
private const val MAX_TRAVERSAL_DEPTH = 6
private const val MAX_TRAVERSED_NODES = 45
...
fun traverse(node: NodeFacade, depth: Int) {
    if (depth > MAX_TRAVERSAL_DEPTH || count >= MAX_TRAVERSED_NODES) return
    count++
    ...
    for (i in 0 until node.childCount) {
        if (count >= MAX_TRAVERSED_NODES) break
        val child = node.getChild(i) ?: continue
        traverse(child, depth + 1)
    }
}
```

#### Adversarial Flaws:
1. **Pre-Order DFS Starvation:**
   Traversal is implemented as depth-first search (`traverse(child, depth + 1)`).
   In Android Settings:
   - Root Node (`DecorView`) -> Depth 0
   - `LinearLayout` (Decor content) -> Depth 1
   - `ActionBarOverlayLayout` -> Depth 2
   - `AppBarLayout` / Toolbar / CollapsingToolbarLayout -> Depth 3
   - Top action bars, search icons, back buttons, and navigation breadcrumbs consume 15–30 nodes.
   - `FrameLayout` (Content parent) -> Depth 4
   - `CoordinatorLayout` / `NestedScrollView` -> Depth 5
   - `RecyclerView` / `ComposeView` -> Depth 6 (Maximum depth limit hit!)
   - Children inside the list or card containers are at Depth 7 or 8.
   **Result:** Nodes at depth >= 7 are NEVER evaluated because `depth > 6` aborts immediately. Action buttons (Uninstall, Force Stop, Storage) housed inside card wrappers at depth 7 or deeper are completely invisible to the engine.
2. **45-Node Budget Exhaustion:**
   If the active window contains a large search bar, user profile switcher, or complex banner, `count >= 45` triggers before the DFS reaches the app header or action buttons.
   Once `count >= 45`, `traverse()` returns immediately. Neither `mentionsLockKeeper` nor `hasAppActionIds` will ever be populated, resulting in a silent false negative (bypass).

---

### 2.3 Sub-Route Evaluation Vulnerabilities

#### Sub-Route A: Accessibility Settings Disabling (`TamperDetectionEngine.kt:117-132`)
```kotlin
val isA11yToggleTarget = nodeCollector.containsAnyTextOrDesc(ACCESSIBILITY_TOGGLE_KEYWORDS) ||
        (mentionsLockKeeper && lowerClass.contains("toggleaccessibilityservicepreferencefragment"))
```
- `ACCESSIBILITY_TOGGLE_KEYWORDS` contains only:
  `"use lockkeeper", "stop lockkeeper", "usar lockkeeper", "utiliser lockkeeper"`.
- If the device is set to German ("LockKeeper verwenden"), Italian ("Usa LockKeeper"), Hindi, Russian, or Chinese:
  - Keyword match fails.
  - If the OEM uses an Activity/Fragment other than AOSP `ToggleAccessibilityServicePreferenceFragment` (e.g., Samsung's `SecAccessibilityServiceDetailsPreference`, Xiaomi's `MiuiAccessibilityServiceDetailsActivity`, or Oppo's `OplusAccessibilityDetailsActivity`), class match fails.
  - **Result:** Attacker disables LockKeeper's Accessibility Service with zero password challenge!

#### Sub-Route B: Device Admin Deactivation (`TamperDetectionEngine.kt:133-152`)
```kotlin
if (isDeviceAdminActive) {
    val isDeviceAdminScreen = lowerClass.contains("deviceadmin") ||
            nodeCollector.containsTextOrDesc("device admin") ||
            nodeCollector.containsTextOrDesc("administrador de dispositivos")

    val hasDeactivateAction = nodeCollector.containsAnyTextOrDesc(DEACTIVATE_ADMIN_KEYWORDS)

    if (isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction)) {
        return TamperEvent(
            type = TamperType.DISABLE_DEVICE_ADMIN,
            source = TamperSource.SETTINGS,
            confidence = TamperConfidence.HIGH,
            targetPackage = appPackageName,
            targetActivity = className
        )
    }
}
```
- **Catastrophic False Positive:**
  Notice `(mentionsLockKeeper || hasDeactivateAction)`!
  - Case 1: User navigates to Settings -> Security -> Device Admin Apps. The screen lists all installed admin apps. LockKeeper is listed. Therefore `mentionsLockKeeper` is TRUE! `isDeviceAdminScreen` is TRUE! The engine detects `DISABLE_DEVICE_ADMIN` and presents the Admin overlay immediately when merely viewing the list! The user cannot inspect their device admin apps!
  - Case 2: User opens an *unrelated* Device Admin app (e.g. Google Find My Device) to deactivate it. The screen has `hasDeactivateAction` ("Deactivate this device admin app")! Even though LockKeeper is NOT mentioned on that screen, `hasDeactivateAction` satisfies the condition! LockKeeper pops an overlay over Find My Device deactivation, falsely attributing `targetPackage = "com.lockkeeper.app"`!

#### Sub-Route C: Storage & Cache Clear Data (`TamperDetectionEngine.kt:153-166`)
```kotlin
val isStorageScreen = lowerClass.contains("storageuseactivity") ||
        lowerClass.contains("appstoragesettings") ||
        lowerClass.contains("cleardatadialog")

if (isStorageScreen && mentionsLockKeeper) { ... }
```
- If the storage management screen on an OEM is `ManageSpaceActivity`, `StorageDashboardFragment`, or `AppStorageActivity`, `isStorageScreen` is FALSE!
- If the user opens the dialog "Clear all data?" and the dialog text says "All of this app's data will be deleted permanently" without explicitly repeating the string "LockKeeper", `mentionsLockKeeper` is FALSE!
- **Result:** Storage clear succeeds without triggering tamper detection!

#### Sub-Route D: App Info Details (`TamperDetectionEngine.kt:168-204`)
```kotlin
val isAppInfoClass = lowerClass.contains("installedappdetails") ||
        lowerClass.contains("appinfodashboardfragment") ||
        lowerClass.contains("appbuttonspreferencecontroller") ||
        lowerClass.contains("spaactivity") ||
        lowerClass.contains("applicationsettings")
```
- Fails on Samsung One UI (`SecAppInfoActivity`, `AppDetailsPreferenceFragment`), Huawei EMUI (`AppControlDetailsActivity`), and customized modern OEM frameworks.
- Requires `mentionsLockKeeper`. If the app header is rendered as an icon without readable text or if text is outside the 45-node DFS window, `mentionsLockKeeper` is FALSE, causing complete detection failure.

---

### 2.4 Package Installer Evaluation (`TamperDetectionEngine.kt:207-243`)
```kotlin
val mentionsLockKeeper = nodeCollector.containsTextOrDesc(appPackageName) ||
        nodeCollector.containsTextOrDesc("lockkeeper")

if (!mentionsLockKeeper) {
    return null // Unrelated app being uninstalled! Strict false-positive protection.
}
```
- In Android 10+ AOSP and Samsung Package Installer, the uninstallation confirmation dialog frequently displays:
  - App icon (ImageView without text or description)
  - Dialog title: "Do you want to uninstall this app?"
  - Positive/Negative buttons: "Cancel" / "OK"
- Unless the system explicitly injects the label into the dialog message ("Do you want to uninstall LockKeeper?"), `mentionsLockKeeper` evaluates to FALSE!
- **Result:** The system uninstaller dialog is completely ignored, allowing one-tap uninstallation from launcher drag actions!

---

## 3. False Positive & False Negative Matrix

| Scenario | Engine Evaluation | Expected Result | Defect Classification |
|---|---|---|---|
| Open Device Admin Apps list | Detected (`DISABLE_DEVICE_ADMIN`) | Allowed (browsing list) | **FALSE POSITIVE (Critical)** |
| Deactivate Google Find My Device | Detected (`DISABLE_DEVICE_ADMIN` on LockKeeper) | Allowed (unrelated app) | **FALSE POSITIVE (Critical)** |
| German A11y toggle ("LockKeeper verwenden") | Ignored (`null`) | Intercepted | **FALSE NEGATIVE (Bypass)** |
| Samsung OneUI App Info (`SecAppInfoActivity`) | Ignored if node count > 45 | Intercepted | **FALSE NEGATIVE (Bypass)** |
| Nova Launcher uninstall dialog | Ignored (`packageName` rejected) | Intercepted | **FALSE NEGATIVE (Bypass)** |
| Google Play Store uninstallation | Ignored (`packageName` rejected) | Documented Policy | **BYPASS (By Design)** |
| Storage Clear Dialog without app title | Ignored (`mentionsLockKeeper` false) | Intercepted | **FALSE NEGATIVE (Bypass)** |

---

## 4. Auditor Conclusion

`TamperDetectionEngine.kt` is structurally brittle:
1. It produces catastrophic false positives on general Device Admin navigation and unrelated admin deactivations.
2. It is trivially bypassed through OEM class name variations, unlisted languages, view trees deeper than 6 levels or wider than 45 nodes, and any third-party launcher.
3. The claim of multi-signal, multilingual robustness is refuted by static code evidence.

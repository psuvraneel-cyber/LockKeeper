# UI/UX Specification — LockKeeper

## Design Principles
- **Utilitarian, not polished-for-market.** This is a personal tool; clarity and speed of interaction matter far more than visual flourish.
- **Friction where it counts, nowhere else.** Day-to-day app browsing and PIN entry should be fast; only the protected admin actions should feel deliberately slow/effortful.
- **Dark theme by default** (reduces flash/glare during frequent lock-screen interruptions).

## Screen Inventory

### 1. Onboarding Wizard
Linear, step-by-step, cannot be skipped (each step gates the next):
1. Welcome / explanation of what the app does
2. Grant "Display over other apps" (SYSTEM_ALERT_WINDOW)
3. Grant Usage Access
4. Enable Accessibility Service (with plain-language explanation of why)
5. Activate Device Admin (with plain-language explanation of why)
6. Exempt from battery optimization
7. Set PIN (numeric, 4–8 digits, confirm twice)
8. Set Admin Password (distinct from PIN, confirm twice, brief warning: "You'll need this to ever uninstall or disable this app")
9. Done / summary screen

Each permission step shows: what it's for, a "Grant" button that deep-links to the correct system settings screen, and detects on-return whether it was actually granted before allowing "Next."

### 2. Home / App List Screen
- Searchable list of installed launchable apps, each with icon, name, and a lock toggle switch.
- Locked apps show a small lock icon + remaining cooldown time (if currently in cooldown) as a subtitle.
- Tapping a locked app's row (not the toggle) opens the App Lock Detail screen.

### 3. App Lock Detail Screen
- App icon/name header.
- "Locked" toggle (on/off).
- Cooldown duration selector (chips: 15m / 1h / 4h / 24h / Custom).
- "Strict lock" toggle — if on, PIN cannot re-open the app during cooldown at all; if off, PIN always works but cooldown still restarts the timer on close.
- Save button.

### 4. PIN Entry / Lock Overlay Screen
- Full-screen, drawn as a system overlay on top of the target app.
- Large numeric keypad, PIN dots indicator, app name/icon being unlocked shown at top.
- No visible "back," "home," or "recent apps" affordance from this screen — back button press is intercepted/no-ops.
- Wrong PIN: shake animation + attempt counter; after 5 fails, keypad disables for 60s with a visible countdown.
- If the app is in a "strict lock" cooldown, this screen instead shows a countdown-only view ("Locked for 42 more minutes") with no PIN field at all.

### 5. Admin Password Prompt (Settings Interception)
- Same full-screen overlay style as the PIN screen but visually distinct (e.g., red/amber accent instead of the standard accent) so it's immediately clear this is the higher-stakes prompt.
- Shown whenever the Accessibility Service detects navigation into a protected system screen (App Info/uninstall, Device Admin list, Accessibility toggle for this app).
- Correct entry: allows the underlying system screen interaction to proceed for a short grace window (e.g., 30s) before re-locking.
- Incorrect/dismissed: triggers a "back" global action, returning the user out of the sensitive screen.

### 6. App Settings Screen (within LockKeeper)
- Change PIN (requires current PIN)
- Change Admin Password (requires current Admin Password)
- Review granted permissions / re-launch onboarding step for any revoked permission
- About / version info

## Navigation Flow (textual)
```
Onboarding (first run only)
   └── Home (App List)
         ├── App Lock Detail (per app)
         └── App Settings
Any locked app opened externally
   └── Accessibility detects foreground change
         └── PIN Entry / Lock Overlay (or Countdown view if strict-locked)
Any protected system screen opened externally
   └── Accessibility detects navigation
         └── Admin Password Prompt
```

## Error & Edge States
- Permission revoked mid-use (e.g., user manually disables Usage Access from Settings): Home screen shows a persistent banner "Some protections are disabled — tap to fix," linking back into the relevant onboarding step.
- Overlay permission missing: locking silently cannot function — treat this as a blocking state, not a soft warning, since it's core to the app's purpose.
- Forgotten PIN vs. forgotten Admin Password: no in-app recovery flow (by design — a recovery backdoor would undermine the entire threat model). Document this clearly to the user during onboarding so it's an informed choice.

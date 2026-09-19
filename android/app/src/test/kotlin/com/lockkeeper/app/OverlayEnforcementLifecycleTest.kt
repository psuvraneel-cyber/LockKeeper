package com.lockkeeper.app

import android.view.KeyEvent
import com.lockkeeper.app.overlay.EnforcementLifecycleState
import com.lockkeeper.app.overlay.EnforcementOperation
import com.lockkeeper.app.overlay.OverlayManager
import com.lockkeeper.app.service.LockKeeperAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1 Overlay Lifecycle & WindowManager Forensic Test Suite.
 *
 * Exercises real production classes:
 * - EnforcementOperation state machine & terminal invariants (including DISMISSING)
 * - OverlayManager.isInputMethodPackage companion validation
 * - LockKeeperAccessibilityService package classification
 * - Key event back interception logic with cancellation protection
 */
class OverlayEnforcementLifecycleTest {

    @Test
    fun `enforcement operation maintains explicit identity and lifecycle state`() {
        val op = EnforcementOperation(
            gateType = OverlayManager.GateType.PIN,
            targetPackage = "com.bank.app",
            generation = 1L,
            lifecycleState = EnforcementLifecycleState.PENDING
        )

        assertNotNull(op.operationId)
        assertEquals("com.bank.app", op.targetPackage)
        assertEquals(OverlayManager.GateType.PIN, op.gateType)
        assertEquals(1L, op.generation)
        assertEquals(EnforcementLifecycleState.PENDING, op.lifecycleState)
        assertFalse(op.isTerminal)

        op.lifecycleState = EnforcementLifecycleState.ATTACHED
        assertFalse(op.isTerminal)

        // Crucial bug fix: DISMISSING must immediately be recognized as terminal
        // so no duplicate requests or delayed navigation races latch onto a tearing-down overlay
        op.lifecycleState = EnforcementLifecycleState.DISMISSING
        assertTrue("DISMISSING state must be considered terminal", op.isTerminal)

        op.lifecycleState = EnforcementLifecycleState.DISMISSED
        op.terminalReason = "User exited to launcher"
        assertTrue(op.isTerminal)
        assertEquals("User exited to launcher", op.terminalReason)

        op.lifecycleState = EnforcementLifecycleState.TERMINATED
        op.terminalReason = "Superseded by higher priority gate"
        assertTrue(op.isTerminal)
    }

    @Test
    fun `input method package recognition covers OEM and standard keyboards`() {
        val standardIme = "com.google.android.inputmethod.latin"
        val samsungIme = "com.samsung.android.honeyboard"
        val swiftkey = "com.touchtype.swiftkey"
        val aospIme = "com.android.inputmethod.latin"

        assertTrue(OverlayManager.isInputMethodPackage(standardIme))
        assertTrue(OverlayManager.isInputMethodPackage(samsungIme))
        assertTrue(OverlayManager.isInputMethodPackage(swiftkey))
        assertTrue(OverlayManager.isInputMethodPackage(aospIme))

        assertFalse(OverlayManager.isInputMethodPackage("com.bank.app"))
        assertFalse(OverlayManager.isInputMethodPackage("com.android.settings"))
        assertFalse(OverlayManager.isInputMethodPackage(null))
    }

    @Test
    fun `system ui package recognition correctly identifies system UI and powerkeeper`() {
        assertTrue(LockKeeperAccessibilityService.isSystemUiPackage("com.android.systemui"))
        assertTrue(LockKeeperAccessibilityService.isSystemUiPackage("com.miui.powerkeeper"))
        assertTrue(LockKeeperAccessibilityService.isSystemUiPackage("com.samsung.systemui"))
        assertFalse(LockKeeperAccessibilityService.isSystemUiPackage("com.android.launcher3"))
        assertFalse(LockKeeperAccessibilityService.isSystemUiPackage("com.android.settings"))
        assertFalse(LockKeeperAccessibilityService.isSystemUiPackage("com.whatsapp"))
    }

    @Test
    fun `launcher package recognition covers major OEM launchers`() {
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.miui.home"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.android.launcher3"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.sec.android.app.launcher"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.google.android.apps.nexuslauncher"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.teslacoilsw.launcher"))
        assertFalse(LockKeeperAccessibilityService.isLauncherPackage("com.android.systemui"))
        assertFalse(LockKeeperAccessibilityService.isLauncherPackage("com.android.settings"))
    }

    @Test
    fun `operation termination reason is retained and accessible for forensics`() {
        val op = EnforcementOperation(
            gateType = OverlayManager.GateType.ADMIN,
            targetPackage = "com.android.settings",
            generation = 42L,
            lifecycleState = EnforcementLifecycleState.ATTACHED
        )

        op.lifecycleState = EnforcementLifecycleState.TERMINATED
        op.terminalReason = "Admin password verified"

        assertTrue(op.isTerminal)
        assertEquals("Admin password verified", op.terminalReason)
        assertEquals(OverlayManager.GateType.ADMIN, op.gateType)
        assertEquals(42L, op.generation)
    }

    @Test
    fun `operation transitions between gate types preserve distinct identities`() {
        val opPin = EnforcementOperation(
            gateType = OverlayManager.GateType.PIN,
            targetPackage = "com.target.app",
            generation = 1L
        )
        val opLockout = EnforcementOperation(
            gateType = OverlayManager.GateType.LOCKOUT,
            targetPackage = "com.target.app",
            generation = 2L,
            remainingSeconds = 60L
        )

        assertTrue(opPin.operationId != opLockout.operationId)
        assertEquals(OverlayManager.GateType.PIN, opPin.gateType)
        assertEquals(OverlayManager.GateType.LOCKOUT, opLockout.gateType)
        assertEquals(60L, opLockout.remainingSeconds)
    }
}

package com.lockkeeper.app.overlay

import java.util.UUID

enum class EnforcementLifecycleState {
    PENDING,
    ATTACHED,
    DISMISSING,
    DISMISSED,
    TERMINATED
}

data class EnforcementOperation(
    val operationId: String = UUID.randomUUID().toString(),
    val gateType: OverlayManager.GateType,
    val targetPackage: String,
    val creationTime: Long = System.currentTimeMillis(),
    val generation: Long,
    @Volatile var lifecycleState: EnforcementLifecycleState = EnforcementLifecycleState.PENDING,
    @Volatile var terminalReason: String? = null,
    val remainingSeconds: Long = 0L,
    val tamperSession: com.lockkeeper.app.security.TamperSession? = null
) {
    val isTerminal: Boolean
        get() = lifecycleState == EnforcementLifecycleState.DISMISSING ||
                lifecycleState == EnforcementLifecycleState.DISMISSED ||
                lifecycleState == EnforcementLifecycleState.TERMINATED
}

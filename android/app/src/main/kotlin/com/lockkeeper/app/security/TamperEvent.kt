package com.lockkeeper.app.security

enum class TamperType {
    UNINSTALL,
    CLEAR_DATA,
    FORCE_STOP,
    DISABLE_APP,
    DISABLE_ACCESSIBILITY,
    DISABLE_DEVICE_ADMIN,
    SECURITY_SETTINGS,
    UNKNOWN
}

enum class TamperSource {
    SETTINGS,
    PACKAGE_INSTALLER,
    OEM_INSTALLER,
    LAUNCHER,
    PLAY_STORE,
    OTHER
}

enum class TamperConfidence {
    HIGH,
    MEDIUM,
    LOW
}

data class TamperEvent(
    val type: TamperType,
    val source: TamperSource,
    val confidence: TamperConfidence,
    val targetPackage: String,
    val targetActivity: String,
    val timestamp: Long = System.currentTimeMillis()
)

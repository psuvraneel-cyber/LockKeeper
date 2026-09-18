package com.lockkeeper.app.security

interface NodeFacade {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val viewIdResourceName: String?
    val className: CharSequence?
    val childCount: Int
    fun getChild(index: Int): NodeFacade?
}

class TamperDetectionEngine(
    private val appPackageName: String = "com.lockkeeper.app"
) {
    companion object {
        private const val MAX_TRAVERSAL_DEPTH = 12
        private const val MAX_TRAVERSED_NODES = 120

        // Multi-lingual keyword dictionaries (fallback signals)
        private val UNINSTALL_KEYWORDS = listOf(
            "uninstall", "desinstalar", "désinstaller", "deinstallieren",
            "disinstalla", "desinstalação", "odinstaluj", "अनइंस्टॉल", "卸载", "アンインストール"
        )
        private val FORCE_STOP_KEYWORDS = listOf(
            "force stop", "forzar detención", "forcer l'arrêt",
            "beenden erzwingen", "interruzione forzata", "forçar parada", "强行停止", "強制停止"
        )
        private val CLEAR_DATA_KEYWORDS = listOf(
            "clear storage", "clear data", "borrar datos", "borrar almacenamiento",
            "effacer les données", "speicherinhalt löschen", "cancella dati", "limpar dados", "清除数据", "データを消去"
        )
        private val DEACTIVATE_ADMIN_KEYWORDS = listOf(
            "deactivate this device admin app", "deactivate", "remove active admin",
            "desactivar esta aplicación de administración", "desactivar", "désactiver", "deaktivieren", "disattiva",
            "desativar este app do administrador do dispositivo", "停用此设备管理应用", "このデバイス管理アプリを無効化",
            "this admin app is active", "admin app is active", "allows the app lockkeeper", "allows the app",
            "deactivation", "unauthorized deactivation", "deactivat",
            "desactivación", "désactivation", "deaktivierung", "disattivazione", "desativação"
        )
        private val DISABLE_KEYWORDS = listOf(
            "disable", "inhabilitar", "désactiver", "deaktivieren", "disabilita", "desativar", "停用", "無効化"
        )
        private val ACCESSIBILITY_TOGGLE_KEYWORDS = listOf(
            "use lockkeeper", "stop lockkeeper", "usar lockkeeper", "utiliser lockkeeper",
            "lockkeeper verwenden", "usa lockkeeper", "usar o lockkeeper"
        )

        // Known package installer package identifiers
        private val PACKAGE_INSTALLER_PACKAGES = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.coloros.packageinstaller",
            "com.oppo.packageinstaller",
            "com.vivo.abe",
            "com.android.vending"
        )

        // Known OEM security/maintenance apps
        private val OEM_SECURITY_PACKAGES = listOf(
            "com.miui.securitycenter",
            "com.samsung.android.lool",
            "com.coloros.safecenter"
        )
    }

    fun evaluate(
        packageName: String,
        className: String,
        rootNode: NodeFacade?,
        isDeviceAdminActive: Boolean
    ): TamperEvent? {
        if (packageName.isBlank() || packageName == appPackageName) {
            return null
        }

        val lowerClass = className.lowercase()

        // 1. Settings Evaluation
        if (packageName == "com.android.settings") {
            return evaluateSettings(className, lowerClass, rootNode, isDeviceAdminActive)
        }

        // 2. Package Installer Evaluation (Launcher drag-to-uninstall or context-menu uninstall)
        if (isPackageInstaller(packageName)) {
            return evaluatePackageInstaller(packageName, className, lowerClass, rootNode)
        }

        // 3. OEM Security Center Evaluation (Xiaomi Security, Samsung Device Care)
        if (isOemSecurityCenter(packageName)) {
            return evaluateOemSecurityCenter(packageName, className, lowerClass, rootNode)
        }

        return null
    }

    private fun isPackageInstaller(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return PACKAGE_INSTALLER_PACKAGES.any { lower == it } ||
                lower.endsWith(".packageinstaller")
    }

    private fun isOemSecurityCenter(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return OEM_SECURITY_PACKAGES.any { lower == it }
    }

    private fun evaluateSettings(
        className: String,
        lowerClass: String,
        rootNode: NodeFacade?,
        isDeviceAdminActive: Boolean
    ): TamperEvent? {
        if (rootNode == null) return null

        val nodeCollector = NodeCollector()
        nodeCollector.traverse(rootNode)

        val mentionsLockKeeper = nodeCollector.containsTextOrDesc(appPackageName) ||
                nodeCollector.containsTextOrDesc("lockkeeper")

        // Sub-route A: Accessibility Settings for LockKeeper
        // CRITICAL FALSE-POSITIVE GUARD:
        // Do NOT trigger on general accessibility lists where LockKeeper is merely a summary list item!
        val isA11yToggleTarget = nodeCollector.containsAnyTextOrDesc(ACCESSIBILITY_TOGGLE_KEYWORDS) ||
                (mentionsLockKeeper && lowerClass.contains("toggleaccessibilityservicepreferencefragment"))

        if (isA11yToggleTarget) {
            return TamperEvent(
                type = TamperType.DISABLE_ACCESSIBILITY,
                source = TamperSource.SETTINGS,
                confidence = TamperConfidence.HIGH,
                targetPackage = appPackageName,
                targetActivity = className
            )
        }

        // Sub-route B: Device Admin Deactivation
        // Only trigger if Device Admin is ALREADY active. During onboarding setup, activating admin must be permitted!
        if (isDeviceAdminActive) {
            val isDeviceAdminScreen = lowerClass.contains("deviceadmin") ||
                    lowerClass.contains("alertdialog") ||
                    lowerClass.contains("dialog") ||
                    nodeCollector.containsTextOrDesc("device admin") ||
                    nodeCollector.containsTextOrDesc("administrador de dispositivos")

            val isGeneralAdminList = lowerClass.contains("settings\$deviceadminsettingsactivity") ||
                    lowerClass.contains("deviceadminsettings") ||
                    (nodeCollector.containsAnyTextOrDesc(listOf("device admin apps", "device admin settings", "administradores de dispositivos", "app de administración")) &&
                     !nodeCollector.containsAnyTextOrDesc(listOf("deactivate this device admin app", "desactivar esta aplicación de administración", "deactivate", "deactivation")))

            val hasDeactivateAction = nodeCollector.containsAnyTextOrDesc(DEACTIVATE_ADMIN_KEYWORDS) ||
                    nodeCollector.viewIds.any { it.contains("button_deactivate") || it.contains("action_button") }

            try {
                android.util.Log.i("TamperDetect", "mentionsLK=$mentionsLockKeeper isScreen=$isDeviceAdminScreen isGen=$isGeneralAdminList hasDeact=$hasDeactivateAction texts=${nodeCollector.texts}")
            } catch (_: Throwable) {}

            // MUST be targeted deactivation screen, MUST mention LockKeeper, MUST have deactivate action, and MUST NOT be a general list
            if (isDeviceAdminScreen && !isGeneralAdminList && mentionsLockKeeper && hasDeactivateAction) {
                return TamperEvent(
                    type = TamperType.DISABLE_DEVICE_ADMIN,
                    source = TamperSource.SETTINGS,
                    confidence = TamperConfidence.HIGH,
                    targetPackage = appPackageName,
                    targetActivity = className
                )
            }
        }

        // Sub-route C: Storage & Cache (Clear Data / Clear Storage)
        val isStorageScreen = lowerClass.contains("storageuseactivity") ||
                lowerClass.contains("appstoragesettings") ||
                lowerClass.contains("cleardatadialog")

        if (isStorageScreen && mentionsLockKeeper) {
            return TamperEvent(
                type = TamperType.CLEAR_DATA,
                source = TamperSource.SETTINGS,
                confidence = TamperConfidence.HIGH,
                targetPackage = appPackageName,
                targetActivity = className
            )
        }

        // Sub-route D: App Info Details (Uninstall / Force Stop / Disable)
        val isAppInfoClass = lowerClass.contains("installedappdetails") ||
                lowerClass.contains("appinfodashboardfragment") ||
                lowerClass.contains("appbuttonspreferencecontroller") ||
                lowerClass.contains("spaactivity") ||
                lowerClass.contains("applicationsettings")

        val hasAppActionIds = nodeCollector.viewIds.any { id ->
            id.contains("button1_negative") ||
            id.contains("uninstall_button") ||
            id.contains("force_stop_button") ||
            id.contains("right_button")
        }

        val hasUninstallAction = nodeCollector.containsAnyTextOrDesc(UNINSTALL_KEYWORDS)
        val hasForceStopAction = nodeCollector.containsAnyTextOrDesc(FORCE_STOP_KEYWORDS)
        val hasClearDataAction = nodeCollector.containsAnyTextOrDesc(CLEAR_DATA_KEYWORDS)
        val hasDisableAction = nodeCollector.containsAnyTextOrDesc(DISABLE_KEYWORDS)
        val hasDestructiveAction = hasAppActionIds || hasUninstallAction || hasForceStopAction || hasClearDataAction || hasDisableAction

        if (mentionsLockKeeper && isAppInfoClass && hasDestructiveAction) {
            val type = when {
                hasDisableAction && !hasUninstallAction -> TamperType.DISABLE_APP
                hasUninstallAction -> TamperType.UNINSTALL
                hasClearDataAction -> TamperType.CLEAR_DATA
                hasForceStopAction -> TamperType.FORCE_STOP
                else -> TamperType.UNINSTALL
            }
            return TamperEvent(
                type = type,
                source = TamperSource.SETTINGS,
                confidence = TamperConfidence.HIGH,
                targetPackage = appPackageName,
                targetActivity = className
            )
        }

        return null
    }

    private fun evaluatePackageInstaller(
        packageName: String,
        className: String,
        lowerClass: String,
        rootNode: NodeFacade?
    ): TamperEvent? {
        if (rootNode == null) return null

        val nodeCollector = NodeCollector()
        nodeCollector.traverse(rootNode)

        val mentionsLockKeeper = nodeCollector.containsTextOrDesc(appPackageName) ||
                nodeCollector.containsTextOrDesc("lockkeeper")

        if (!mentionsLockKeeper) {
            return null // Unrelated app being uninstalled! Strict false-positive protection.
        }

        val isUninstallActivity = lowerClass.contains("uninstaller") ||
                lowerClass.contains("uninstall") ||
                lowerClass.contains("alertactivity") ||
                lowerClass.contains("alertdialog") ||
                nodeCollector.containsAnyTextOrDesc(UNINSTALL_KEYWORDS) ||
                nodeCollector.viewIds.any { it.contains("button1") || it.contains("ok_button") }

        if (isUninstallActivity) {
            return TamperEvent(
                type = TamperType.UNINSTALL,
                source = TamperSource.PACKAGE_INSTALLER,
                confidence = TamperConfidence.HIGH,
                targetPackage = appPackageName,
                targetActivity = className
            )
        }

        return null
    }

    private fun evaluateOemSecurityCenter(
        packageName: String,
        className: String,
        lowerClass: String,
        rootNode: NodeFacade?
    ): TamperEvent? {
        if (rootNode == null) return null

        val nodeCollector = NodeCollector()
        nodeCollector.traverse(rootNode)

        val mentionsLockKeeper = nodeCollector.containsTextOrDesc(appPackageName) ||
                nodeCollector.containsTextOrDesc("lockkeeper")

        if (!mentionsLockKeeper) {
            return null
        }

        val hasTamperAction = nodeCollector.containsAnyTextOrDesc(UNINSTALL_KEYWORDS) ||
                nodeCollector.containsAnyTextOrDesc(FORCE_STOP_KEYWORDS) ||
                nodeCollector.containsAnyTextOrDesc(CLEAR_DATA_KEYWORDS)

        if (hasTamperAction || lowerClass.contains("appdetail") || lowerClass.contains("manageapp")) {
            return TamperEvent(
                type = TamperType.UNINSTALL,
                source = TamperSource.OEM_INSTALLER,
                confidence = TamperConfidence.MEDIUM,
                targetPackage = appPackageName,
                targetActivity = className
            )
        }

        return null
    }

    private class NodeCollector {
        val texts = mutableListOf<String>()
        val viewIds = mutableListOf<String>()
        private var count = 0

        fun traverse(root: NodeFacade) {
            val queue = ArrayDeque<Pair<NodeFacade, Int>>()
            queue.add(Pair(root, 0))

            while (queue.isNotEmpty() && count < MAX_TRAVERSED_NODES) {
                val (node, depth) = queue.removeFirst()
                count++

                node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts.add(it.lowercase()) }
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts.add(it.lowercase()) }
                node.viewIdResourceName?.let { viewIds.add(it.lowercase()) }

                if (depth < MAX_TRAVERSAL_DEPTH) {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        queue.add(Pair(child, depth + 1))
                    }
                }
            }
        }

        fun containsTextOrDesc(query: String): Boolean {
            val q = query.lowercase()
            return texts.any { it.contains(q) }
        }

        fun containsAnyTextOrDesc(keywords: List<String>): Boolean {
            return keywords.any { kw -> texts.any { it.contains(kw) } }
        }
    }
}

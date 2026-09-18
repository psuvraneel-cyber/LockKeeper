package com.lockkeeper.app

import com.lockkeeper.app.security.NodeFacade
import com.lockkeeper.app.security.TamperConfidence
import com.lockkeeper.app.security.TamperDetectionEngine
import com.lockkeeper.app.security.TamperSource
import com.lockkeeper.app.security.TamperType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TamperDetectionEngineTest {

    private lateinit var engine: TamperDetectionEngine

    @Before
    fun setUp() {
        engine = TamperDetectionEngine("com.lockkeeper.app")
    }

    private data class FakeNode(
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val viewIdResourceName: String? = null,
        override val className: CharSequence? = null,
        val children: List<FakeNode> = emptyList()
    ) : NodeFacade {
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): NodeFacade? = children.getOrNull(index)
    }

    @Test
    fun `settings app info in English triggers uninstall tamper event`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.settings:id/button1_negative"),
                FakeNode(text = "Force stop", viewIdResourceName = "com.android.settings:id/button2_negative")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.UNINSTALL, event?.type)
        assertEquals(TamperSource.SETTINGS, event?.source)
        assertEquals(TamperConfidence.HIGH, event?.confidence)
    }

    @Test
    fun `settings app info in Spanish triggers uninstall tamper event without English keywords`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Desinstalar", viewIdResourceName = "com.android.settings:id/button1_negative"),
                FakeNode(text = "Forzar detención", viewIdResourceName = "com.android.settings:id/button2_negative")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.spa.SpaActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull("Spanish settings must trigger tamper protection", event)
        assertEquals(TamperType.UNINSTALL, event?.type)
        assertEquals(TamperSource.SETTINGS, event?.source)
    }

    @Test
    fun `settings storage clear data triggers clear data tamper event`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper"),
                FakeNode(text = "Clear storage", viewIdResourceName = "com.android.settings:id/clear_data_button")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.StorageUseActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.CLEAR_DATA, event?.type)
        assertEquals(TamperSource.SETTINGS, event?.source)
    }

    @Test
    fun `package installer uninstalling LockKeeper triggers package installer tamper event`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "Do you want to uninstall LockKeeper?"),
                FakeNode(text = "OK", viewIdResourceName = "android:id/button1")
            )
        )

        val event = engine.evaluate(
            packageName = "com.google.android.packageinstaller",
            className = "com.android.packageinstaller.UninstallerActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.UNINSTALL, event?.type)
        assertEquals(TamperSource.PACKAGE_INSTALLER, event?.source)
        assertEquals(TamperConfidence.HIGH, event?.confidence)
    }

    @Test
    fun `package installer uninstalling unrelated app is ignored`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "Do you want to uninstall WhatsApp?"),
                FakeNode(text = "OK", viewIdResourceName = "android:id/button1")
            )
        )

        val event = engine.evaluate(
            packageName = "com.google.android.packageinstaller",
            className = "com.android.packageinstaller.UninstallerActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("Unrelated app uninstallation must NOT trigger LockKeeper overlay", event)
    }

    @Test
    fun `general accessibility settings listing is ignored to prevent black screen freeze`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "Accessibility"),
                FakeNode(text = "Downloaded apps"),
                FakeNode(text = "LockKeeper - Off"),
                FakeNode(text = "TalkBack - Off")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.MiuiAccessibilitySettingsActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("General Accessibility list must NOT be blocked", event)
    }

    @Test
    fun `device admin activation during onboarding is allowed when admin is not yet active`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper"),
                FakeNode(text = "Activate this device admin app")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isDeviceAdminActive = false
        )

        assertNull("Onboarding Device Admin activation must NOT be blocked", event)
    }

    @Test
    fun `device admin deactivation attempt is blocked when admin is active`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper"),
                FakeNode(text = "Deactivate this device admin app")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.DISABLE_DEVICE_ADMIN, event?.type)
        assertEquals(TamperSource.SETTINGS, event?.source)
    }

    @Test
    fun `settings app info with only force stop triggers force stop tamper event`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Force stop", viewIdResourceName = "com.android.settings:id/button2_negative")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.FORCE_STOP, event?.type)
        assertEquals(TamperSource.SETTINGS, event?.source)
    }

    @Test
    fun `settings app info with disable button triggers disable app tamper event`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Disable", viewIdResourceName = "com.android.settings:id/button1_negative")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.DISABLE_APP, event?.type)
        assertEquals(TamperSource.SETTINGS, event?.source)
    }

    @Test
    fun `individual accessibility service details screen for LockKeeper triggers accessibility disable event`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper"),
                FakeNode(text = "Use LockKeeper", viewIdResourceName = "android:id/switch_widget")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.DISABLE_ACCESSIBILITY, event?.type)
        assertEquals(TamperSource.SETTINGS, event?.source)
    }

    @Test
    fun `unrelated settings screen like wifi or display settings is ignored`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "Wi-Fi"),
                FakeNode(text = "Connected devices"),
                FakeNode(text = "Display"),
                FakeNode(text = "Wallpaper")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.Settings\$NetworkDashboardActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("Unrelated settings screens must never trigger tamper detection", event)
    }

    @Test
    fun `rapid burst of identical events produces consistent tamper events without state corruption`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.settings:id/button1_negative")
            )
        )

        // Simulate 20 rapid accessibility event callbacks in a loop
        for (i in 1..20) {
            val event = engine.evaluate(
                packageName = "com.android.settings",
                className = "com.android.settings.applications.InstalledAppDetails",
                rootNode = root,
                isDeviceAdminActive = true
            )
            assertNotNull(event)
            assertEquals(TamperType.UNINSTALL, event?.type)
            assertEquals(TamperConfidence.HIGH, event?.confidence)
        }
    }

    @Test
    fun `general device admin settings list showing LockKeeper among admins does NOT trigger tamper`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    text = "Device admin apps",
                    viewIdResourceName = "com.android.settings:id/title"
                ),
                FakeNode(
                    className = "android.widget.ListView",
                    children = listOf(
                        FakeNode(
                            text = "LockKeeper",
                            viewIdResourceName = "android:id/title",
                            children = listOf(
                                FakeNode(text = "Active", viewIdResourceName = "android:id/summary")
                            )
                        ),
                        FakeNode(
                            text = "Find My Device",
                            viewIdResourceName = "android:id/title",
                            children = listOf(
                                FakeNode(text = "Active", viewIdResourceName = "android:id/summary")
                            )
                        )
                    )
                )
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminSettings",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("General Device Admin listing must NEVER trigger tamper overlay", event)
    }

    @Test
    fun `another device admin deactivation screen does NOT trigger LockKeeper tamper`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "Find My Device", viewIdResourceName = "com.android.settings:id/admin_name"),
                FakeNode(text = "Deactivate this device admin app", viewIdResourceName = "com.android.settings:id/action_button")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("Deactivating another device admin must NOT trigger LockKeeper overlay", event)
    }

    @Test
    fun `LockKeeper deactivation screen specifically triggers DISABLE_DEVICE_ADMIN`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/admin_name"),
                FakeNode(text = "Deactivate this device admin app", viewIdResourceName = "com.android.settings:id/action_button")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull("LockKeeper deactivation screen must trigger tamper protection", event)
        assertEquals(TamperType.DISABLE_DEVICE_ADMIN, event?.type)
        assertEquals(TamperConfidence.HIGH, event?.confidence)
    }

    @Test
    fun `destructive action outside relevant subtree does NOT trigger tamper`() {
        // WhatsApp settings screen has 'Uninstall' but LockKeeper is NOT in this subtree
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "WhatsApp", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.settings:id/button1_negative")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("Uninstall action on WhatsApp must not trigger tamper", event)
    }

    // Helper to build linear deep node trees
    private fun buildDeepNode(targetDepth: Int, currentDepth: Int = 1, leaf: FakeNode): FakeNode {
        return if (currentDepth >= targetDepth) {
            leaf
        } else {
            FakeNode(
                className = "android.view.ViewGroup",
                children = listOf(buildDeepNode(targetDepth, currentDepth + 1, leaf))
            )
        }
    }

    @Test
    fun `deep node tree at depth 2 is detected`() {
        val leaf = FakeNode(
            className = "android.widget.LinearLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.settings:id/button1_negative")
            )
        )
        val root = buildDeepNode(2, leaf = leaf)

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull("Tree at depth 2 must be detected", event)
        assertEquals(TamperType.UNINSTALL, event?.type)
    }

    @Test
    fun `deep node tree at depth 6 is detected`() {
        val leaf = FakeNode(
            className = "android.widget.LinearLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Clear storage", viewIdResourceName = "com.android.settings:id/clear_data_button")
            )
        )
        val root = buildDeepNode(6, leaf = leaf)

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.StorageUseActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull("Tree at depth 6 must be detected", event)
        assertEquals(TamperType.CLEAR_DATA, event?.type)
    }

    @Test
    fun `deep node tree at depth 10 is detected`() {
        val leaf = FakeNode(
            className = "android.widget.LinearLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Force stop", viewIdResourceName = "com.android.settings:id/button2_negative")
            )
        )
        val root = buildDeepNode(10, leaf = leaf)

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull("Tree at depth 10 must be detected by BFS bounded collector", event)
        assertEquals(TamperType.FORCE_STOP, event?.type)
    }

    @Test
    fun `deep node tree at depth 12 is detected`() {
        val leaf = FakeNode(
            className = "android.widget.LinearLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.settings:id/button1_negative")
            )
        )
        val root = buildDeepNode(12, leaf = leaf)

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull("Tree at depth 12 within MAX_DEPTH bound must be detected", event)
        assertEquals(TamperType.UNINSTALL, event?.type)
    }

    @Test
    fun `missing resource IDs fallback to text matching in German`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper"),
                FakeNode(text = "Deinstallieren"),
                FakeNode(text = "Beenden erzwingen")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull("Missing resource IDs must fallback to multilingual action keywords", event)
        assertEquals(TamperType.UNINSTALL, event?.type)
    }

    @Test
    fun `dynamic content change with newly added destructive button is caught`() {
        // Initial node tree has no destructive buttons yet
        val initialRoot = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title")
            )
        )

        val initialEvent = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = initialRoot,
            isDeviceAdminActive = true
        )
        assertNull("Initial state without action button does not trigger", initialEvent)

        // Asynchronous content update injects the buttons (TYPE_WINDOW_CONTENT_CHANGED)
        val updatedRoot = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.settings:id/button1_negative")
            )
        )

        val updatedEvent = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = updatedRoot,
            isDeviceAdminActive = true
        )
        assertNotNull("Dynamic content change presenting destructive button must trigger", updatedEvent)
        assertEquals(TamperType.UNINSTALL, updatedEvent?.type)
    }
}

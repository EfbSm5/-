package com.example.agent.agent.planning

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAppLauncherInstrumentedTest {
    @Test
    fun allowlistedSettingsApp_passesPreflightAndLaunches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = AndroidAppLauncher(
            context = context,
            allowedPackages = setOf("com.android.settings"),
        )

        assertEquals(AppLaunchPreflight.Ready, launcher.preflight("com.android.settings"))
        assertEquals(AppLaunchResult.Launched, launcher.launch("com.android.settings"))
    }

    @Test
    fun packageOutsideAllowlist_isDenied() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = AndroidAppLauncher(
            context = context,
            allowedPackages = setOf("com.android.settings"),
        )

        assertEquals(
            AppLaunchPreflight.Denied("com.example.other"),
            launcher.preflight("com.example.other"),
        )
        assertEquals(
            AppLaunchResult.Denied("com.example.other"),
            launcher.launch("com.example.other"),
        )
    }

    @Test
    fun allowlistedMissingPackage_failsPreflightWithoutLaunching() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageName = "com.example.definitely.not.installed"
        val launcher = AndroidAppLauncher(
            context = context,
            allowedPackages = setOf(packageName),
        )

        assertEquals(
            AppLaunchPreflight.Failure("应用未安装：$packageName"),
            launcher.preflight(packageName),
        )
        assertEquals(
            AppLaunchResult.Failure("应用未安装：$packageName"),
            launcher.launch(packageName),
        )
    }
}

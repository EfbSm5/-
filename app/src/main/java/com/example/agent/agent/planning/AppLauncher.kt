package com.example.agent.agent.planning

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

sealed interface AppLaunchPreflight {
    data object Ready : AppLaunchPreflight

    data class Denied(val packageName: String) : AppLaunchPreflight

    data class Failure(val message: String) : AppLaunchPreflight
}

sealed interface AppLaunchResult {
    data object Launched : AppLaunchResult

    data class Denied(val packageName: String) : AppLaunchResult

    data class Failure(val message: String) : AppLaunchResult
}

interface AppLauncher {
    fun preflight(packageName: String): AppLaunchPreflight

    suspend fun launch(packageName: String): AppLaunchResult
}

class AndroidAppLauncher(
    context: Context,
    allowedPackages: Set<String>,
    private val packageManager: PackageManager = context.packageManager,
) : AppLauncher {
    private val appContext = context.applicationContext
    private val allowedPackageNames = allowedPackages.toSet()

    init {
        require(allowedPackageNames.all(PACKAGE_NAME_PATTERN::matches)) {
            "白名单中包含不合法的包名"
        }
    }

    override fun preflight(packageName: String): AppLaunchPreflight {
        if (packageName !in allowedPackageNames) {
            return AppLaunchPreflight.Denied(packageName)
        }

        val applicationInfo = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return AppLaunchPreflight.Failure("应用未安装：$packageName")
        }
        if (!applicationInfo.enabled) {
            return AppLaunchPreflight.Failure("应用已停用：$packageName")
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return AppLaunchPreflight.Failure("应用没有可启动入口：$packageName")
        if (launchIntent.component?.packageName != packageName) {
            return AppLaunchPreflight.Failure("启动目标与请求包名不一致：$packageName")
        }
        return AppLaunchPreflight.Ready
    }

    override suspend fun launch(packageName: String): AppLaunchResult {
        when (val preflight = preflight(packageName)) {
            AppLaunchPreflight.Ready -> Unit
            is AppLaunchPreflight.Denied -> {
                return AppLaunchResult.Denied(preflight.packageName)
            }

            is AppLaunchPreflight.Failure -> {
                return AppLaunchResult.Failure(preflight.message)
            }
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return AppLaunchResult.Failure("应用没有可启动入口：$packageName")
        if (launchIntent.component?.packageName != packageName) {
            return AppLaunchResult.Failure("启动目标与请求包名不一致：$packageName")
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(launchIntent)
            AppLaunchResult.Launched
        } catch (_: ActivityNotFoundException) {
            AppLaunchResult.Failure("找不到可启动的 Activity：$packageName")
        } catch (_: SecurityException) {
            AppLaunchResult.Failure("系统拒绝启动应用：$packageName")
        }
    }

    private companion object {
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

package com.example.agent.rootpilot.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.agent.rootpilot.model.RootPilotApp

fun interface AppCatalog {
    fun listApps(): List<RootPilotApp>
}

class AndroidAppCatalog(context: Context) : AppCatalog {
    private val ownPackage = context.packageName
    private val packageManager = context.packageManager

    override fun listApps(): List<RootPilotApp> = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        PackageManager.ResolveInfoFlags.of(0),
    ).mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled ||
            activity.packageName == ownPackage
        ) return@mapNotNull null
        RootPilotApp(
            packageName = activity.packageName,
            label = resolved.loadLabel(packageManager).toString(),
            activityName = activity.name,
        )
    }.sortedWith(compareBy({ it.packageName }, { it.activityName }))
        .distinctBy { it.packageName }
}

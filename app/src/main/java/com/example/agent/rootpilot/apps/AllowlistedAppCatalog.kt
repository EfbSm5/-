package com.example.agent.rootpilot.apps

import com.example.agent.rootpilot.model.RootPilotApp

class AllowlistedAppCatalog(
    private val rawCatalog: AppCatalog,
    private val store: AppLaunchAllowlistStore,
) : AppCatalog {
    override fun listApps(): List<RootPilotApp> {
        val allowed = store.read()
        return if (allowed.isEmpty()) emptyList() else rawCatalog.listApps().filter { it.packageName in allowed }
    }
}

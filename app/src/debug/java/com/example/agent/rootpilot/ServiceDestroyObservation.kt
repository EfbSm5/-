package com.example.agent.rootpilot

import java.lang.ref.WeakReference

/** Read-only lifecycle evidence; never owns a Service or changes its lifetime. */
internal object ServiceDestroyObservation {
    class Instance(service: RootPilotService) {
        val service = WeakReference(service)
        @Volatile var destroyed = false
            internal set
    }

    @Volatile var current: Instance? = null
        private set

    fun created(service: RootPilotService) { current = Instance(service) }
    fun destroyed(service: RootPilotService) {
        current?.takeIf { it.service.get() === service }?.destroyed = true
    }
}

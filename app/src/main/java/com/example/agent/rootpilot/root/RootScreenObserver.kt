package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.ScreenObserver
import com.example.agent.rootpilot.screen.DisplaySession
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RootScreenObserver internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
    private val maxOutputBytes: Int,
    private val clock: () -> Long,
    private val start: (String) -> Process,
    private val session: DisplaySession? = null,
) : ScreenObserver {
    constructor() : this(
        Dispatchers.IO, 3_000, 256 * 1024, { System.nanoTime() / 1_000_000 },
        { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() },
    )

    constructor(session: DisplaySession) : this(
        Dispatchers.IO, 3_000, 256 * 1024, { System.nanoTime() / 1_000_000 },
        { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() }, session,
    )

    init {
        require(timeoutMillis > 0)
        require(maxOutputBytes in 1..1024 * 1024)
    }

    private val lock = Any()
    private val operations = mutableSetOf<Job>()

    /** Cancels current observations only. The controller must still join its owning task. */
    fun cancel() {
        val jobs = synchronized(lock) { operations.toList() }
        jobs.forEach { it.cancel(CancellationException("Screen observation cancelled")) }
    }

    override suspend fun observe(): ScreenObservation = coroutineScope {
        val operation = currentCoroutineContext().job
        synchronized(lock) { operations.add(operation) }
        try {
            withContext(dispatcher) {
                withTimeoutOrNull(timeoutMillis) {
                    val activities = collect("exec dumpsys activity activities")
                    val windows = collect("exec dumpsys window windows")
                    val displays = collect("exec dumpsys window displays")
                    val ime = collect("exec dumpsys input_method --dump-priority CRITICAL")
                    currentCoroutineContext().ensureActive()
                    if (session == null) ScreenObservationParser.parse(activities, windows, ime, clock(), displays)
                    else ScreenObservationParser.parseVirtual(activities, windows, displays, clock(), session)
                } ?: ScreenObservationParser.parse(null, null, null, clock())
            }
        } finally {
            synchronized(lock) { operations.remove(operation) }
        }
    }

    private suspend fun collect(command: String): String? {
        currentCoroutineContext().ensureActive()
        var process: Process? = null
        try {
            process = start(command)
            // The finally block owns the process even if cancellation raced with start().
            currentCoroutineContext().ensureActive()
            process.outputStream.close()
            val input = process.inputStream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val available = input.available()
                if (available > 0) {
                    // Read only bytes already available: no blocking reader or reader thread.
                    val count = input.read(buffer, 0, minOf(available, buffer.size))
                    if (count < 0) return null
                    if (count > maxOutputBytes - output.size()) return null
                    output.write(buffer, 0, count)
                } else if (!process.isAlive) {
                    // Recheck after exit, since the final pipe write may race the first check.
                    if (input.available() > 0) continue
                    return if (process.exitValue() == 0) output.toString("UTF-8") else null
                } else {
                    delay(10)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Neither dumps nor platform exception messages cross this boundary.
            return null
        } finally {
            process?.let { child ->
                runCatching { child.destroyForcibly() }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
                runCatching { child.outputStream.close() }
            }
        }
    }
}

/** Strict, pure Kotlin parsers. Window titles are never treated as package names. */
internal object ScreenObservationParser {
    private const val PACKAGE = "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"
    private const val CLASS = "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"
    private val activity = Regex(
        "(?:mResumedActivity: |topResumedActivity=|Resumed: |ResumedActivity: )" +
            "(ActivityRecord\\{[0-9a-fA-F]{1,16} u[0-9]+ ($PACKAGE)/(\\.?$CLASS) t[0-9]+\\})",
    )
    private val focus = Regex("mCurrentFocus=Window\\{([0-9a-fA-F]{1,16}) u[0-9]+ [^\\r\\n]*\\}")
    private val window = Regex("Window #[0-9]+ Window\\{([0-9a-fA-F]{1,16}) u[0-9]+ [^\\r\\n]*\\}:")
    private val owner = Regex(
        "mOwnerUid=[0-9]+ showForAllUsers=(?:true|false) package=($PACKAGE) appop=[A-Za-z0-9_]+",
    )
    private val shown = Regex("mInputShown=(true|false)")
    private val activityDisplay = Regex("Display #([0-9]+) \\(activities from top to bottom\\):")
    private val windowDisplay = Regex("(?:Display: )?mDisplayId=([0-9]+)(?: .*)?")
    private val displayHeader = Regex("Display: mDisplayId=([0-9]+)(?: \\(organized\\))?")
    private val sleeping = Regex("mSleeping=true(?: mAllSleepTokens=\\[\\])?")

    /** Opt-in parser for an owned display. The default-screen parser stays fail-closed. */
    fun parseVirtual(
        activities: String?, windows: String?, displays: String?, observedAtMillis: Long, session: DisplaySession,
    ): ScreenObservation {
        fun unavailable() = ScreenObservation(null, null, null, null, null, observedAtMillis,
            session.displayId, session.sessionId)
        val id = session.displayId.toString()
        val tasks = displaySections(activities.orEmpty().lines(), activityDisplay, "Display #", activitySummary = true)
            ?.displays?.get(id) ?: return unavailable()
        val displayLines = displays.orEmpty().replace(Regex("[ \\t]+(?=Display:)"), "\n").lines()
        val display = displaySections(displayLines, displayHeader, "Display:")?.displays?.get(id)
            ?: return unavailable()
        val candidates = tasks.filter {
            it.startsWith("mResumedActivity") || it.startsWith("topResumedActivity") ||
                it.startsWith("Resumed:") || it.startsWith("ResumedActivity:")
        }.map { activity.matchEntire(it) }
        if (candidates.isEmpty() || candidates.any { it == null } ||
            candidates.map { it!!.groupValues[1] }.distinct().size != 1) return unavailable()
        val foreground = candidates.first()!!
        val pkg = foreground.groupValues[2]
        val component = foreground.groupValues[3].let { if (it.startsWith('.')) pkg + it else it }
        val token = display.filter { it.startsWith("mCurrentFocus") }.singleOrNull()
            ?.let(focus::matchEntire)?.groupValues?.get(1) ?: return unavailable()
        val rawWindows = windows.orEmpty().lines()
        val matching = rawWindows.indices.filter {
            window.matchEntire(rawWindows[it].trim())?.groupValues?.get(1) == token
        }.singleOrNull() ?: return unavailable()
        val block = windowBlock(rawWindows, matching)
        if (block.filter { it.startsWith("mDisplayId") }.singleOrNull()
                ?.let(windowDisplay::matchEntire)?.groupValues?.get(1) != id) return unavailable()
        val focusPackage = block.filter { it.startsWith("mOwnerUid") }.singleOrNull()
            ?.let(owner::matchEntire)?.groupValues?.get(1) ?: return unavailable()
        if (focusPackage != pkg) return unavailable()
        // Text input is unsupported here. Never infer the secondary IME state from the main screen.
        val imeWindows = display.filter { it.startsWith("mImeWindow=") }
        val keyboard = if (imeWindows.isNotEmpty() && imeWindows.all { it == "mImeWindow=null" }) false else null
        return ScreenObservation(pkg, component, focusPackage, token, keyboard, observedAtMillis,
            session.displayId, session.sessionId)
    }

    fun parse(
        activities: String?, windows: String?, ime: String?, observedAtMillis: Long,
        displays: String? = windows,
    ): ScreenObservation {
        val rawActivityLines = activities.orEmpty().lines()
        var activityDump = rawActivityLines.map(String::trim)
        val rawWindowLines = windows.orEmpty().lines()
        val lines = rawWindowLines.map(String::trim)
        // Some dumps omit the newline between mImeWindow and the next display header.
        var displayLines = displays.orEmpty().replace(Regex("[ \\t]+(?=Display:)"), "\n")
            .lineSequence().map(String::trim).toList()
        val activityStructure = displaySections(rawActivityLines, activityDisplay, "Display #", activitySummary = true)
        val activitySections = activityStructure?.displays
        val focusSections = displaySections(displayLines, displayHeader, "Display:")?.displays
        val hasSecondaryDisplay = (activitySections.orEmpty().keys + focusSections.orEmpty().keys)
            .any { it != "0" }
        val invalidSections = activitySections == null || focusSections == null
        val unsafeSecondaryDisplay = hasSecondaryDisplay && (
            activitySections?.keys != focusSections?.keys ||
                activitySections?.containsKey("0") != true ||
                activitySections.orEmpty().any { (id, body) -> id != "0" && body.any(String::isNotBlank) } ||
                focusSections.orEmpty().any { (id, body) ->
                    id != "0" && (
                        body.filter { it.startsWith("mSleeping") }.singleOrNull()?.let(sleeping::matches) != true ||
                            body.filter { it.startsWith("mCurrentFocus") } != listOf("mCurrentFocus=null") ||
                            body.filter { it.startsWith("mFocusedApp") } != listOf("mFocusedApp=null") ||
                            body.any {
                                it.contains("ActivityRecord{") ||
                                    it.startsWith("mDisplayId")
                            }
                        )
                }
            )
        // Sleeping, unfocused displays can retain system windows. Only IDs proven idle by
        // both dumps may own these windows; the actual focused window must still be on 0.
        val idleDisplayIds = if (!invalidSections && !unsafeSecondaryDisplay) {
            focusSections.orEmpty().keys - "0"
        } else emptySet()
        val unsafeWindowDisplay = lines.any {
            windowDisplay.matchEntire(it)?.groupValues?.get(1)?.let { id ->
                id != "0" && id !in idleDisplayIds
            } == true
        } || (hasSecondaryDisplay && lines.indices.filter { lines[it].startsWith("Window #") }.any { index ->
            val block = windowBlock(rawWindowLines, index)
            val id = block.filter { it.startsWith("mDisplayId") }.singleOrNull()
                ?.let(windowDisplay::matchEntire)?.groupValues?.get(1)
            window.matchEntire(lines[index]) == null || id == null ||
                (id != "0" && (id !in idleDisplayIds || block.any { it.contains("ActivityRecord{") }))
        })
        val unscopedDisplayLines = (if (focusSections.isNullOrEmpty()) displayLines else emptyList()) +
            activityStructure?.summary.orEmpty().flatMap {
                it.replace(Regex("[ \\t]+(?=Display:)"), "\n").lines().map(String::trim)
            }
        if (invalidSections || unsafeSecondaryDisplay || unsafeWindowDisplay || unscopedDisplayLines.any {
                if (it.startsWith("Display:") || it.startsWith("mDisplayId")) {
                    val id = (if (it.startsWith("Display:")) displayHeader else windowDisplay)
                        .matchEntire(it)?.groupValues?.get(1)
                    id == null || (id != "0" && id !in idleDisplayIds)
                } else false
            }) {
            return ScreenObservation(null, null, null, null, null, observedAtMillis)
        }
        if (!activitySections.isNullOrEmpty()) {
            activityDump = activitySections["0"].orEmpty() + activityStructure?.summary.orEmpty()
        }
        if (!focusSections.isNullOrEmpty()) displayLines = focusSections["0"].orEmpty()
        val activityLines = activityDump.filter {
            it.startsWith("mResumedActivity") || it.startsWith("topResumedActivity") ||
                it.startsWith("Resumed:") || it.startsWith("ResumedActivity:")
        }
        // Task.dump and RootWindowContainer.dumpActivities repeat the same ActivityRecord.
        // Compare the complete identity (including user/task/token), not just its component.
        val candidates = activityLines.map { activity.matchEntire(it) }
        val hasDefaultActivity = activitySections.isNullOrEmpty() ||
            activitySections["0"].orEmpty().any { activity.matches(it) }
        val foreground = if (hasDefaultActivity && candidates.all { it != null } &&
            candidates.map { it?.groupValues?.get(1) }.distinct().size == 1
        ) candidates.first() else null
        val foregroundPackage = foreground?.groupValues?.get(2)
        val activityName = foreground?.groupValues?.get(3)?.let {
            if (it.startsWith('.')) foregroundPackage + it else it
        }
        val focusLines = displayLines.filter { it.startsWith("mCurrentFocus") }
        val token = focusLines.singleOrNull()?.let(focus::matchEntire)?.groupValues?.get(1)
        val matchingWindows = lines.indices.filter { index ->
            token != null && window.matchEntire(lines[index])?.groupValues?.get(1) == token
        }
        val focusedPackage = matchingWindows.singleOrNull()?.let { index ->
            val block = windowBlock(rawWindowLines, index)
            // WindowState.dump emits mDisplayId. Missing or non-default display cannot bind
            // the default-display screencap/input path to this window.
            val display = block.filter { it.startsWith("mDisplayId") }.singleOrNull()
                ?.let(windowDisplay::matchEntire)?.groupValues?.get(1)
            if (display != "0") return@let null
            block.filter { it.startsWith("mOwnerUid") }.singleOrNull()
                ?.let(owner::matchEntire)?.groupValues?.get(1)
        }
        val keyboard = parseIme(ime)
        return ScreenObservation(foregroundPackage, activityName, focusedPackage, token, keyboard, observedAtMillis)
    }

    private fun windowBlock(lines: List<String>, headerIndex: Int): List<String> {
        val header = lines[headerIndex]
        val indent = header.length - header.trimStart().length
        // Global dump sections can follow the final window without another Window header.
        return lines.drop(headerIndex + 1).takeWhile {
            it.isBlank() || it.length - it.trimStart().length > indent
        }.map(String::trim)
    }

    private data class DisplaySections(val displays: Map<String, List<String>>, val summary: List<String>)

    private fun displaySections(
        lines: List<String>, header: Regex, prefix: String, activitySummary: Boolean = false,
    ): DisplaySections? {
        val sections = linkedMapOf<String, MutableList<String>>()
        val preamble = mutableListOf<String>()
        val summary = mutableListOf<String>()
        var current: MutableList<String>? = null
        var displayIndent: Int? = null
        var inSummary = false
        for (raw in lines) {
            val line = raw.trim()
            val indent = raw.length - raw.trimStart().length
            if (line.startsWith(prefix)) {
                if (inSummary || (activitySummary && displayIndent != null && indent != displayIndent)) return null
                val id = header.matchEntire(line)?.groupValues?.get(1) ?: return null
                if (sections.containsKey(id)) return null
                displayIndent = indent
                current = mutableListOf<String>().also { sections[id] = it }
            } else {
                // These global activity sections follow the final display, even when it is
                // empty. Only known headers at their observed indentation end that display.
                if (activitySummary && displayIndent != null && (
                        (line.startsWith("ResumedActivity: ") && activity.matches(line) && indent == displayIndent + 2) ||
                            ((line == "Activities waiting to stop:" || line == "Activities waiting to finish:") &&
                                indent == displayIndent + 2) ||
                            (line.startsWith("ActivityTaskSupervisor ") && indent == displayIndent)
                        )) {
                    inSummary = true
                    current = null
                }
                when {
                    inSummary -> summary.add(line)
                    current != null -> current.add(line)
                    else -> preamble.add(line)
                }
            }
        }
        // Do not silently drop identities outside a recognized display section.
        if (sections.isNotEmpty() && preamble.any {
                it.contains("ActivityRecord{") || it.startsWith("mCurrentFocus") || it.startsWith("mFocusedApp")
            }) return null
        return DisplaySections(sections, summary)
    }

    private fun parseIme(dump: String?): Boolean? {
        val lines = dump.orEmpty().lines()
        // AOSP 15: IMMS.dumpAsStringNoCheck -> ImeVisibilityStateComputer.dump (two spaces).
        // AOSP 16: IMMS.dumpAsStringNoCheckForUser -> visibility subsection (six spaces).
        val headers = lines.indices.filter {
            lines[it] == "Current Input Method Manager state:" ||
                lines[it] == "Input Method Manager Service state:"
        }
        val header = headers.singleOrNull() ?: return null
        val section = lines.drop(header + 1).takeWhile { it.isBlank() || it.startsWith(' ') }
        val stateLines = if (lines[header] == "Current Input Method Manager state:") {
            section.filter { it.startsWith("  mInputShown") }
        } else {
            val users = section.filter { it.startsWith("  UserId=") }
            if (users.size != 1 || !Regex("  UserId=[0-9]+").matches(users.single())) return null
            val visibility = section.indices.filter { section[it] == "    mVisibilityStateComputer:" }
                .singleOrNull() ?: return null
            section.drop(visibility + 1).takeWhile { it.startsWith("      ") }
                .filter { it.startsWith("      mInputShown") }
        }
        return stateLines.singleOrNull()?.trim()?.let(shown::matchEntire)
            ?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }
}

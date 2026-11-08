package com.threedstwo.tv.core.settings

import com.threedstwo.tv.core.input.ButtonMapping
import com.threedstwo.tv.core.input.MappingPreset
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.layout.LayoutSettings
import com.threedstwo.tv.core.ntr.NtrConstants
import com.threedstwo.tv.core.ntr.RemotePlayRequest
import com.threedstwo.tv.core.session.ConnectMode

/** Abstraction over SharedPreferences so persistence is unit-testable on the JVM. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putAll(values: Map<String, String>)
}

class MemoryStore(private val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun getString(key: String) = map[key]
    override fun putAll(values: Map<String, String>) { map.putAll(values) }
}

enum class ScalingFilter(val label: String) { NEAREST("Píxel nítido (nearest)"), BILINEAR("Suave (bilineal)") }

enum class CursorStick(val label: String) { RIGHT_STICK("Stick derecho"), LEFT_STICK("Stick izquierdo"), DPAD("Cruceta") }

data class AppSettings(
    val consoleIp: String = "",
    val connectMode: ConnectMode = ConnectMode.ACTIVE,
    val jpegQuality: Int = 80,
    val qosMbps: Int = 16,
    val topPriority: Boolean = true,
    val priorityFactor: Int = 1,
    val viewerPort: Int = NtrConstants.DEFAULT_VIEWER_PORT,
    val layout: LayoutSettings = LayoutSettings(),
    val filter: ScalingFilter = ScalingFilter.NEAREST,
    val showHud: Boolean = false,
    val audioEnabled: Boolean = false,
    val audioVolume: Int = 100,
    val inputRedirection: Boolean = true,
    val mappingPreset: MappingPreset = MappingPreset.POSITIONAL,
    val mapping: ButtonMapping = ButtonMapping.preset(MappingPreset.POSITIONAL),
    val circleDeadzone: Float = 0.15f,
    val circleSensitivity: Float = 1.0f,
    val cStickDeadzone: Float = 0.20f,
    val cStickSensitivity: Float = 1.0f,
    val allowHome: Boolean = false,
    val touchCursorEnabled: Boolean = true,
    val cursorStick: CursorStick = CursorStick.RIGHT_STICK,
    /** Native bottom-screen pixels per second at full deflection. */
    val cursorSpeed: Int = 300,
    /** While the touch cursor is active this control "touches" the screen (instead of its normal action). */
    val touchButton: PadControl = PadControl.R1,
    val tvRemoteAsPad: Boolean = false,
    /** Descriptor of the preferred controller (InputDevice.getDescriptor), empty = first available. */
    val preferredController: String = "",
    val maxReconnectAttempts: Int = 8,
) {
    fun remotePlayRequest() = RemotePlayRequest(
        topScreenPriority = topPriority,
        priorityFactor = priorityFactor,
        jpegQuality = jpegQuality,
        qosMbps = qosMbps,
        viewerPort = viewerPort,
        audio = audioEnabled,
    ).clamped()

    fun save(store: KeyValueStore) {
        store.putAll(
            mapOf(
                "consoleIp" to consoleIp,
                "connectMode" to connectMode.name,
                "jpegQuality" to jpegQuality.toString(),
                "qosMbps" to qosMbps.toString(),
                "topPriority" to topPriority.toString(),
                "priorityFactor" to priorityFactor.toString(),
                "viewerPort" to viewerPort.toString(),
                "viewMode" to layout.viewMode.name,
                "arrangement" to layout.arrangement.name,
                "bottomFirst" to layout.bottomFirst.toString(),
                "topRotation" to layout.topRotation.name,
                "bottomRotation" to layout.bottomRotation.name,
                "gap" to layout.gap.toString(),
                "topScale" to layout.topScale.toString(),
                "bottomScale" to layout.bottomScale.toString(),
                "fill" to layout.fill.toString(),
                "integerScale" to layout.integerScale.toString(),
                "filter" to filter.name,
                "showHud" to showHud.toString(),
                "audioEnabled" to audioEnabled.toString(),
                "audioVolume" to audioVolume.toString(),
                "inputRedirection" to inputRedirection.toString(),
                "mappingPreset" to mappingPreset.name,
                "mapping" to mapping.serialize(),
                "circleDeadzone" to circleDeadzone.toString(),
                "circleSensitivity" to circleSensitivity.toString(),
                "cStickDeadzone" to cStickDeadzone.toString(),
                "cStickSensitivity" to cStickSensitivity.toString(),
                "allowHome" to allowHome.toString(),
                "touchCursorEnabled" to touchCursorEnabled.toString(),
                "cursorStick" to cursorStick.name,
                "cursorSpeed" to cursorSpeed.toString(),
                "touchButton" to touchButton.name,
                "tvRemoteAsPad" to tvRemoteAsPad.toString(),
                "preferredController" to preferredController,
                "maxReconnectAttempts" to maxReconnectAttempts.toString(),
            ),
        )
    }

    companion object {
        private inline fun <reified E : Enum<E>> KeyValueStore.enum(key: String, def: E): E =
            getString(key)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

        private fun KeyValueStore.int(key: String, def: Int, range: IntRange) =
            getString(key)?.toIntOrNull()?.coerceIn(range) ?: def

        private fun KeyValueStore.float(key: String, def: Float, min: Float, max: Float) =
            getString(key)?.toFloatOrNull()?.takeIf { !it.isNaN() }?.coerceIn(min, max) ?: def

        private fun KeyValueStore.bool(key: String, def: Boolean) = getString(key)?.toBooleanStrictOrNull() ?: def

        fun load(store: KeyValueStore): AppSettings {
            val d = AppSettings()
            val dl = d.layout
            val preset = store.enum("mappingPreset", d.mappingPreset)
            return AppSettings(
                consoleIp = store.getString("consoleIp") ?: d.consoleIp,
                connectMode = store.enum("connectMode", d.connectMode),
                jpegQuality = store.int("jpegQuality", d.jpegQuality, NtrConstants.JPEG_QUALITY_MIN..NtrConstants.JPEG_QUALITY_MAX),
                qosMbps = store.int("qosMbps", d.qosMbps, NtrConstants.QOS_MBPS_MIN..NtrConstants.QOS_MBPS_MAX),
                topPriority = store.bool("topPriority", d.topPriority),
                priorityFactor = store.int("priorityFactor", d.priorityFactor, 0..8),
                viewerPort = store.int("viewerPort", d.viewerPort, 1024..65535),
                layout = LayoutSettings(
                    viewMode = store.enum("viewMode", dl.viewMode),
                    arrangement = store.enum("arrangement", dl.arrangement),
                    bottomFirst = store.bool("bottomFirst", dl.bottomFirst),
                    topRotation = store.enum("topRotation", dl.topRotation),
                    bottomRotation = store.enum("bottomRotation", dl.bottomRotation),
                    gap = store.int("gap", dl.gap, 0..120),
                    topScale = store.float("topScale", dl.topScale, 0.5f, 2f),
                    bottomScale = store.float("bottomScale", dl.bottomScale, 0.5f, 2f),
                    fill = store.float("fill", dl.fill, 0.5f, 1f),
                    integerScale = store.bool("integerScale", dl.integerScale),
                ),
                filter = store.enum("filter", d.filter),
                showHud = store.bool("showHud", d.showHud),
                audioEnabled = store.bool("audioEnabled", d.audioEnabled),
                audioVolume = store.int("audioVolume", d.audioVolume, 0..100),
                inputRedirection = store.bool("inputRedirection", d.inputRedirection),
                mappingPreset = preset,
                mapping = ButtonMapping.deserialize(store.getString("mapping"), ButtonMapping.preset(preset)),
                circleDeadzone = store.float("circleDeadzone", d.circleDeadzone, 0f, 0.6f),
                circleSensitivity = store.float("circleSensitivity", d.circleSensitivity, 0.5f, 2f),
                cStickDeadzone = store.float("cStickDeadzone", d.cStickDeadzone, 0f, 0.6f),
                cStickSensitivity = store.float("cStickSensitivity", d.cStickSensitivity, 0.5f, 2f),
                allowHome = store.bool("allowHome", d.allowHome),
                touchCursorEnabled = store.bool("touchCursorEnabled", d.touchCursorEnabled),
                cursorStick = store.enum("cursorStick", d.cursorStick),
                cursorSpeed = store.int("cursorSpeed", d.cursorSpeed, 50..1000),
                touchButton = store.enum("touchButton", d.touchButton),
                tvRemoteAsPad = store.bool("tvRemoteAsPad", d.tvRemoteAsPad),
                preferredController = store.getString("preferredController") ?: d.preferredController,
                maxReconnectAttempts = store.int("maxReconnectAttempts", d.maxReconnectAttempts, 1..50),
            )
        }

    }
}

package com.threedstwo.tv.core.input

/**
 * Physical controls of an Android gamepad, after Android's own key-layout normalisation
 * (Xbox, PlayStation and most HID gamepads report the same KEYCODE_BUTTON_* codes; names here
 * follow the position: SOUTH = Xbox A / PlayStation Cross).
 */
enum class PadControl(val label: String) {
    SOUTH("Botón inferior (Xbox A / PS ✕)"),
    EAST("Botón derecho (Xbox B / PS ○)"),
    WEST("Botón izquierdo (Xbox X / PS □)"),
    NORTH("Botón superior (Xbox Y / PS △)"),
    L1("LB / L1"), R1("RB / R1"),
    L2("LT / L2"), R2("RT / R2"),
    L3("Clic stick izquierdo (L3)"), R3("Clic stick derecho (R3)"),
    SELECT("Select / View / Share"), START("Start / Menu / Options"),
    GUIDE("Guía / Home / PS"),
    DPAD_UP("Cruceta arriba"), DPAD_DOWN("Cruceta abajo"), DPAD_LEFT("Cruceta izquierda"), DPAD_RIGHT("Cruceta derecha"),
}

/** What a [PadControl] does on the 3DS side (or in the app). */
enum class Action(val label: String) {
    NONE("Sin asignar"),
    A("A"), B("B"), X("X"), Y("Y"),
    L("L"), R("R"), ZL("ZL"), ZR("ZR"),
    START("START"), SELECT("SELECT"),
    DPAD_UP("Cruceta ↑"), DPAD_DOWN("Cruceta ↓"), DPAD_LEFT("Cruceta ←"), DPAD_RIGHT("Cruceta →"),
    HOME("HOME (3DS)"),
    TOUCH("Tocar (cursor táctil)"),
    TOGGLE_TOUCH_CURSOR("Activar/desactivar cursor táctil"),
    OPEN_MENU("Abrir menú de 3DS2TV"),
    ;

    val button: ThreeDsButton?
        get() = when (this) {
            A -> ThreeDsButton.A; B -> ThreeDsButton.B; X -> ThreeDsButton.X; Y -> ThreeDsButton.Y
            L -> ThreeDsButton.L; R -> ThreeDsButton.R
            START -> ThreeDsButton.START; SELECT -> ThreeDsButton.SELECT
            DPAD_UP -> ThreeDsButton.DPAD_UP; DPAD_DOWN -> ThreeDsButton.DPAD_DOWN
            DPAD_LEFT -> ThreeDsButton.DPAD_LEFT; DPAD_RIGHT -> ThreeDsButton.DPAD_RIGHT
            else -> null
        }
}

enum class MappingPreset(val label: String) {
    /** Same physical position as on the 3DS (A right, B bottom). Default, like ntrviewer-hr "Swap A/B X/Y". */
    POSITIONAL("Por posición (como en la 3DS)"),
    /** Same letter as printed on an Xbox-style pad (A bottom). */
    XBOX_LABELS("Por letra (Xbox: A abajo)"),
    /** PlayStation: Cross = B (bottom), Circle = A (right): same as positional. */
    PLAYSTATION("PlayStation (✕ = B, ○ = A)"),
    GENERIC("Genérico Android (por posición)"),
}

/** Immutable control → action mapping. */
data class ButtonMapping(val actions: Map<PadControl, Action>) {
    fun actionFor(control: PadControl): Action = actions[control] ?: Action.NONE

    fun with(control: PadControl, action: Action) = ButtonMapping(actions + (control to action))

    /** Swaps A<->B and X<->Y in the current mapping. */
    fun swapFaceButtons(): ButtonMapping = ButtonMapping(actions.mapValues { (_, a) ->
        when (a) {
            Action.A -> Action.B; Action.B -> Action.A
            Action.X -> Action.Y; Action.Y -> Action.X
            else -> a
        }
    })

    /** Serialized as "CONTROL=ACTION;..." for SharedPreferences. Unknown names are ignored on load. */
    fun serialize(): String = PadControl.entries.joinToString(";") { "${it.name}=${actionFor(it).name}" }

    companion object {
        fun preset(p: MappingPreset): ButtonMapping {
            val common = mapOf(
                PadControl.L1 to Action.L, PadControl.R1 to Action.R,
                PadControl.L2 to Action.ZL, PadControl.R2 to Action.ZR,
                PadControl.SELECT to Action.SELECT, PadControl.START to Action.START,
                PadControl.DPAD_UP to Action.DPAD_UP, PadControl.DPAD_DOWN to Action.DPAD_DOWN,
                PadControl.DPAD_LEFT to Action.DPAD_LEFT, PadControl.DPAD_RIGHT to Action.DPAD_RIGHT,
                PadControl.L3 to Action.OPEN_MENU,
                PadControl.R3 to Action.TOGGLE_TOUCH_CURSOR,
                PadControl.GUIDE to Action.NONE,
            )
            val positional = mapOf(
                PadControl.SOUTH to Action.B, PadControl.EAST to Action.A,
                PadControl.WEST to Action.Y, PadControl.NORTH to Action.X,
            )
            val labels = mapOf(
                PadControl.SOUTH to Action.A, PadControl.EAST to Action.B,
                PadControl.WEST to Action.X, PadControl.NORTH to Action.Y,
            )
            return ButtonMapping(common + if (p == MappingPreset.XBOX_LABELS) labels else positional)
        }

        fun deserialize(text: String?, fallback: ButtonMapping = preset(MappingPreset.POSITIONAL)): ButtonMapping {
            if (text.isNullOrBlank()) return fallback
            val map = fallback.actions.toMutableMap()
            for (part in text.split(';')) {
                val kv = part.split('=')
                if (kv.size != 2) continue
                val c = PadControl.entries.firstOrNull { it.name == kv[0] } ?: continue
                val a = Action.entries.firstOrNull { it.name == kv[1] } ?: continue
                map[c] = a
            }
            return ButtonMapping(map)
        }
    }
}

/**
 * Raw, device-independent snapshot of a gamepad (filled by the Android layer from KeyEvent/MotionEvent).
 * Axes use Android convention: x right +, y down +, range [-1, 1]; triggers [0, 1].
 */
data class PadSnapshot(
    val held: Set<PadControl> = emptySet(),
    val leftX: Float = 0f, val leftY: Float = 0f,
    val rightX: Float = 0f, val rightY: Float = 0f,
    val leftTrigger: Float = 0f, val rightTrigger: Float = 0f,
    val hatX: Float = 0f, val hatY: Float = 0f,
) {
    /** Held controls including analog triggers and HAT d-pad converted to digital. */
    fun effectiveHeld(triggerThreshold: Float = 0.35f): Set<PadControl> {
        val s = held.toMutableSet()
        if (leftTrigger >= triggerThreshold) s += PadControl.L2
        if (rightTrigger >= triggerThreshold) s += PadControl.R2
        if (hatX <= -0.5f) s += PadControl.DPAD_LEFT
        if (hatX >= 0.5f) s += PadControl.DPAD_RIGHT
        if (hatY <= -0.5f) s += PadControl.DPAD_UP
        if (hatY >= 0.5f) s += PadControl.DPAD_DOWN
        return s
    }
}

data class StickSettings(
    val deadzone: Float = 0.15f,
    val sensitivity: Float = 1.0f,
)

/**
 * Converts a [PadSnapshot] into an [InputFrame] according to the mapping.
 * App-level actions (menu, toggle cursor) are reported separately via [AppActions].
 */
object InputMapper {
    data class AppActions(val openMenu: Boolean, val toggleTouchCursor: Boolean, val touchPressed: Boolean)

    data class Result(val frame: InputFrame, val app: AppActions)

    fun map(
        pad: PadSnapshot,
        mapping: ButtonMapping,
        circle: StickSettings,
        cStick: StickSettings,
        allowHome: Boolean,
        /** When true the right stick drives the touch cursor, so the C-stick stays neutral. */
        rightStickUsedByCursor: Boolean,
        touch: InputFrame.TouchPoint?,
    ): Result {
        val held = pad.effectiveHeld()
        val pressed = mutableSetOf<ThreeDsButton>()
        var zl = false; var zr = false; var home = false
        var menu = false; var toggle = false; var touchPressed = false
        for (c in held) {
            when (val a = mapping.actionFor(c)) {
                Action.ZL -> zl = true
                Action.ZR -> zr = true
                Action.HOME -> if (allowHome) home = true
                Action.OPEN_MENU -> menu = true
                Action.TOGGLE_TOUCH_CURSOR -> toggle = true
                Action.TOUCH -> touchPressed = true
                Action.NONE -> {}
                else -> a.button?.let { pressed += it }
            }
        }
        val (lx, ly) = StickMath.shape(pad.leftX, pad.leftY, circle.deadzone, circle.sensitivity)
        val (cx, cy) = StickMath.circlePad(lx, ly)
        val (csx, csy) = if (rightStickUsedByCursor) {
            InputFrame.CSTICK_CENTER to InputFrame.CSTICK_CENTER
        } else {
            val (rx, ry) = StickMath.shape(pad.rightX, pad.rightY, cStick.deadzone, cStick.sensitivity)
            StickMath.cStick(rx, ry)
        }
        return Result(
            InputFrame(
                pressed = pressed,
                touch = if (touchPressed) touch else null,
                circleX = cx, circleY = cy,
                cStickX = csx, cStickY = csy,
                zl = zl, zr = zr, home = home,
            ),
            AppActions(menu, toggle, touchPressed),
        )
    }
}

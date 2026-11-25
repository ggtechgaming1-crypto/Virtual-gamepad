package com.example.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

enum class HapticProfile(
    val baseDurationMs: Long,
    val intensityMultiplier: Float,
    val primitiveType: Int
) {
    BUTTON_PRESS(
        baseDurationMs = 18L,
        intensityMultiplier = 1.0f,
        primitiveType = VibrationEffect.Composition.PRIMITIVE_CLICK
    ),
    TRIGGER_PULL(
        baseDurationMs = 24L,
        intensityMultiplier = 1.0f,
        primitiveType = VibrationEffect.Composition.PRIMITIVE_THUD
    ),
    DPAD_CLICK(
        baseDurationMs = 14L,
        intensityMultiplier = 0.85f,
        primitiveType = VibrationEffect.Composition.PRIMITIVE_TICK
    ),
    STICK_ENGAGE(
        baseDurationMs = 12L,
        intensityMultiplier = 0.7f,
        primitiveType = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    )
}

/**
 * Low-latency tactile feedback engine backed by Android's VibratorManager (API 31+)
 * with seamless fallback to Vibrator on API 26-30. Supports continuous intensity scaling
 * across both hardware composition primitives and amplitude-controlled one-shot effects.
 */
class HapticFeedbackEngine(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)
    private val hasHardwareVibrator: Boolean = vibrator?.hasVibrator() == true
    private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true

    private val touchAttributes: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
        } else {
            null
        }

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        }

    fun trigger(profile: HapticProfile = HapticProfile.BUTTON_PRESS) {
        if (!isEnabled) return
        dispatchVibration(profile, intensity)
    }

    fun triggerPreview(previewIntensity: Float) {
        dispatchVibration(
            HapticProfile.BUTTON_PRESS,
            previewIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        )
    }

    private fun dispatchVibration(profile: HapticProfile, rawIntensity: Float) {
        val vib = vibrator ?: return
        if (!hasHardwareVibrator) return

        val scaledIntensity = (rawIntensity * profile.intensityMultiplier).coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        val effect = buildEffect(vib, profile, scaledIntensity)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && touchAttributes != null) {
                vib.vibrate(effect, touchAttributes)
            } else {
                vib.vibrate(effect)
            }
        }
    }

    private fun buildEffect(
        vib: Vibrator,
        profile: HapticProfile,
        scaledIntensity: Float
    ): VibrationEffect {
        // Prefer crisp linear-actuator composition primitives on Android 11+ when supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = resolveSupportedPrimitive(vib, profile.primitiveType)
            if (primitive != null) {
                return VibrationEffect.startComposition()
                    .addPrimitive(primitive, scaledIntensity)
                    .compose()
            }
        }

        // Fall back to amplitude-scaled one-shot vibration (or duration-scaled on fixed motors)
        return if (hasAmplitudeControl) {
            val amplitude = (MIN_AMPLITUDE + (MAX_AMPLITUDE - MIN_AMPLITUDE) * scaledIntensity)
                .roundToInt()
                .coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
            VibrationEffect.createOneShot(profile.baseDurationMs, amplitude)
        } else {
            val scaledDuration = (profile.baseDurationMs * (0.55f + 0.75f * scaledIntensity))
                .toLong()
                .coerceIn(8L, 36L)
            VibrationEffect.createOneShot(scaledDuration, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private fun resolveSupportedPrimitive(vib: Vibrator, preferred: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
                preferred == VibrationEffect.Composition.PRIMITIVE_CLICK ||
                preferred == VibrationEffect.Composition.PRIMITIVE_TICK
            ) {
                add(preferred)
            }
            add(VibrationEffect.Composition.PRIMITIVE_CLICK)
            add(VibrationEffect.Composition.PRIMITIVE_TICK)
        }
        return candidates.firstOrNull { candidate ->
            runCatching { vib.areAllPrimitivesSupported(candidate) }.getOrDefault(false)
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    companion object {
        const val MIN_INTENSITY = 0.10f
        const val MAX_INTENSITY = 1.00f
        private const val MIN_AMPLITUDE = 28
        private const val MAX_AMPLITUDE = 255
    }
}

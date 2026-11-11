package moe.ouom.neriplayer.data.model.playback.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsDspResolutionTest {
    private val speaker = AudioEffectsRuntimeContext(route = AudioOutputRoute.SPEAKER)
    private val headphones = AudioEffectsRuntimeContext(route = AudioOutputRoute.WIRED)

    @Test
    fun `param indices mirror the native layout`() {
        assertEquals(111, NeriDspParams.COUNT)
        assertEquals(19, NeriDspParams.PARAMETRIC_EQ_ENABLED)
        assertEquals(70, NeriDspParams.BASS_GAIN_DB)
        assertEquals(81, NeriDspParams.STEREO_WIDTH)
        assertEquals(91, NeriDspParams.COMPRESSOR_ENABLED)
        assertEquals(98, NeriDspParams.SPEAKER_ENABLED)
        assertEquals(103, NeriDspParams.SPEAKER_STEREO_EXPAND)
        assertEquals(104, NeriDspParams.DELAY_ENABLED)
        assertEquals(108, NeriDspParams.LOUDNESS_NORMALIZATION_ENABLED)
        assertEquals(NeriDspParams.COUNT - 1, NeriDspParams.LOUDNESS_MAX_GAIN_DB)
    }

    @Test
    fun `disabled or neutral profiles bypass the engine`() {
        val disabled = AudioEffectsSettings().resolveDsp(headphones)
        assertFalse(disabled.active)
        assertEquals(AudioEffectsInactiveReason.DISABLED, disabled.inactiveReason)
        assertEquals(0f, disabled.params[NeriDspParams.MASTER_ENABLED])

        val flat = AudioEffectsSettings(main = AudioEffectsProfile(enabled = true)).resolveDsp(headphones)
        assertFalse(flat.active)
        assertEquals(AudioEffectsInactiveReason.NEUTRAL, flat.inactiveReason)
    }

    @Test
    fun `a tuned limiter alone keeps the engine active`() {
        val tuned = AudioEffectsSettings(
            main = AudioEffectsProfile(enabled = true, sound = AudioEffectsSound(limiterCeilingDb = -6f))
        ).resolveDsp(headphones)
        assertTrue(tuned.active)
        assertEquals(1f, tuned.params[NeriDspParams.LIMITER_ENABLED])
        assertEquals(-6f, tuned.params[NeriDspParams.LIMITER_CEILING_DB])

        assertFalse(AudioEffectsSound(limiterReleaseMs = 300f).isNeutral())
        assertTrue(AudioEffectsSound(limiterEnabled = false, limiterCeilingDb = -6f).isNeutral())
        assertTrue(AudioEffectsSound(dynamicsEnabled = false, limiterCeilingDb = -6f).isNeutral())
    }

    @Test
    fun `active preset writes equalizer gains and applies auto headroom`() {
        val rock = requireNotNull(findAudioEffectsBuiltInPreset("rock"))
        val settings = AudioEffectsSettings(main = AudioEffectsProfile().applyBuiltInPreset(rock))
        val resolution = settings.resolveDsp(headphones)
        assertTrue(resolution.active)
        assertEquals(1f, resolution.params[NeriDspParams.MASTER_ENABLED])
        assertEquals(1f, resolution.params[NeriDspParams.GRAPHIC_EQ_ENABLED])
        assertEquals(3f, resolution.params[NeriDspParams.GRAPHIC_EQ_GAIN_0])
        assertTrue(resolution.params[NeriDspParams.PREAMP_DB] < 0f)

        val noHeadroom = settings.copy(autoHeadroom = false).resolveDsp(headphones)
        assertEquals(0f, noHeadroom.params[NeriDspParams.PREAMP_DB])
    }

    @Test
    fun `speaker optimizer only runs on the built in speaker`() {
        val settings = AudioEffectsSettings(
            main = AudioEffectsProfile(enabled = true),
            speaker = SpeakerOptimizerSettings(enabled = true)
        )
        val onSpeaker = settings.resolveDsp(speaker)
        assertTrue(onSpeaker.active)
        assertEquals(1f, onSpeaker.params[NeriDspParams.SPEAKER_ENABLED])
        assertEquals(SpeakerSize.PHONE.protectionHz, onSpeaker.params[NeriDspParams.SPEAKER_HIGH_PASS_HZ])
        assertEquals(0f, onSpeaker.params[NeriDspParams.GRAPHIC_EQ_GAIN_0])

        val onHeadphones = settings.resolveDsp(headphones)
        assertFalse(onHeadphones.active)
        assertEquals(AudioEffectsInactiveReason.NEUTRAL, onHeadphones.inactiveReason)
    }

    @Test
    fun `master switch off bypasses the speaker optimizer and every tuned section`() {
        val rock = requireNotNull(findAudioEffectsBuiltInPreset("rock"))
        val settings = AudioEffectsSettings(
            main = AudioEffectsProfile().applyBuiltInPreset(rock).copy(enabled = false),
            speaker = SpeakerOptimizerSettings(enabled = true)
        )

        val resolution = settings.resolveDsp(speaker)

        assertFalse(resolution.active)
        assertEquals(AudioEffectsInactiveReason.DISABLED, resolution.inactiveReason)
        assertEquals(0f, resolution.params[NeriDspParams.MASTER_ENABLED])
        assertEquals(0f, resolution.params[NeriDspParams.SPEAKER_ENABLED])
    }

    @Test
    fun `speaker route profile owns the master switch when outputs are separated`() {
        val settings = AudioEffectsSettings(
            perOutputEnabled = true,
            main = AudioEffectsProfile(enabled = true),
            speaker = SpeakerOptimizerSettings(enabled = true)
        ).updateProfileFor(AudioOutputRoute.SPEAKER) { it.copy(enabled = false) }

        assertEquals(AudioEffectsInactiveReason.DISABLED, settings.resolveDsp(speaker).inactiveReason)
        assertTrue(
            settings.updateProfileFor(AudioOutputRoute.SPEAKER) { it.copy(enabled = true) }
                .resolveDsp(speaker).active
        )
    }

    @Test
    fun `USB exclusive keeps bit perfect output unless explicitly allowed`() {
        val vocal = requireNotNull(findAudioEffectsBuiltInPreset("vocal_boost"))
        val settings = AudioEffectsSettings(main = AudioEffectsProfile().applyBuiltInPreset(vocal))
        val usb = AudioEffectsRuntimeContext(AudioOutputRoute.USB, usbExclusiveNative = true)
        assertEquals(AudioEffectsInactiveReason.USB_EXCLUSIVE, settings.resolveDsp(usb).inactiveReason)

        val allowed = settings.copy(applyInUsbExclusive = true)
        assertTrue(allowed.resolveDsp(usb).active)
        assertEquals(
            AudioEffectsInactiveReason.USB_EXCLUSIVE,
            allowed.resolveDsp(usb.copy(usbBitPerfect = true)).inactiveReason
        )
    }

    @Test
    fun `power mode selects native quality and dither`() {
        val pop = requireNotNull(findAudioEffectsBuiltInPreset("pop"))
        val base = AudioEffectsSettings(main = AudioEffectsProfile().applyBuiltInPreset(pop))
        val eco = base.copy(powerMode = AudioEffectsPowerMode.ECO.storageValue).resolveDsp(headphones)
        assertEquals(0f, eco.params[NeriDspParams.QUALITY_MODE])
        assertEquals(0f, eco.params[NeriDspParams.DITHER_ENABLED])
        val high = base.copy(powerMode = AudioEffectsPowerMode.HIGH.storageValue).resolveDsp(headphones)
        assertEquals(2f, high.params[NeriDspParams.QUALITY_MODE])
        assertEquals(1f, high.params[NeriDspParams.DITHER_ENABLED])
    }

    @Test
    fun `per output profiles follow the active route`() {
        val bass = requireNotNull(findAudioEffectsBuiltInPreset("bass_boost"))
        val settings = AudioEffectsSettings(perOutputEnabled = true)
            .updateProfileFor(AudioOutputRoute.BLUETOOTH) { it.applyBuiltInPreset(bass) }
        assertTrue(settings.resolveDsp(AudioEffectsRuntimeContext(AudioOutputRoute.BLUETOOTH)).active)
        assertFalse(settings.resolveDsp(headphones).active)
        assertEquals("bass_boost", settings.profileFor(AudioOutputRoute.BLUETOOTH).presetId)
        assertEquals(AudioEffectsPresetIds.FLAT, settings.profileFor(AudioOutputRoute.WIRED).presetId)
    }

    @Test
    fun `parametric bands are packed with native type ids`() {
        val sound = AudioEffectsSound(
            parametricEnabled = true,
            parametricBands = listOf(
                ParametricEqBand(type = ParametricEqBandType.HIGH_SHELF.storageValue, frequencyHz = 9_000f, gainDb = -3f, q = 0.7f)
            )
        )
        val params = AudioEffectsSettings(main = AudioEffectsProfile(enabled = true, sound = sound)).resolveDsp(headphones).params
        val base = NeriDspParams.PARAMETRIC_BAND_0
        assertEquals(1f, params[NeriDspParams.PARAMETRIC_EQ_ENABLED])
        assertEquals(1f, params[base + NeriDspParams.BAND_ENABLED])
        assertEquals(2f, params[base + NeriDspParams.BAND_TYPE])
        assertEquals(9_000f, params[base + NeriDspParams.BAND_FREQUENCY_HZ])
        assertEquals(-3f, params[base + NeriDspParams.BAND_GAIN_DB])
        assertEquals(0f, params[base + NeriDspParams.PARAMETRIC_BAND_STRIDE + NeriDspParams.BAND_ENABLED])
    }

    @Test
    fun `delay effect reaches the native params`() {
        val sound = AudioEffectsSound(
            delayEnabled = true,
            delayTimeMs = 320f,
            delayFeedback = 0.4f,
            delayMix = 0.3f
        )
        val params = AudioEffectsSettings(main = AudioEffectsProfile(enabled = true, sound = sound))
            .resolveDsp(headphones).params
        assertEquals(1f, params[NeriDspParams.DELAY_ENABLED])
        assertEquals(320f, params[NeriDspParams.DELAY_TIME_MS])
        assertEquals(0.4f, params[NeriDspParams.DELAY_FEEDBACK])
        assertEquals(0.3f, params[NeriDspParams.DELAY_MIX])
    }

    @Test
    fun `delay alone keeps the engine active`() {
        val sound = AudioEffectsSound(delayEnabled = true)
        assertFalse(sound.isNeutral())
        val resolution = AudioEffectsSettings(main = AudioEffectsProfile(enabled = true, sound = sound))
            .resolveDsp(headphones)
        assertTrue(resolution.active)
    }

    @Test
    fun `turning the space section off also turns the delay off`() {
        val sound = AudioEffectsSound(delayEnabled = true, delayTimeMs = 400f)
        val effective = sound.copy(spaceEnabled = false).effective()
        assertFalse(effective.delayEnabled)
        assertTrue(effective.isNeutral())
    }

    @Test
    fun `loudness normalization alone keeps the engine active`() {
        val settings = AudioEffectsSettings(
            main = AudioEffectsProfile(enabled = true),
            loudnessNormalization = LoudnessNormalizationSettings(
                enabled = true,
                targetLufs = -16f,
                maxGainDb = 9f
            )
        )
        val resolution = settings.resolveDsp(headphones)
        assertTrue(resolution.active)
        assertEquals(1f, resolution.params[NeriDspParams.LOUDNESS_NORMALIZATION_ENABLED])
        assertEquals(-16f, resolution.params[NeriDspParams.LOUDNESS_TARGET_LUFS])
        assertEquals(9f, resolution.params[NeriDspParams.LOUDNESS_MAX_GAIN_DB])
    }

    @Test
    fun `loudness normalization follows the master switch`() {
        val settings = AudioEffectsSettings(
            main = AudioEffectsProfile(enabled = false),
            loudnessNormalization = LoudnessNormalizationSettings(enabled = true)
        )
        val resolution = settings.resolveDsp(headphones)
        assertFalse(resolution.active)
        assertEquals(AudioEffectsInactiveReason.DISABLED, resolution.inactiveReason)
    }

    @Test
    fun `loudness normalization stays inside safe ranges`() {
        val loudness = LoudnessNormalizationSettings(
            enabled = true,
            targetLufs = -99f,
            maxGainDb = 99f
        ).normalized()
        assertEquals(-24f, loudness.targetLufs)
        assertEquals(18f, loudness.maxGainDb)

        val sound = AudioEffectsSound(delayTimeMs = 9999f, delayFeedback = 5f, delayMix = 9f).normalized()
        assertEquals(1000f, sound.delayTimeMs)
        assertEquals(0.85f, sound.delayFeedback)
        assertEquals(1f, sound.delayMix)
    }
}

package com.TapLink.app.media

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Adds the NextLib FFmpeg software AUDIO decoder (AC3/E-AC3/DTS/TrueHD/etc.)
 * after the device's MediaCodec audio renderer, while leaving VIDEO entirely on
 * the platform hardware decoders.
 *
 * Why audio-only: enabling the FFmpeg VIDEO decoders (as the full
 * NextRenderersFactory does) caused some films to render with washed-out /
 * wrong contrast on the X3's additive display — the software video path doesn't
 * match the hardware decoder's color handling. We only ever needed FFmpeg for
 * the audio codecs the device can't decode, so video stays on hardware.
 */
@UnstableApi
class FfmpegAudioOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        // FFmpeg audio renderer is added LAST so the hardware MediaCodec decoder
        // is preferred and FFmpeg only handles formats the device can't.
        runCatching {
            out.add(
                io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer(
                    eventHandler,
                    eventListener,
                    audioSink
                )
            )
        }
    }
}

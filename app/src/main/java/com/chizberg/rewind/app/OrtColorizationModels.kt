package com.chizberg.rewind.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import com.chizberg.rewind.colorization.ColorizationModel
import com.chizberg.rewind.colorization.ColorizationModelId
import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.models.DDColorGraph
import com.chizberg.rewind.colorization.models.DDColorLarge
import com.chizberg.rewind.colorization.models.ECCV16
import com.chizberg.rewind.colorization.models.ECCV16Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer

/**
 * A colorization model over an installed ONNX graph — what `ColorizationModelStore.makeModel`
 * builds. The graph is loaded on the first prediction, as on iOS.
 */
fun makeOrtColorizationModel(
    context: Context,
    id: ColorizationModelId,
    file: File,
): ColorizationModel =
    when (id) {
        ColorizationModelId.DDColorLarge -> {
            val loader = OrtLoader(context, file, DDCOLOR_REQUIRED_MEMORY)
            ClosingModel(DDColorLarge(OrtDDColorGraph(loader)), loader)
        }
        ColorizationModelId.Eccv16 -> {
            val loader = OrtLoader(context, file, ECCV16_REQUIRED_MEMORY)
            ClosingModel(ECCV16(OrtECCV16Graph(loader)), loader)
        }
    }

/**
 * Lets the store free a model it stops handing out. iOS drops the reference and ARC frees the
 * graph; an ONNX Runtime session is native memory the garbage collector does not see, so it is
 * closed explicitly — after the run that may still hold it.
 */
private class ClosingModel(
    private val model: ColorizationModel,
    private val loader: OrtLoader,
) : ColorizationModel by model,
    AutoCloseable {
    override fun close() = loader.close()
}

/**
 * Port of iOS `CoreMLLoader`: opens the session on the first run and keeps it, refusing to load
 * when the device has less memory available than the graph needs — rather than letting the
 * low-memory killer take the app down halfway.
 */
private class OrtLoader(
    private val context: Context,
    private val file: File,
    private val requiredMemory: Long,
) : AutoCloseable {
    private val mutex = Mutex()
    private var session: OrtSession? = null

    @Volatile
    private var closed = false

    suspend fun <T> run(block: (OrtEnvironment, OrtSession) -> T): T =
        withContext(Dispatchers.Default) {
            try {
                mutex.withLock {
                    // A loader closed before its first run still runs once: the colorize tap came
                    // first, and the session goes again straight after.
                    val environment = OrtEnvironment.getEnvironment()
                    val opened = session ?: open(environment).also { session = it }
                    block(environment, opened)
                }
            } finally {
                // After the lock, not inside it: a close() that came while this run held the lock
                // could not take it, and would otherwise leave the session to nobody.
                releaseIfClosed()
            }
        }

    override fun close() {
        closed = true
        releaseIfClosed()
    }

    private fun releaseIfClosed() {
        if (closed && mutex.tryLock()) {
            try {
                release()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun release() {
        session?.close()
        session = null
    }

    private fun open(environment: OrtEnvironment): OrtSession {
        if (!file.exists()) throw IOException("The colorization model is not on disk any more")
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (memory.availMem < requiredMemory) {
            throw IOException(
                "Not enough memory for colorization: ${memory.availMem / MEGABYTE} MB available",
            )
        }
        return OrtSession.SessionOptions().use { environment.createSession(file.absolutePath, it) }
    }
}

/** DDColor's graph: the gray frame replicated into RGB, 0...1, ab out at the same size. */
private class OrtDDColorGraph(
    private val loader: OrtLoader,
) : DDColorGraph {
    override suspend fun infer(square: GrayPlane): ABPlanes =
        loader.run { environment, session ->
            val count = square.size.pixelCount
            val input = FloatBuffer.allocate(count * RGB_CHANNELS)
            repeat(RGB_CHANNELS) {
                for (index in 0 until count) input.put(square[index] / BYTE_MAX)
            }
            input.rewind()
            val shape = square.size.tensorShape(RGB_CHANNELS)
            OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                session.run(mapOf("grayRGB" to tensor)).use { it.abPlanes(square.size) }
            }
        }
}

/** ECCV16's graph: L of the squashed frame and the decode's exponent in, ab out. */
private class OrtECCV16Graph(
    private val loader: OrtLoader,
) : ECCV16Graph {
    override suspend fun infer(
        lightness: FloatPlane,
        rebalance: Float,
    ): ABPlanes =
        loader.run { environment, session ->
            val values = FloatBuffer.wrap(lightness.values)
            val shape = lightness.size.tensorShape(1)
            OnnxTensor.createTensor(environment, values, shape).use { l ->
                val exponent = FloatBuffer.wrap(floatArrayOf(rebalance))
                OnnxTensor.createTensor(environment, exponent, longArrayOf(1)).use { r ->
                    session.run(mapOf("lightness" to l, "rebalance" to r)).use { result ->
                        result.abPlanes(lightness.size)
                    }
                }
            }
        }
}

/** NCHW, one frame: how both graphs were traced. */
private fun PlaneSize.tensorShape(channels: Int) =
    longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())

/** The graph's `ab` output, (1, 2, H, W), as two planes. */
private fun OrtSession.Result.abPlanes(size: PlaneSize): ABPlanes {
    val output = (get("ab").orElseThrow() as OnnxTensor).floatBuffer
    val count = size.pixelCount
    val a = FloatArray(count)
    val b = FloatArray(count)
    output.get(a)
    output.get(b)
    return ABPlanes(size, a, b)
}

private const val RGB_CHANNELS = 3
private const val BYTE_MAX = 255f
private const val MEGABYTE = 1_000_000

/**
 * Memory gate thresholds. ONNX Runtime folds DDColor's fp16 weights into fp32 when it loads the
 * graph (~0.9 GB, twice what Core ML keeps), plus the activations at 384; ECCV16 is fp32 already,
 * so it keeps iOS's figure.
 */
private const val DDCOLOR_REQUIRED_MEMORY = 1_600_000_000L
private const val ECCV16_REQUIRED_MEMORY = 400_000_000L

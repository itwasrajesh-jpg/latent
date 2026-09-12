package com.celestial.latent.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The viewfinder, drawn by us instead of by the system, so a film look can be applied to the
 * live image. Camera frames arrive as an external texture, are drawn through a shader, and
 * (later) looked up in a 3D table baked from the chosen film.
 *
 * This first version applies no look: with [lut] unset the shader is a straight copy, so the
 * preview must look exactly as it did before. That is the test that this path is correct.
 */
class FilmPreviewView(context: Context) : GLSurfaceView(context) {

    /** Called once the camera can start: gives the Surface to draw camera frames into. */
    var onSurfaceReady: ((Surface, SurfaceTexture) -> Unit)? = null

    /** Called if the shader could not be built, so the screen can fall back to the plain preview. */
    var onUnavailable: ((String) -> Unit)? = null

    private val renderer = FilmRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Size the camera buffer; must match what the capture session was configured with. */
    fun setBufferSize(width: Int, height: Int) = renderer.setBufferSize(width, height)

    /** 33×33×33 RGB table, or null for a neutral pass-through. */
    fun setLut(lut: FloatArray?, size: Int = 33) {
        renderer.pendingLut = lut
        renderer.pendingLutSize = size
        requestRender()
    }

    /** Linear gain applied before the table, matching the engine's own auto-exposure. */
    fun setExposureGain(gain: Float) { renderer.exposureGain = gain; requestRender() }

    fun release() = renderer.release()

    private inner class FilmRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var program = 0
        private var texture = 0
        private var cameraSurfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private val transform = FloatArray(16)
        private var bufW = 1920
        private var bufH = 1440
        private var viewW = 0
        private var viewH = 0

        @Volatile var pendingLut: FloatArray? = null
        @Volatile var pendingLutSize = 33
        @Volatile var exposureGain = 1f
        private var lutTexture = 0
        private var lutSize = 0
        private var reportedError = false

        fun setBufferSize(w: Int, h: Int) {
            bufW = w; bufH = h
            cameraSurfaceTexture?.setDefaultBufferSize(w, h)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // The GL context is recreated after a pause: drop the previous camera surface so
            // it is never handed to the camera again, and start clean.
            release()
            lutTexture = 0
            lutSize = 0
            reportedError = false
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) { post { onUnavailable?.invoke("the film preview shader could not be built") }; return }
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(texture)
            st.setDefaultBufferSize(bufW, bufH)
            st.setOnFrameAvailableListener(this)
            cameraSurfaceTexture = st
            val surf = Surface(st)
            surface = surf
            Log.i("Latent", "film preview: GL surface ready (${bufW}x$bufH)")
            post { onSurfaceReady?.invoke(surf, st) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width; viewH = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) = requestRender()

        override fun onDrawFrame(gl: GL10?) {
            val st = cameraSurfaceTexture ?: return
            st.updateTexImage()
            st.getTransformMatrix(transform)
            uploadPendingLut()

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, UV)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGain"), exposureGain)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutSize"), lutSize.toFloat())
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uHasLut"), if (lutSize > 0) 1 else 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCamera"), 0)
            if (lutSize > 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLut"), 1)
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (!reportedError) {
                val e = GLES20.glGetError()
                reportedError = true
                if (e != GLES20.GL_NO_ERROR) Log.e("Latent", "film preview: GL error 0x${Integer.toHexString(e)} on the first frame")
                else Log.i("Latent", "film preview: first frame drawn cleanly (${viewW}x$viewH)")
            }
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(uv)
        }

        /** The 3D table is stored as a wide 2D strip: size×size across, size down. */
        private fun uploadPendingLut() {
            val lut = pendingLut ?: return
            pendingLut = null
            val n = pendingLutSize
            if (lutTexture == 0) {
                val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); lutTexture = ids[0]
            }
            val w = n * n
            val bytes = java.nio.ByteBuffer.allocateDirect(w * n * 4).order(java.nio.ByteOrder.nativeOrder())
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                val i = ((b * n + g) * n + r) * 3
                bytes.put(((lut[i] * 255f).toInt().coerceIn(0, 255)).toByte())
                bytes.put(((lut[i + 1] * 255f).toInt().coerceIn(0, 255)).toByte())
                bytes.put(((lut[i + 2] * 255f).toInt().coerceIn(0, 255)).toByte())
                bytes.put(255.toByte())
            }
            bytes.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            lutSize = n
            Log.i("Latent", "film preview: look table uploaded ($n³)")
        }

        fun release() {
            surface?.release(); surface = null
            cameraSurfaceTexture?.release(); cameraSurfaceTexture = null
        }
    }

    /** Builds the program, reporting exactly why if it fails. Returns 0 on failure. */
    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String, label: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            val info = GLES20.glGetShaderInfoLog(id)
            if (ok[0] == 0) {
                Log.e("Latent", "film preview: $label shader did not compile — ${info.trim()}")
                GLES20.glDeleteShader(id)
                return 0
            }
            if (info.isNotBlank()) Log.i("Latent", "film preview: $label shader note — ${info.trim()}")
            return id
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs, "vertex")
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs, "fragment")
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e("Latent", "film preview: program did not link — ${GLES20.glGetProgramInfoLog(p).trim()}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        Log.i("Latent", "film preview: shader program ready")
        return p
    }

    companion object {
        private val QUAD = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val UV = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun floatBuffer(a: FloatArray) = java.nio.ByteBuffer.allocateDirect(a.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(a).apply { position(0) }

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uTransform;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTransform * aUv).xy;
            }
        """

        // With uHasLut = 0 this is a straight copy of the camera image: the check that the
        // new preview path is correct before any film look is applied.
        private const val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uCamera;
uniform sampler2D uLut;
uniform float uLutSize;
uniform float uGain;
uniform int uHasLut;

            vec3 lookup(vec3 c) {
                float n = uLutSize;
                vec3 q = clamp(c, 0.0, 1.0) * (n - 1.0);
                float bLo = floor(q.b);
                float bHi = min(bLo + 1.0, n - 1.0);
                float f = q.b - bLo;
                vec2 uvLo = vec2((bLo * n + q.r + 0.5) / (n * n), (q.g + 0.5) / n);
                vec2 uvHi = vec2((bHi * n + q.r + 0.5) / (n * n), (q.g + 0.5) / n);
                return mix(texture2D(uLut, uvLo).rgb, texture2D(uLut, uvHi).rgb, f);
            }

            void main() {
                vec3 c = texture2D(uCamera, vUv).rgb;
                if (uHasLut == 1) {
                    c = lookup(clamp(c * uGain, 0.0, 1.0));
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}

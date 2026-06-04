package pl.kejmil.oledsender

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt

class Esp32ModelView(context: Context, backgroundColor: Int) : GLSurfaceView(context) {
    private val renderer = Esp32ModelRenderer(context.applicationContext, backgroundColor)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

private class Esp32ModelRenderer(context: Context, private val backgroundColor: Int) : GLSurfaceView.Renderer {
    private val mesh = runCatching {
        context.assets.open("esp32.stl").use { StlMesh.load(it.readBytes()) }
    }.getOrElse {
        StlMesh.placeholder()
    }
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val startTime = SystemClock.uptimeMillis()
    private var program = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var mvpHandle = 0
    private var modelHandle = 0
    private var colorHandle = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        modelHandle = GLES20.glGetUniformLocation(program, "uModelMatrix")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glClearColor(
            Color.red(backgroundColor) / 255f,
            Color.green(backgroundColor) / 255f,
            Color.blue(backgroundColor) / 255f,
            1f
        )
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / max(1, height).toFloat()
        Matrix.perspectiveM(projection, 0, 34f, aspect, 0.1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val elapsed = (SystemClock.uptimeMillis() - startTime).toFloat()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, -0.08f, -3.25f)
        Matrix.rotateM(model, 0, 58f, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, elapsed / 38f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, projection, 0, model, 0)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0)
        GLES20.glUniform4f(colorHandle, 0.05f, 0.78f, 0.74f, 1f)

        mesh.vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, mesh.vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)

        mesh.normals.position(0)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, mesh.normals)
        GLES20.glEnableVertexAttribArray(normalHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val newProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(newProgram)
            GLES20.glDeleteProgram(newProgram)
            throw IllegalStateException("OpenGL program link failed: $log")
        }
        return newProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("OpenGL shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying float vLight;
            void main() {
                vec3 normal = normalize(mat3(uModelMatrix) * aNormal);
                vec3 light = normalize(vec3(-0.35, 0.75, 0.55));
                vLight = max(dot(normal, light), 0.0);
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            varying float vLight;
            void main() {
                float shade = 0.36 + (0.64 * vLight);
                gl_FragColor = vec4(uColor.rgb * shade, uColor.a);
            }
        """
    }
}

private class StlMesh(
    val vertices: FloatBuffer,
    val normals: FloatBuffer,
    val vertexCount: Int
) {
    companion object {
        fun load(bytes: ByteArray): StlMesh {
            if (looksLikeBinaryStl(bytes)) {
                return loadBinary(bytes)
            }
            return loadAscii(bytes.toString(Charsets.UTF_8))
        }

        fun placeholder(): StlMesh {
            val vertices = floatArrayOf(
                -0.9f, -0.55f, 0f, 0.9f, -0.55f, 0f, 0.9f, 0.55f, 0f,
                -0.9f, -0.55f, 0f, 0.9f, 0.55f, 0f, -0.9f, 0.55f, 0f
            )
            val normals = FloatArray(vertices.size) { index ->
                if (index % 3 == 2) 1f else 0f
            }
            return fromArrays(vertices, normals)
        }

        private fun looksLikeBinaryStl(bytes: ByteArray): Boolean {
            if (bytes.size < 84) return false
            val count = ByteBuffer.wrap(bytes, 80, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and 0xFFFFFFFFL
            val expected = 84L + count * 50L
            return count > 0L && expected <= bytes.size.toLong()
        }

        private fun loadBinary(bytes: ByteArray): StlMesh {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(80)
            val triangleCount = buffer.int
            require(triangleCount > 0) { "STL has no triangles" }

            val vertices = FloatArray(triangleCount * 9)
            val normals = FloatArray(triangleCount * 9)
            val bounds = Bounds()
            var out = 0

            repeat(triangleCount) {
                val normal = floatArrayOf(buffer.float, buffer.float, buffer.float)
                val triangle = FloatArray(9)
                for (index in 0 until 9) {
                    triangle[index] = buffer.float
                }
                buffer.short

                val fixedNormal = if (length(normal) > 0.00001f) {
                    normalize(normal)
                } else {
                    normalFromTriangle(triangle)
                }
                for (index in 0 until 9) {
                    vertices[out + index] = triangle[index]
                    bounds.include(
                        triangle[(index / 3) * 3],
                        triangle[(index / 3) * 3 + 1],
                        triangle[(index / 3) * 3 + 2]
                    )
                    normals[out + index] = fixedNormal[index % 3]
                }
                out += 9
            }

            normalizeVertices(vertices, bounds)
            return fromArrays(vertices, normals)
        }

        private fun loadAscii(text: String): StlMesh {
            val vertexList = ArrayList<Float>()
            val normalList = ArrayList<Float>()
            val triangle = ArrayList<Float>(9)
            val bounds = Bounds()
            var currentNormal = floatArrayOf(0f, 0f, 1f)

            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("facet normal") -> {
                        currentNormal = parseThreeFloats(line.removePrefix("facet normal"))
                    }
                    line.startsWith("vertex") -> {
                        val vertex = parseThreeFloats(line.removePrefix("vertex"))
                        triangle += vertex[0]
                        triangle += vertex[1]
                        triangle += vertex[2]
                        if (triangle.size == 9) {
                            val tri = triangle.toFloatArray()
                            val fixedNormal = if (length(currentNormal) > 0.00001f) {
                                normalize(currentNormal)
                            } else {
                                normalFromTriangle(tri)
                            }
                            for (index in 0 until 9) {
                                val value = tri[index]
                                vertexList += value
                                normalList += fixedNormal[index % 3]
                            }
                            bounds.include(tri[0], tri[1], tri[2])
                            bounds.include(tri[3], tri[4], tri[5])
                            bounds.include(tri[6], tri[7], tri[8])
                            triangle.clear()
                        }
                    }
                }
            }

            require(vertexList.isNotEmpty()) { "ASCII STL has no vertices" }
            val vertices = vertexList.toFloatArray()
            normalizeVertices(vertices, bounds)
            return fromArrays(vertices, normalList.toFloatArray())
        }

        private fun fromArrays(vertices: FloatArray, normals: FloatArray): StlMesh {
            return StlMesh(
                vertices = vertices.toFloatBuffer(),
                normals = normals.toFloatBuffer(),
                vertexCount = vertices.size / 3
            )
        }

        private fun FloatArray.toFloatBuffer(): FloatBuffer {
            return ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(this)
                .apply { position(0) }
        }

        private fun normalizeVertices(vertices: FloatArray, bounds: Bounds) {
            val centerX = (bounds.minX + bounds.maxX) / 2f
            val centerY = (bounds.minY + bounds.maxY) / 2f
            val centerZ = (bounds.minZ + bounds.maxZ) / 2f
            val maxDimension = max(
                max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY),
                bounds.maxZ - bounds.minZ
            ).takeIf { it > 0.00001f } ?: 1f
            val scale = 1.82f / maxDimension
            var index = 0
            while (index < vertices.size) {
                vertices[index] = (vertices[index] - centerX) * scale
                vertices[index + 1] = (vertices[index + 1] - centerY) * scale
                vertices[index + 2] = (vertices[index + 2] - centerZ) * scale
                index += 3
            }
        }

        private fun parseThreeFloats(text: String): FloatArray {
            val parts = text.trim().split(Regex("\\s+"))
            return floatArrayOf(
                parts.getOrNull(0)?.toFloatOrNull() ?: 0f,
                parts.getOrNull(1)?.toFloatOrNull() ?: 0f,
                parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            )
        }

        private fun normalFromTriangle(triangle: FloatArray): FloatArray {
            val ux = triangle[3] - triangle[0]
            val uy = triangle[4] - triangle[1]
            val uz = triangle[5] - triangle[2]
            val vx = triangle[6] - triangle[0]
            val vy = triangle[7] - triangle[1]
            val vz = triangle[8] - triangle[2]
            return normalize(
                floatArrayOf(
                    uy * vz - uz * vy,
                    uz * vx - ux * vz,
                    ux * vy - uy * vx
                )
            )
        }

        private fun normalize(vector: FloatArray): FloatArray {
            val len = length(vector).takeIf { it > 0.00001f } ?: 1f
            return floatArrayOf(vector[0] / len, vector[1] / len, vector[2] / len)
        }

        private fun length(vector: FloatArray): Float {
            return sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
        }
    }
}

private class Bounds {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY

    fun include(x: Float, y: Float, z: Float) {
        minX = minOf(minX, x)
        minY = minOf(minY, y)
        minZ = minOf(minZ, z)
        maxX = maxOf(maxX, x)
        maxY = maxOf(maxY, y)
        maxZ = maxOf(maxZ, z)
    }
}

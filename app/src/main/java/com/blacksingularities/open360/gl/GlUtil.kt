package com.blacksingularities.open360.gl

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GlUtil {
    fun program(vs: String, fs: String): Int {
        val v = shader(GL_VERTEX_SHADER, vs)
        val f = shader(GL_FRAGMENT_SHADER, fs)
        val p = glCreateProgram()
        glAttachShader(p, v)
        glAttachShader(p, f)
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(p)
            Log.e("Open360", "link: $log")
            throw IllegalStateException("Shader link: $log")
        }
        glDeleteShader(v)
        glDeleteShader(f)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e("Open360", "compile: $log")
            throw IllegalStateException("Shader: $log")
        }
        return s
    }

    fun quad(): Int {
        val data = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        val vao = IntArray(1)
        glGenVertexArrays(1, vao, 0)
        glBindVertexArray(vao[0])
        val vbo = IntArray(1)
        glGenBuffers(1, vbo, 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
        glBindVertexArray(0)
        return vao[0]
    }

    fun drawQuad(vao: Int) {
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindVertexArray(0)
    }

    fun texture(w: Int, h: Int, filter: Int, wrap: Int, clampT: Boolean = false, mipmaps: Boolean = false): Int {
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, if (filter == GL_NEAREST) GL_NEAREST else GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, if (clampT) GL_CLAMP_TO_EDGE else wrap)
        if (mipmaps) {
            var levels = 1
            var s = maxOf(w, h)
            while (s > 1) { s /= 2; levels++ }
            glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, w, h)
        } else {
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h)
        }
        return t[0]
    }

    /** Tekstura o zmiennym rozmiarze (klatki kamery) – bez glTexStorage. */
    fun mutableTexture(): Int {
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        return t[0]
    }

    fun fbo(tex: Int): Int {
        val f = IntArray(1)
        glGenFramebuffers(1, f, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, f[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) Log.e("Open360", "FBO status $status")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return f[0]
    }

    fun bindTex(prog: Int, name: String, unit: Int, tex: Int) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(glGetUniformLocation(prog, name), unit)
    }

    fun deleteTextures(vararg t: Int) {
        val ids = t.filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) glDeleteTextures(ids.size, ids, 0)
    }

    fun deleteFbos(vararg f: Int) {
        val ids = f.filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) glDeleteFramebuffers(ids.size, ids, 0)
    }
}

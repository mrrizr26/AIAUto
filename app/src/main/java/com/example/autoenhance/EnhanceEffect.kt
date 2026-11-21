package com.example.autoenhance

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleFrameGlShaderProgram

/**
 * Ekta GPU shader e shob kaj:
 * edge-preserving denoise -> sharpen -> white balance -> levels -> gamma ->
 * S-curve contrast -> split-tone -> vibrance/saturation.
 * Preview (ExoPlayer) ar export (Transformer) duto jaygay same effect.
 */
@OptIn(UnstableApi::class)
class EnhanceEffect(private val params: GradeParams) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhanceShaderProgram(params, useHdr)
}

@OptIn(UnstableApi::class)
private class EnhanceShaderProgram(
    private val p: GradeParams,
    useHdr: Boolean
) : SingleFrameGlShaderProgram(useHdr) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        program.setFloatsUniform("uTexel", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        val shortSide = if (inputWidth < inputHeight) inputWidth else inputHeight
        program.setFloatUniform("uRadius", maxOf(0.75f, shortSide / 1080f))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uGain", p.gain)
            program.setFloatUniform("uBp", p.blackPoint)
            program.setFloatUniform("uInvRange", p.invRange)
            program.setFloatUniform("uGamma", p.gamma)
            program.setFloatUniform("uContrast", p.contrast)
            program.setFloatUniform("uVibrance", p.vibrance)
            program.setFloatUniform("uSaturation", p.saturation)
            program.setFloatsUniform("uShadowTint", p.shadowTint)
            program.setFloatsUniform("uHighTint", p.highTint)
            program.setFloatUniform("uHighlights", p.highlights)
            program.setFloatUniform("uShadows", p.shadows)
            program.setFloatUniform("uClarity", p.clarity)
            program.setFloatUniform("uVignette", p.vignette)
            program.setFloatUniform("uMono", p.mono)
            program.setFloatUniform("uFade", p.fade)
            program.setFloatUniform("uDenoise", p.denoise)
            program.setFloatUniform("uSharpen", p.sharpen)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform vec2 uTexel;
uniform float uRadius;
uniform vec3 uGain;
uniform float uBp;
uniform float uInvRange;
uniform float uGamma;
uniform float uContrast;
uniform float uVibrance;
uniform float uSaturation;
uniform vec3 uShadowTint;
uniform vec3 uHighTint;
uniform float uHighlights;
uniform float uShadows;
uniform float uClarity;
uniform float uVignette;
uniform float uMono;
uniform float uFade;
uniform float uDenoise;
uniform float uSharpen;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 tap(vec2 o) { return texture2D(uTexSampler, vTexSamplingCoord + o * uTexel).rgb; }

// 8 directions ring average at radius r (texel)
vec3 ring(float r) {
  float d = r * 0.7071;
  vec3 s = tap(vec2( r, 0.0)) + tap(vec2(-r, 0.0)) + tap(vec2(0.0,  r)) + tap(vec2(0.0, -r))
         + tap(vec2( d,  d)) + tap(vec2(-d,  d)) + tap(vec2( d, -d)) + tap(vec2(-d, -d));
  return s / 8.0;
}

void main() {
  vec3 c  = tap(vec2(0.0, 0.0));
  vec3 n1 = tap(vec2(-1.0, 0.0));
  vec3 n2 = tap(vec2( 1.0, 0.0));
  vec3 n3 = tap(vec2( 0.0,-1.0));
  vec3 n4 = tap(vec2( 0.0, 1.0));
  vec3 n5 = tap(vec2(-1.0,-1.0));
  vec3 n6 = tap(vec2( 1.0,-1.0));
  vec3 n7 = tap(vec2(-1.0, 1.0));
  vec3 n8 = tap(vec2( 1.0, 1.0));

  // --- edge-preserving denoise (bilateral 3x3) ---
  float s2 = 2.0 * pow(0.02 + 0.08 * uDenoise, 2.0);
  vec3 d;
  d = n1 - c; float w1 = exp(-dot(d, d) / s2);
  d = n2 - c; float w2 = exp(-dot(d, d) / s2);
  d = n3 - c; float w3 = exp(-dot(d, d) / s2);
  d = n4 - c; float w4 = exp(-dot(d, d) / s2);
  d = n5 - c; float w5 = 0.7 * exp(-dot(d, d) / s2);
  d = n6 - c; float w6 = 0.7 * exp(-dot(d, d) / s2);
  d = n7 - c; float w7 = 0.7 * exp(-dot(d, d) / s2);
  d = n8 - c; float w8 = 0.7 * exp(-dot(d, d) / s2);
  vec3 den = (c + w1*n1 + w2*n2 + w3*n3 + w4*n4 + w5*n5 + w6*n6 + w7*n7 + w8*n8)
           / (1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8);
  vec3 base = mix(c, den, clamp(uDenoise, 0.0, 1.0));

  // --- fine sharpen (luma, noise cut kore) ---
  vec3 blur = (c + n1 + n2 + n3 + n4 + n5 + n6 + n7 + n8) / 9.0;
  float detail = luma(c) - luma(blur);
  float th = 0.008 + 0.02 * uDenoise;
  detail = sign(detail) * max(abs(detail) - th, 0.0);
  base += vec3(uSharpen * 2.0 * detail);
  base = clamp(base, 0.0, 1.0);

  // --- DEEP DETAILS: 2-scale local contrast (texture + structure) ---
  if (uClarity > 0.001) {
    float lb = luma(base);
    float fine   = lb - luma(ring(3.0 * uRadius));
    float coarse = lb - luma(ring(8.0 * uRadius));
    float det = clamp(0.6 * fine + 0.4 * coarse, -0.12, 0.12);
    float mid = 0.35 + 0.65 * 4.0 * lb * (1.0 - lb);      // midtone e beshi, shadow/highlight e kom
    base = clamp(base + vec3(uClarity * 2.5 * det * mid), 0.0, 1.0);
  }

  // --- tone + color ---
  vec3 x = clamp(base * uGain, 0.0, 1.0);
  x = clamp((x - vec3(uBp)) * uInvRange, 0.0, 1.0);
  x = pow(x, vec3(uGamma));
  vec3 sc = x * x * (3.0 - 2.0 * x);
  x = clamp(x + uContrast * (sc - x), 0.0, 1.0);

  float y = luma(x);
  float shadowMask = 0.35 + 0.65 * smoothstep(0.0, 0.3, y);
  x += vec3(uShadows * 0.25 * pow(1.0 - y, 2.0) * shadowMask);
  x += vec3(uHighlights * 0.20 * y * y);
  x += uShadowTint * pow(1.0 - y, 2.0) + uHighTint * (y * y);
  x = clamp(x, 0.0, 1.0);

  y = luma(x);
  float mx = max(x.r, max(x.g, x.b));
  float mn = min(x.r, min(x.g, x.b));
  float satPix = mx > 0.001 ? (mx - mn) / mx : 0.0;
  float f = uSaturation * (1.0 + uVibrance * (1.0 - satPix));
  x = clamp(vec3(y) + (x - vec3(y)) * f, 0.0, 1.0);

  // --- look: noir, fade, vignette ---
  x = mix(x, vec3(luma(x)), uMono);
  x = x * (1.0 - uFade) + vec3(uFade * 0.2);
  vec2 q = vTexSamplingCoord - vec2(0.5);
  x *= 1.0 - uVignette * smoothstep(0.30, 0.85, length(q) * 1.4);

  gl_FragColor = vec4(clamp(x, 0.0, 1.0), 1.0);
}
"""
    }
}

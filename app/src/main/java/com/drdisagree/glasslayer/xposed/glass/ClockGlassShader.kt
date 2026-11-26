package com.drdisagree.glasslayer.xposed.glass

import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import kotlin.math.max

internal object ClockGlassShader {

    const val CLOCK = "clock"
    const val WALLPAPER = "wallpaper"
    const val MASK = "mask"
    const val REFRACTION_RATIO = 0.18f
    const val MAX_EDGE_DP = 9f
    const val MAX_REFRACTION_DP = 11f
    private const val DISPERSION = 0.06f

    fun edge(shortSide: Float, density: Float): Float = minOf(shortSide * REFRACTION_RATIO, MAX_EDGE_DP * density)

    fun effect(
        shader: RuntimeShader,
        wallpaper: BitmapShader,
        mask: ClockMask,
        shortSide: Float,
        density: Float,
        refraction: Float,
        reflection: Float,
        rim: Float,
        tint: Float
    ): RenderEffect {
        shader.setInputShader(WALLPAPER, wallpaper)
        shader.setInputShader(MASK, mask.shader)
        shader.setFloatUniform("maskOrigin", mask.originX / mask.scale, mask.originY / mask.scale)
        shader.setFloatUniform("maskScale", mask.scale)
        shader.setFloatUniform("maskRange", mask.range / mask.scale)
        shader.setFloatUniform("refraction", minOf(shortSide * REFRACTION_RATIO, MAX_REFRACTION_DP * density) * refraction)
        shader.setFloatUniform("edge", max(1f, edge(shortSide, density)))
        shader.setFloatUniform("dispersion", DISPERSION)
        shader.setFloatUniform("reflection", reflection)
        shader.setFloatUniform("rim", rim)
        shader.setFloatUniform("tint", tint)
        return RenderEffect.createRuntimeShaderEffect(shader, CLOCK)
    }

    const val SOURCE = """
        uniform shader clock;
        uniform shader wallpaper;
        uniform shader mask;
        uniform float2 maskOrigin;
        uniform float maskScale;
        uniform float maskRange;
        uniform float refraction;
        uniform float edge;
        uniform float dispersion;
        uniform float reflection;
        uniform float rim;
        uniform float tint;

        float maskDistance(float2 coord) {
            float m = mask.eval((coord - maskOrigin) * maskScale).a;
            return (0.5 - m) * 2.0 * maskRange;
        }

        half4 main(float2 position) {
            half4 glyph = clock.eval(position);
            if (glyph.a <= 0.001) return half4(0.0);

            float d = maskDistance(position);
            float2 m = float2(2.0, 0.0);
            float2 normal = float2(
                maskDistance(position + m.xy) - maskDistance(position - m.xy),
                maskDistance(position + m.yx) - maskDistance(position - m.yx)
            );
            float width = min(edge, maskRange * 0.9);
            float strength = smoothstep(0.05, 0.4, length(normal));
            normal = normal / max(length(normal), 0.0001);

            float band = clamp(1.0 + d / max(width, 1.0), 0.0, 1.0) * smoothstep(2.0, -0.5, d);
            float bend = band * band * strength;
            float2 offset = -normal * refraction * bend;
            float2 tangent = float2(-normal.y, normal.x);
            float edgeBlur = bend * 3.0;

            half3 color = half3(0.0);
            if (edgeBlur > 0.05) {
                float2 rPos = position + offset * (1.0 + dispersion);
                float2 gPos = position + offset;
                float2 bPos = position + offset * (1.0 - dispersion);
                float2 s1 = tangent * edgeBlur;
                float2 s2 = normal * (edgeBlur * 0.6);
                color.r = (wallpaper.eval(rPos).r + wallpaper.eval(rPos + s1).r + wallpaper.eval(rPos - s1).r +
                    wallpaper.eval(rPos + s2).r + wallpaper.eval(rPos - s2).r) * 0.2;
                color.g = (wallpaper.eval(gPos).g + wallpaper.eval(gPos + s1).g + wallpaper.eval(gPos - s1).g +
                    wallpaper.eval(gPos + s2).g + wallpaper.eval(gPos - s2).g) * 0.2;
                color.b = (wallpaper.eval(bPos).b + wallpaper.eval(bPos + s1).b + wallpaper.eval(bPos - s1).b +
                    wallpaper.eval(bPos + s2).b + wallpaper.eval(bPos - s2).b) * 0.2;
            } else {
                color.r = wallpaper.eval(position + offset * (1.0 + dispersion)).r;
                color.g = wallpaper.eval(position + offset).g;
                color.b = wallpaper.eval(position + offset * (1.0 - dispersion)).b;
            }

            color = mix(color, glyph.rgb / max(glyph.a, half(0.001)), half(tint));

            float rimWidth = max(2.5, width * 0.4);
            float rimBand = smoothstep(-rimWidth, 0.0, d) * smoothstep(2.0, -0.5, d) * strength;
            float2 lightDir = normalize(float2(-0.65, -0.76));
            float topLeft = pow(clamp(dot(normal, lightDir), 0.0, 1.0), 1.5);
            float bottomRight = pow(clamp(dot(normal, -lightDir), 0.0, 1.0), 1.5);
            float specular = topLeft + bottomRight * 0.85;
            color += half3(rim * rimBand * (0.06 + 0.55 * specular));
            color += half3(reflection * bend * 0.16 * specular);
            color += half3(0.03);

            return half4(clamp(color, 0.0, 1.0) * glyph.a, glyph.a);
        }
    """
}

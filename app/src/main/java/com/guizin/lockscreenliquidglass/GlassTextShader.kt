package com.guizin.lockscreenliquidglass

import android.graphics.RuntimeShader

/**
 * Vidro líquido para TEXTO/glifos, com os padrões nativos do Smart Launcher que não aparecem na UI:
 *  - curva de refração circleMap: 1 - sqrt(1 - x²)  (forte na borda, suave para dentro)
 *  - refração amostrando para DENTRO (refractionAmount entra negativo no shader nativo)
 *  - dispersão em 7 cores (vermelho..roxo) quando Dispersion > 0; amostra única quando = 0
 *  - saturation fixa em 1.0 no shader nativo (aqui continua o slider, padrão 1.0)
 *  - brilho de borda nativo: largura 2 px, intensidade 0.5, concentração 0.5, gradiente diagonal
 *    (cantos opostos cima/esquerda e baixo/direita), screen + lerp para branco conforme o brilho
 *  - refractionHeight limitado à metade do menor lado da view
 * Não portado: depthEffect (1.0), que soma a direção radial a partir do centro de uma forma
 * conhecida; no texto não existe esse centro.
 *
 * Cor do relógio (uniform tint, escolhida no app): alfa 0 = vidro total, alfa 1 = relógio sólido na cor
 * escolhida, valores no meio misturam. A refração usa vetor gradiente contínuo, sem costura no eixo central.
 */
object GlassTextShader {

    const val SRC: String = """
uniform shader contents;
uniform shader backdrop;      // wallpaper nítido, em coordenadas locais da view
uniform shader backdropBlur;  // wallpaper borrado

uniform float hasBackdrop;    // 1 = há wallpaper
uniform float2 size;          // tamanho da view em px
uniform float bevelRadius;    // px: refractionHeight nativo (largura da faixa de refração)
uniform float bevelStrength;  // quão rápido a refração satura perto da borda
uniform float refractionPx;   // px: refractionAmount nativo (deslocamento máximo)
uniform float frost;          // 0..1 mistura do fundo borrado
uniform float dim;            // 0..1 escurecimento uniforme do miolo
uniform float saturation;
uniform float bodyAlpha;      // só usado sem wallpaper
uniform float2 chromAb;       // dispersão nativa (0,0 = desligada)
uniform float edgeStrength;   // edgeHighlightStrength nativo (0.5)
uniform float edgeConc;       // highlightConcentration nativo (0.5)
uniform float edgeWidth;      // px (2.0 nativo)
uniform float4 baseColor;
uniform float4 tint;          // cor do relógio escolhida no app (rgb + alfa): alfa 0 = vidro total, alfa 1 = relógio sólido nessa cor
uniform int blendMode;        // 0 = Normal, 1 = Soft Light, 2 = Overlay

float alphaAt(float2 p) {
    return contents.eval(p).a;
}

// Soma (não normalizada) do alpha em 8 direções. z = cobertura média do anel, mx = alpha máximo.
float3 field(float2 p, float r, out float mx) {
    float2 g = float2(0.0);
    float s = 0.0;
    mx = 0.0;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.785398;
        float2 d = float2(cos(ang), sin(ang));
        float a = alphaAt(p + d * r);
        g += d * a;
        s += a;
        mx = max(mx, a);
    }
    return float3(g * 0.25, s * 0.125);
}

float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}

float dispersionIntensity(float2 normal, int n) {
    return 1.0 + dot(chromAb, normal) * float(n) / 3.0;
}

float3 wallpaperAt(float2 p) {
    return float3(backdrop.eval(p).rgb);
}

float3 softLightBlend(float3 col, float3 s) {
    float3 low = col - (1.0 - 2.0 * s) * col * (1.0 - col);
    float3 high = col + (2.0 * s - 1.0) * (sqrt(max(col, 0.0)) - col);
    return mix(low, high, step(float3(0.5), s));
}

float3 overlayBlend(float3 col, float3 s) {
    float3 low = 2.0 * s * col;
    float3 high = 1.0 - 2.0 * (1.0 - s) * (1.0 - col);
    return mix(low, high, step(float3(0.5), s));
}

half4 main(float2 p) {
    half4 c = contents.eval(p);
    float ca = float(c.a);
    if (ca < 0.003) {
        return half4(0.0);
    }

    float3 base = clamp(float3(c.rgb) / ca, 0.0, 1.0);

    if (hasBackdrop < 0.5) {
        float a0 = ca * mix(bodyAlpha, 1.0, tint.a);
        return half4(half3(mix(base, tint.rgb, tint.a) * a0), half(a0));
    }

    float m1;
    float m2;
    float m3;
    float3 f1 = field(p, bevelRadius, m1);
    float3 f2 = field(p, bevelRadius * 2.0, m2);
    float3 f3 = field(p, edgeWidth, m3);

    // opacidade do relógio ~ alpha máximo da vizinhança
    float peak = max(ca, max(m1, max(m2, m3)));
    float cov = clamp(ca / peak, 0.0, 1.0);   // cobertura do glifo, sem a transparência
    f1 /= peak;
    f2 /= peak;
    f3 /= peak;

    // visibilidade do relógio (fades por alfa do próprio relógio continuam funcionando)
    float vis = clamp(peak, 0.0, 1.0);

    // vetor gradiente contínuo (aponta para o interior), tamanho saturado em 1 = "proximidade da borda"
    float2 g = f1.xy + 0.5 * f2.xy;
    float gRef = 1.0 / (1.0 + bevelStrength);
    float2 v = g / gRef;
    float vl = length(v);
    float prox = min(vl, 1.0);
    float2 dirIn = v / max(vl, 1.0);                 // contínuo, zero no eixo central
    float2 dirU = v / max(vl, 0.0001);               // direção unitária (só com prox > 0)

    // curva nativa: d = circleMap(proximidade) * refractionAmount, amostrando para dentro
    float d = circleMap(prox) * refractionPx;
    float2 disp = (dirIn / max(prox, 0.0001)) * d;   // = dirU * d, sem salto em prox -> 0
    float2 outN = -dirU;                              // normal para fora, como no shader nativo

    float3 col;
    if (dot(chromAb, chromAb) > 0.0) {
        float4 acc = float4(0.0);
        float3 r3 = float3(0.0);
        float3 s0;
        s0 = wallpaperAt(p + disp * dispersionIntensity(outN, 3)); r3.r += s0.r / 3.5;
        s0 = wallpaperAt(p + disp * dispersionIntensity(outN, 2)); r3.r += s0.r / 3.5; r3.g += s0.g / 7.0;
        s0 = wallpaperAt(p + disp * dispersionIntensity(outN, 1)); r3.r += s0.r / 3.5; r3.g += s0.g / 3.5;
        s0 = wallpaperAt(p + disp * dispersionIntensity(outN, 0)); r3.g += s0.g / 3.5;
        s0 = wallpaperAt(p + disp * dispersionIntensity(outN, -1)); r3.g += s0.g / 3.5; r3.b += s0.b / 3.0;
        s0 = wallpaperAt(p + disp * dispersionIntensity(outN, -2)); r3.b += s0.b / 3.0;
        s0 = wallpaperAt(p + disp * dispersionIntensity(outN, -3)); r3.r += s0.r / 7.0; r3.b += s0.b / 3.0;
        col = r3;
    } else {
        col = wallpaperAt(p + disp);
    }
    float3 soft = float3(backdropBlur.eval(p + disp).rgb);
    col = mix(col, soft, frost);

    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(float3(lum), col, saturation), 0.0, 1.0);
    col *= 1.0 - dim;

    if (blendMode == 1) {
        col = mix(col, clamp(softLightBlend(col, baseColor.rgb), 0.0, 1.0), 0.7);
    } else if (blendMode == 2) {
        col = mix(col, clamp(overlayBlend(col, baseColor.rgb), 0.0, 1.0), 0.7);
    }

    // brilho de borda nativo: máscara de 2 px na borda do glifo + gradiente diagonal da view
    float edgeMask = 1.0 - smoothstep(0.55, 0.95, f3.z);
    float2 halfSize = max(size * 0.5, float2(1.0));
    float2 normalizedCoord = (p - halfSize) / halfSize;
    float diagonalPos = (normalizedCoord.x + normalizedCoord.y) / 1.414;
    float gradientIntensity = pow(min(abs(diagonalPos), 1.0), 1.0 / max(edgeConc, 0.01));
    float highlight = gradientIntensity * edgeMask * cov * edgeStrength;

    float3 screenBlend = col + float3(highlight) - col * float3(highlight);
    float brightness = dot(col, float3(0.333));
    col = mix(screenBlend, mix(col, float3(1.0), highlight), brightness * 0.7);

    // alfa da cor escolhida: 0 = vidro total, 1 = relógio sólido na cor escolhida (premultiplicado)
    float3 glassRgb = clamp(col, 0.0, 1.0) * cov;
    float3 solidRgb = tint.rgb * cov;
    float3 rgb = mix(glassRgb, solidRgb, tint.a) * vis;
    float a = cov * vis;
    return half4(half3(rgb), half(a));
}
"""

    fun configure(
        rs: RuntimeShader,
        density: Float,
        hasBackdrop: Boolean,
        refractionHeight: Float,
        refractionAmount: Float,
        chromaticAberration: Float,
        saturation: Float,
        edgeHighlight: Float,   // edgeHighlightStrength nativo (padrão 0.5)
        highlightConc: Float,   // highlightConcentration nativo (padrão 0.5)
        blurRadius: Float,
        baseColorArgb: Int,
        blendMode: Int,
        viewWidth: Float = 0f,
        viewHeight: Float = 0f,
        tintArgb: Int = 0x00FFFFFF,   // padrão: alfa 0 = vidro total (visual de antes)
    ) {
        val halfMin = if (viewWidth > 0f && viewHeight > 0f) minOf(viewWidth, viewHeight) / 2f else Float.MAX_VALUE
        rs.setFloatUniform("hasBackdrop", if (hasBackdrop) 1f else 0f)
        rs.setFloatUniform("size", maxOf(viewWidth, 1f), maxOf(viewHeight, 1f))
        // nativo: refractionHeight limitado à metade do menor lado
        rs.setFloatUniform(
            "bevelRadius",
            ((refractionHeight / 6f).coerceIn(1f, 24f) * density).coerceAtMost(halfMin).coerceAtLeast(1f)
        )
        rs.setFloatUniform("bevelStrength", (refractionAmount / 16f).coerceIn(0.3f, 8f))
        rs.setFloatUniform("refractionPx", (refractionAmount / 4f).coerceIn(0f, 60f) * density)
        rs.setFloatUniform("frost", (blurRadius / 40f).coerceIn(0f, 1f))
        rs.setFloatUniform("dim", 0.04f)
        rs.setFloatUniform("saturation", saturation)
        rs.setFloatUniform("bodyAlpha", (1f - 0.6f * (blurRadius / 100f)).coerceIn(0.3f, 1f))
        // nativo: chromaticAberration = (f, f) quando > 0. A escala do slider (0..100) é uma suposição.
        val ca = (chromaticAberration / 100f * 0.5f).coerceIn(0f, 1f)
        rs.setFloatUniform("chromAb", ca, ca)
        rs.setFloatUniform("edgeStrength", edgeHighlight.coerceIn(0f, 1f))
        rs.setFloatUniform("edgeConc", highlightConc.coerceIn(0.01f, 2f))
        rs.setFloatUniform("edgeWidth", 2f)
        rs.setFloatUniform(
            "baseColor",
            android.graphics.Color.red(baseColorArgb) / 255f,
            android.graphics.Color.green(baseColorArgb) / 255f,
            android.graphics.Color.blue(baseColorArgb) / 255f,
            android.graphics.Color.alpha(baseColorArgb) / 255f,
        )
        rs.setFloatUniform(
            "tint",
            android.graphics.Color.red(tintArgb) / 255f,
            android.graphics.Color.green(tintArgb) / 255f,
            android.graphics.Color.blue(tintArgb) / 255f,
            android.graphics.Color.alpha(tintArgb) / 255f,
        )
        rs.setIntUniform("blendMode", blendMode)
    }
}

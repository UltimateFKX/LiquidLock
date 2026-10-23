package com.guizin.lockscreenliquidglass

object LiquidGlassShader {
    val SIMPLE: String = """
uniform shader content;
uniform shader wallpaper;

uniform float2 size;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float2 chromaticAberration;
uniform float2 contentScale;
uniform float2 contentTranslate;
uniform float2 wallpaperOffset;
uniform float2 wallpaperScale;
uniform float saturation;
uniform float edgeHighlightStrength;
uniform float highlightConcentration;
uniform float4 baseColor;
uniform int blendMode;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRectangle(float2 coord, float2 halfSize, float4 radii) {
    float r = radiusAt(coord, radii);
    float2 innerHalfSize = halfSize - float2(r);
    float2 cornerCoord = abs(coord) - innerHalfSize;
    
    float outside = length(max(cornerCoord, 0.0)) - r;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRectangle(float2 coord, float2 halfSize, float4 radii) {
    float r = radiusAt(coord, radii);
    float2 innerHalfSize = halfSize - float2(r);
    float2 cornerCoord = abs(coord) - innerHalfSize;
    
    float insideCorner = step(0.0, min(cornerCoord.x, cornerCoord.y)); // 1 if in corner
    float xMajor = step(cornerCoord.y, cornerCoord.x); // 1 if x is major
    float2 gradEdge = float2(xMajor, 1.0 - xMajor);
    float2 gradCorner = normalize(cornerCoord);
    return sign(coord) * mix(gradEdge, gradCorner, insideCorner);
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

float dispersionIntensity(float2 normal, int n) {
    return 1.0 + dot(chromaticAberration, normal) * float(n) / 3.0;
}

float2 transformCoord(float2 coord) {
    return coord * contentScale + contentTranslate;
}

half4 adjustSaturation(half4 color, float saturation) {
    if(saturation == 0.0) {
        return color;
    }
    // Calculate luminance using Rec. 709 coefficients
    float luminance = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
    // Interpolate between grayscale and original color based on saturation
    half3 saturatedColor = mix(half3(luminance), color.rgb, saturation);
    return half4(saturatedColor, color.a);
}

half4 main(float2 coord) {
    half4 origContent = content.eval(transformCoord(coord));
    if (origContent.a < 0.5) {
        return half4(0.0);
    }
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float edgeProximity = clamp(1.0 - (origContent.a - 0.5) * 2.0, 0.0, 1.0);
    float aL = content.eval(transformCoord(coord - float2(1.0, 0.0))).a;
    float aR = content.eval(transformCoord(coord + float2(1.0, 0.0))).a;
    float aU = content.eval(transformCoord(coord - float2(0.0, 1.0))).a;
    float aD = content.eval(transformCoord(coord + float2(0.0, 1.0))).a;
    float2 grad = float2(aR - aL, aD - aU);
    float gradMag = length(grad);
    float2 normal = (gradMag > 0.001) ? normalize(grad) : float2(0.0, -1.0);
    if (edgeProximity < 0.05) {
        return adjustSaturation(wallpaper.eval((coord + wallpaperOffset) * wallpaperScale), saturation);
    }
    float sd = -edgeProximity;

    float4 maxGradRadius = float4(min(halfSize.x, halfSize.y));
    float4 gradRadius = min(cornerRadii * 1.5, maxGradRadius);
    // normal already computed from alpha gradient
    float d = circleMap(edgeProximity) * refractionAmount;
    float2 dir = normal;

    half4 color = half4(0.0);
    color.a = 0.0;

    half4 redColor = wallpaper.eval((coord + d * dispersionIntensity(dir, 3) * dir + wallpaperOffset) * wallpaperScale);
    color.r += redColor.r / 3.5;
    color.a += redColor.a / 7.0;

    half4 orangeColor = wallpaper.eval((coord + d * dispersionIntensity(dir, 2) * dir + wallpaperOffset) * wallpaperScale);
    color.r += orangeColor.r / 3.5;
    color.g += orangeColor.g / 7.0;
    color.a += orangeColor.a / 7.0;

    half4 yellowColor = wallpaper.eval((coord + d * dispersionIntensity(dir, 1) * dir + wallpaperOffset) * wallpaperScale);
    color.r += yellowColor.r / 3.5;
    color.g += yellowColor.g / 3.5;
    color.a += yellowColor.a / 7.0;

    half4 greenColor = wallpaper.eval((coord + d * dispersionIntensity(dir, 0) * dir + wallpaperOffset) * wallpaperScale);
    color.g += greenColor.g / 3.5;
    color.a += greenColor.a / 7.0;

    half4 cyanColor = wallpaper.eval((coord + d * dispersionIntensity(dir, -1) * dir + wallpaperOffset) * wallpaperScale);
    color.g += cyanColor.g / 3.5;
    color.b += cyanColor.b / 3.0;
    color.a += cyanColor.a / 7.0;

    half4 blueColor = wallpaper.eval((coord + d * dispersionIntensity(dir, -2) * dir + wallpaperOffset) * wallpaperScale);
    color.b += blueColor.b / 3.0;
    color.a += blueColor.a / 7.0;

    half4 purpleColor = wallpaper.eval((coord + d * dispersionIntensity(dir, -3) * dir + wallpaperOffset) * wallpaperScale);
    color.r += purpleColor.r / 7.0;
    color.b += purpleColor.b / 3.0;
    color.a += purpleColor.a / 7.0;

    color = adjustSaturation(color, saturation);

    // Add glass edge highlights
    // Calculate edge proximity (normalized 0-1, where 1 is at the edge)
    float edgeDistance = edgeProximity * 4.0;
    float edgeWidth = 2.0; // Width of the highlight edge in pixels

    // Only apply highlight within 2px of the edge
    float edgeMask = smoothstep(edgeWidth, 0.0, edgeDistance);

    // Calculate diagonal position from top-left to bottom-right
    // Normalize coordinates to -1 to 1 range
    float2 normalizedCoord = centeredCoord / halfSize;
    // Calculate position along the diagonal (top-left = -1, bottom-right = 1)
    float diagonalPos = (normalizedCoord.x + normalizedCoord.y) / 1.414; // divide by sqrt(2) for normalization

    // Create gradient: white at top-left (-1), transparent at middle (0), white at bottom-right (1)
    // abs(diagonalPos) gives us: 1 at corners, 0 in middle
    // We want: 1 (white) at corners, 0 (transparent) in middle
    // So we use (1 - abs) which gives: 0 at corners, 1 in middle
    // But we need the OPPOSITE for highlight intensity
    float distanceFromCenter = abs(diagonalPos); // 0 in middle, 1 at corners

    // Apply concentration control BEFORE inverting
    // Lower concentration values spread the highlight, higher values focus it at corners
    float gradientIntensity = pow(distanceFromCenter, 1.0 / max(highlightConcentration, 0.01));

    // Combine edge mask with gradient intensity and strength control
    float highlight = gradientIntensity * edgeMask * edgeHighlightStrength;

    // Apply highlight using a blend that works on both light and dark backgrounds
    // Use a combination of screen blend and direct addition for glass-like highlights
    // Screen blend: 1 - (1 - base) * (1 - highlight) = base + highlight - base * highlight
    half3 screenBlend = color.rgb + half3(highlight) - color.rgb * half3(highlight);

    // Mix between screen blend (for dark colors) and lerp towards white (for bright colors)
    float colorBrightness = dot(color.rgb, half3(0.333));
    half3 highlightedColor = mix(
        screenBlend, // Use screen blend for darker colors
        mix(color.rgb, half3(1.0), highlight), // Lerp to white for brighter colors
        colorBrightness * 0.7 // Transition based on brightness
    );

    color.rgb = highlightedColor;

    // Blend mode with base color (One UI clock color)
    if (blendMode == 1) {
        half3 base = baseColor.rgb;
        half3 result = color.rgb;
        for (int c = 0; c < 3; c++) {
            if (base[c] < 0.5) {
                result[c] = color.rgb[c] - (1.0 - 2.0 * base[c]) * color.rgb[c] * (1.0 - color.rgb[c]);
            } else {
                result[c] = color.rgb[c] + (2.0 * base[c] - 1.0) * (sqrt(color.rgb[c]) - color.rgb[c]);
            }
        }
        color.rgb = mix(color.rgb, result, 0.7);
    } else if (blendMode == 2) {
        half3 base = baseColor.rgb;
        half3 result = color.rgb;
        for (int c = 0; c < 3; c++) {
            if (base[c] < 0.5) {
                result[c] = 2.0 * base[c] * color.rgb[c];
            } else {
                result[c] = 1.0 - 2.0 * (1.0 - base[c]) * (1.0 - color.rgb[c]);
            }
        }
        color.rgb = mix(color.rgb, result, 0.7);
    }

    return color;
}"""

    val CHROMATIC: String = """
uniform shader content;
uniform shader wallpaper;

uniform float2 size;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float2 chromaticAberration;
uniform float2 contentScale;
uniform float2 contentTranslate;
uniform float2 wallpaperOffset;
uniform float2 wallpaperScale;
uniform float saturation;
uniform float edgeHighlightStrength;
uniform float highlightConcentration;
uniform float4 baseColor;
uniform int blendMode;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRectangle(float2 coord, float2 halfSize, float4 radii) {
    float r = radiusAt(coord, radii);
    float2 innerHalfSize = halfSize - float2(r);
    float2 cornerCoord = abs(coord) - innerHalfSize;

    float outside = length(max(cornerCoord, 0.0)) - r;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRectangle(float2 coord, float2 halfSize, float4 radii) {
    float r = radiusAt(coord, radii);
    float2 innerHalfSize = halfSize - float2(r);
    float2 cornerCoord = abs(coord) - innerHalfSize;

    float insideCorner = step(0.0, min(cornerCoord.x, cornerCoord.y)); // 1 if in corner
    float xMajor = step(cornerCoord.y, cornerCoord.x); // 1 if x is major
    float2 gradEdge = float2(xMajor, 1.0 - xMajor);
    float2 gradCorner = normalize(cornerCoord);
    return sign(coord) * mix(gradEdge, gradCorner, insideCorner);
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

float2 transformCoord(float2 coord) {
    return coord * contentScale + contentTranslate;
}

half4 adjustSaturation(half4 color, float saturation) {
    if(saturation == 0.0) {
        return color;
    }
    // Calculate luminance using Rec. 709 coefficients
    float luminance = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
    // Interpolate between grayscale and original color based on saturation
    half3 saturatedColor = mix(half3(luminance), color.rgb, saturation);
    return half4(saturatedColor, color.a);
}

half4 main(float2 coord) {
    half4 origContent = content.eval(transformCoord(coord));
    if (origContent.a < 0.5) {
        return half4(0.0);
    }
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float edgeProximity = clamp(1.0 - (origContent.a - 0.5) * 2.0, 0.0, 1.0);
    float aL = content.eval(transformCoord(coord - float2(1.0, 0.0))).a;
    float aR = content.eval(transformCoord(coord + float2(1.0, 0.0))).a;
    float aU = content.eval(transformCoord(coord - float2(0.0, 1.0))).a;
    float aD = content.eval(transformCoord(coord + float2(0.0, 1.0))).a;
    float2 grad = float2(aR - aL, aD - aU);
    float gradMag = length(grad);
    float2 normal = (gradMag > 0.001) ? normalize(grad) : float2(0.0, -1.0);
    if (edgeProximity < 0.05) {
        return adjustSaturation(wallpaper.eval((coord + wallpaperOffset) * wallpaperScale), saturation);
    }
    float sd = -edgeProximity;

    float4 maxGradRadius = float4(min(halfSize.x, halfSize.y));
    float4 gradRadius = min(cornerRadii * 1.5, maxGradRadius);
    // normal already computed from alpha gradient
    float d = circleMap(edgeProximity) * refractionAmount;
    float2 dir = normal;

    half4 color = wallpaper.eval((coord + d * dir + wallpaperOffset) * wallpaperScale);
    color = adjustSaturation(color, saturation);
    color.a += dot(chromaticAberration, chromaticAberration) * 0.0; // Keeps uniform consistent without dispersion

    // Add glass edge highlights
    // Calculate edge proximity (normalized 0-1, where 1 is at the edge)
    float edgeDistance = edgeProximity * 4.0;
    float edgeWidth = 2.0; // Width of the highlight edge in pixels

    // Only apply highlight within 2px of the edge
    float edgeMask = smoothstep(edgeWidth, 0.0, edgeDistance);

    // Calculate diagonal position from top-left to bottom-right
    // Normalize coordinates to -1 to 1 range
    float2 normalizedCoord = centeredCoord / halfSize;
    // Calculate position along the diagonal (top-left = -1, bottom-right = 1)
    float diagonalPos = (normalizedCoord.x + normalizedCoord.y) / 1.414; // divide by sqrt(2) for normalization

    // Create gradient: white at top-left (-1), transparent at middle (0), white at bottom-right (1)
    // abs(diagonalPos) gives us: 1 at corners, 0 in middle
    // We want: 1 (white) at corners, 0 (transparent) in middle
    // So we use (1 - abs) which gives: 0 at corners, 1 in middle
    // But we need the OPPOSITE for highlight intensity
    float distanceFromCenter = abs(diagonalPos); // 0 in middle, 1 at corners

    // Apply concentration control BEFORE inverting
    // Lower concentration values spread the highlight, higher values focus it at corners
    float gradientIntensity = pow(distanceFromCenter, 1.0 / max(highlightConcentration, 0.01));

    // Combine edge mask with gradient intensity and strength control
    float highlight = gradientIntensity * edgeMask * edgeHighlightStrength;

    // Apply highlight using a blend that works on both light and dark backgrounds
    // Use a combination of screen blend and direct addition for glass-like highlights
    // Screen blend: 1 - (1 - base) * (1 - highlight) = base + highlight - base * highlight
    half3 screenBlend = color.rgb + half3(highlight) - color.rgb * half3(highlight);

    // Mix between screen blend (for dark colors) and lerp towards white (for bright colors)
    float colorBrightness = dot(color.rgb, half3(0.333));
    half3 highlightedColor = mix(
        screenBlend, // Use screen blend for darker colors
        mix(color.rgb, half3(1.0), highlight), // Lerp to white for brighter colors
        colorBrightness * 0.7 // Transition based on brightness
    );

    color.rgb = highlightedColor;

    // Blend mode with base color (One UI clock color)
    if (blendMode == 1) {
        half3 base = baseColor.rgb;
        half3 result = color.rgb;
        for (int c = 0; c < 3; c++) {
            if (base[c] < 0.5) {
                result[c] = color.rgb[c] - (1.0 - 2.0 * base[c]) * color.rgb[c] * (1.0 - color.rgb[c]);
            } else {
                result[c] = color.rgb[c] + (2.0 * base[c] - 1.0) * (sqrt(color.rgb[c]) - color.rgb[c]);
            }
        }
        color.rgb = mix(color.rgb, result, 0.7);
    } else if (blendMode == 2) {
        half3 base = baseColor.rgb;
        half3 result = color.rgb;
        for (int c = 0; c < 3; c++) {
            if (base[c] < 0.5) {
                result[c] = 2.0 * base[c] * color.rgb[c];
            } else {
                result[c] = 1.0 - 2.0 * (1.0 - base[c]) * (1.0 - color.rgb[c]);
            }
        }
        color.rgb = mix(color.rgb, result, 0.7);
    }

    return color;
}"""
}

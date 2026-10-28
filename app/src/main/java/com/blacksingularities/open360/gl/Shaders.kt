package com.blacksingularities.open360.gl

/**
 * Shadery GLSL ES 3.00. Płótno panoramy jest równoprostokątne: wiersz 0 to zenit (góra obrazu),
 * s = 0 to długość −180°. Kierunki w układzie panoramy: X – wschód, Y – przód, Z – zenit.
 * Płótno może być podzielone na kafelki (16K) – shadery pracują na globalnych pikselach.
 */
object Shaders {
    val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private val COMMON = """
const float PI = 3.14159265358979;
vec3 dirFromUv(vec2 uv) {
    float lon = (uv.x - 0.5) * 2.0 * PI;
    float lat = (0.5 - uv.y) * PI;
    return vec3(cos(lat) * sin(lon), cos(lat) * cos(lon), sin(lat));
}
vec2 uvFromDir(vec3 d) {
    float lon = atan(d.x, d.y);
    float lat = asin(clamp(d.z, -1.0, 1.0));
    return vec2(lon / (2.0 * PI) + 0.5, 0.5 - lat / PI);
}
// Współrzędne obrazu pionowego (y w dół) → współrzędne bufora kamery obróconego o rot°.
vec2 camUv(vec2 uv, int rot) {
    if (rot == 90) return vec2(uv.y, 1.0 - uv.x);
    if (rot == 180) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (rot == 270) return vec2(1.0 - uv.y, uv.x);
    return uv;
}
// Kierunek panoramy → współrzędne kadru kamery (−1..1, y w górę); z > 0 – za kamerą.
vec3 camProject(mat3 panoToCam, vec2 camTan, vec3 d) {
    vec3 c = panoToCam * d;
    if (c.z >= -1e-4) return vec3(0.0, 0.0, 1.0);
    vec2 p = (c.xy / -c.z) / camTan;
    return vec3(p, -1.0);
}
// Kamera daje YUV (BT.601, pełny zakres) – zamiana na RGB na GPU zamiast na CPU.
vec3 yuv2rgb(float y, vec2 c) {
    float u = c.x - 0.5;
    float v = c.y - 0.5;
    return clamp(vec3(y + 1.402 * v, y - 0.344136 * u - 0.714136 * v, y + 1.772 * u), 0.0, 1.0);
}
"""

    /**
     * Zszywanie fragmentu płótna (kafelek → bufor roboczy). Reguła łączenia jak w
     * stitch/FrameModel.kt (Blend): waga = centralność × jakość klatki; nowa klatka wygrywa
     * tam, gdzie jej waga jest większa, z wąskim pasem przenikania; przy dużej różnicy treści
     * przejście jest ostre, żeby nie było duchów. Korekta jasności liniowo, migawka szczelinowa
     * odwracana przez prędkość kątową, próbkowanie kamery z mipmap (bez schodków przy 2K–4K).
     */
    val STITCH = """#version 300 es
precision highp float;
out vec4 outColor;
uniform sampler2D uTile;
uniform sampler2D uY;
uniform sampler2D uU;
uniform sampler2D uV;
uniform mat3 uPanoToCam;
uniform vec2 uCamTan;
uniform int uCamRot;
uniform vec2 uOrigin;
uniform vec2 uTileOrigin;
uniform vec2 uCanvasSize;
uniform float uGain;
uniform float uVignette;
uniform vec3 uOmega;
uniform float uSkew;
uniform float uQuality;
$COMMON
const float BAND = 0.1;
const float GHOST = 0.08;
void main() {
    vec2 gpx = gl_FragCoord.xy + uOrigin;
    vec4 old = texelFetch(uTile, ivec2(gpx - uTileOrigin), 0);
    vec3 c = uPanoToCam * dirFromUv(gpx / uCanvasSize);
    vec2 p = (c.xy / -min(c.z, -1e-4)) / uCamTan;
    vec2 b = camUv(vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5), uCamRot);
    // Wiersz odczytywany τ s od środka klatki widział świat obrócony o ω·τ.
    vec3 c2 = c - ((b.y - 0.5) * uSkew) * cross(uOmega, c);
    p = (c2.xy / -min(c2.z, -1e-4)) / uCamTan;
    b = camUv(vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5), uCamRot);
    vec2 bx = dFdx(b);
    vec2 by = dFdy(b);
    if (c.z > -1e-4 || c2.z > -1e-4 || abs(p.x) >= 1.0 || abs(p.y) >= 1.0) { outColor = old; return; }
    vec3 rgb = yuv2rgb(textureGrad(uY, b, bx, by).r, vec2(textureGrad(uU, b, bx, by).r, textureGrad(uV, b, bx, by).r));
    vec2 tn = p * uCamTan;
    float r2 = dot(tn, tn) / dot(uCamTan, uCamTan);
    vec3 lin = pow(rgb, vec3(2.2)) * (uGain * (1.0 + uVignette * r2));
    rgb = pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));
    float wN = max((1.0 - p.x * p.x) * (1.0 - p.y * p.y) * uQuality, 1.0 / 255.0);
    float t = 1.0;
    if (old.a > 0.0) {
        t = clamp((wN - old.a) / BAND + 0.5, 0.0, 1.0);
        float diff = abs(dot(rgb - old.rgb, vec3(0.299, 0.587, 0.114)));
        if (diff > GHOST) t = smoothstep(0.35, 0.65, t);
    }
    outColor = vec4(mix(old.rgb, rgb, t), mix(old.a, wN, t));
}
"""

    /** Pomniejszenie kafelka do podglądu (średnia z bloku, puste piksele nie przyciemniają). */
    val DOWN = """#version 300 es
precision highp float;
out vec4 outColor;
uniform sampler2D uTile;
uniform vec2 uTileOrigin;
uniform int uFactor;
void main() {
    ivec2 base = ivec2(floor(gl_FragCoord.xy)) * uFactor - ivec2(uTileOrigin);
    vec3 acc = vec3(0.0);
    float n = 0.0;
    float a = 0.0;
    for (int j = 0; j < uFactor; j++) for (int i = 0; i < uFactor; i++) {
        vec4 s = texelFetch(uTile, base + ivec2(i, j), 0);
        float p = s.a > 0.0 ? 1.0 : 0.0;
        acc += s.rgb * p;
        n += p;
        a += s.a;
    }
    float k = float(uFactor * uFactor);
    outColor = n > 0.0 ? vec4(acc / n, max(a / k, 1.0 / 255.0)) : vec4(0.0);
}
"""

    /** Widok: perspektywa, mała planeta, tunel albo płaska mapa; opcjonalnie kadr kamery na żywo. */
    val VIEW = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uPano;
uniform int uMode;
uniform mat3 uScreenToPano;
uniform vec2 uViewTan;
uniform vec2 uPan;
uniform float uLod;
uniform vec4 uCrop;
uniform bool uGrid;
uniform bool uFlipY;
uniform bool uHasCam;
uniform sampler2D uY;
uniform sampler2D uU;
uniform sampler2D uV;
uniform mat3 uPanoToCam;
uniform vec2 uCamTan;
uniform int uCamRot;
uniform float uLiveAlpha;
uniform vec4 uFrameColor;
$COMMON
vec3 background(vec3 d) {
    vec3 bg = vec3(0.055 + 0.03 * d.z);
    if (uGrid) {
        float lon = degrees(atan(d.x, d.y));
        float lat = degrees(asin(clamp(d.z, -1.0, 1.0)));
        vec2 g = vec2(lon, lat) / 15.0;
        vec2 f = abs(fract(g + 0.5) - 0.5) / max(fwidth(g), vec2(1e-4));
        float line = 1.0 - clamp(min(f.x, f.y) - 0.5, 0.0, 1.0);
        float horizon = 1.0 - clamp(abs(lat) / max(fwidth(lat), 1e-4) - 0.5, 0.0, 1.0);
        bg = mix(bg, vec3(0.22), line * 0.6);
        bg = mix(bg, vec3(0.5), horizon * 0.7);
    }
    return bg;
}
void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    if (uFlipY) ndc.y = -ndc.y;
    vec3 d;
    if (uMode == 0) {
        d = uScreenToPano * normalize(vec3(ndc * uViewTan, -1.0));
    } else if (uMode == 1 || uMode == 2) {
        vec2 p = ndc * uViewTan;
        float r2 = dot(p, p);
        if (uMode == 1) d = vec3(2.0 * p.x, 2.0 * p.y, r2 - 1.0) / (1.0 + r2);
        else d = vec3(-2.0 * p.x, 2.0 * p.y, 1.0 - r2) / (1.0 + r2);
        d = uScreenToPano * d;
    } else {
        float lon = ndc.x * uViewTan.x + uPan.x;
        float lat = ndc.y * uViewTan.y + uPan.y;
        if (abs(lat) > PI * 0.5) { outColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        d = vec3(cos(lat) * sin(lon), cos(lat) * cos(lon), sin(lat));
    }
    vec2 uv = uvFromDir(d);
    vec2 cuv = (uv - uCrop.xy) / uCrop.zw;
    vec3 col = background(d);
    if (cuv.x >= 0.0 && cuv.x <= 1.0 && cuv.y >= 0.0 && cuv.y <= 1.0) {
        vec4 s = textureLod(uPano, cuv, uLod);
        col = mix(col, s.rgb, smoothstep(0.0, 2.0 / 255.0, s.a));
    }
    vec3 p = camProject(uPanoToCam, uCamTan, d);
    float m = (uHasCam && p.z < 0.0) ? max(abs(p.x), abs(p.y)) : 10.0;
    float fw = min(fwidth(m), 0.05) * 2.2;
    if (m < 1.0) {
        vec2 b = camUv(vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5), uCamRot);
        vec3 c = yuv2rgb(textureLod(uY, b, 0.0).r, vec2(textureLod(uU, b, 0.0).r, textureLod(uV, b, 0.0).r));
        col = mix(col, c, uLiveAlpha);
    }
    if (m < 2.0) {
        float edge = smoothstep(1.0 - fw * 1.5, 1.0 - fw * 0.5, m) * (1.0 - smoothstep(1.0, 1.0 + fw * 0.5, m));
        col = mix(col, uFrameColor.rgb, edge * uFrameColor.a);
    }
    outColor = vec4(col, 1.0);
}
"""

    /** Źródło piramidy do wypełniania dziur: kolor z wagą obecności (0/1). */
    val PREMUL = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uCanvas;
void main() {
    vec4 c = texture(uCanvas, vUv);
    float a = smoothstep(0.0, 1.0 / 255.0, c.a);
    outColor = vec4(c.rgb * a, a);
}
"""

    /**
     * Eksport fragmentu kafelka: wypełnianie dziur metodą push-pull (od najgrubszego poziomu
     * piramidy do najdrobniejszego – gładko, bez bloków) i delikatne wyostrzanie (maska
     * nieostra 3×3 na luminancji z progiem, żeby nie wzmacniać szumu).
     */
    val EXPORT = """#version 300 es
precision highp float;
out vec4 outColor;
uniform sampler2D uTile;
uniform sampler2D uFill;
uniform vec2 uOrigin;
uniform vec2 uTileOrigin;
uniform vec2 uCanvasSize;
uniform float uMaxLod;
uniform float uSharpen;
vec3 fillAt(vec2 uv) {
    vec3 acc = vec3(0.5);
    bool have = false;
    for (int k = 0; k <= 13; k++) {
        float lod = uMaxLod - float(k);
        if (lod < 0.0) break;
        vec4 f = textureLod(uFill, uv, lod);
        if (f.a > 0.002) {
            vec3 c = f.rgb / f.a;
            acc = have ? mix(acc, c, smoothstep(0.0, 1.0, f.a)) : c;
            have = true;
        }
    }
    return acc;
}
void main() {
    vec2 g = gl_FragCoord.xy + uOrigin;
    ivec2 t = ivec2(g - uTileOrigin);
    ivec2 lim = textureSize(uTile, 0) - 1;
    vec4 c = texelFetch(uTile, t, 0);
    if (c.a <= 0.0) { outColor = vec4(fillAt(g / uCanvasSize), 1.0); return; }
    vec3 blur = c.rgb * 4.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
        if (i == 0 && j == 0) continue;
        vec4 n = texelFetch(uTile, clamp(t + ivec2(i, j), ivec2(0), lim), 0);
        float w = (i == 0 || j == 0) ? 2.0 : 1.0;
        blur += (n.a > 0.0 ? n.rgb : c.rgb) * w;
    }
    blur /= 16.0;
    vec3 detail = c.rgb - blur;
    float dl = abs(dot(detail, vec3(0.299, 0.587, 0.114)));
    float k = uSharpen * smoothstep(0.004, 0.02, dl);
    outColor = vec4(clamp(c.rgb + k * detail, 0.0, 1.0), 1.0);
}
"""
}

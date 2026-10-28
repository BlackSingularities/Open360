package com.blacksingularities.open360.gl

/**
 * Shadery GLSL ES 3.00. Płótno panoramy jest równoprostokątne: t = 0 to zenit (góra obrazu),
 * s = 0 to długość −180°. Kierunki w układzie panoramy: X – wschód, Y – przód, Z – zenit.
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
"""

    /**
     * Zszywanie: każdy piksel płótna w zakresie klatki łączy dotychczasowy kolor z kolorem
     * z kamery. Kanał alfa przechowuje wagę (odległość od brzegu kadru – jak w OpenPano).
     */
    val STITCH = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uCanvas;
uniform sampler2D uCam;
uniform mat3 uPanoToCam;
uniform vec2 uCamTan;
uniform int uCamRot;
uniform vec2 uCanvasSize;
uniform int uBlend;
uniform float uFeather;
uniform float uGain;
uniform float uVignette;
$COMMON
void main() {
    vec2 uv = gl_FragCoord.xy / uCanvasSize;
    vec4 old = texture(uCanvas, uv);
    vec3 p = camProject(uPanoToCam, uCamTan, dirFromUv(uv));
    if (p.z > 0.0 || abs(p.x) >= 1.0 || abs(p.y) >= 1.0) { outColor = old; return; }
    vec2 cuv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
    vec3 c = texture(uCam, camUv(cuv, uCamRot)).rgb;
    float r2 = dot(p.xy, p.xy) * 0.5;
    c *= (1.0 + uVignette * r2) * uGain;
    c = clamp(c, 0.0, 1.0);
    float e = 1.0 - max(abs(p.x), abs(p.y));
    float w = clamp(e / max(uFeather, 0.02), 0.0, 1.0);
    w = max(w, 1.0 / 255.0);
    vec3 col;
    float a;
    if (uBlend == 1) {
        // Miękkie przenikanie (średnia ważona).
        float tot = old.a + w;
        col = (old.rgb * old.a + c * w) / tot;
        a = min(1.0, tot);
    } else if (uBlend == 0) {
        // Najostrzejsza: wygrywa klatka, w której piksel był bliżej środka kadru.
        float t = clamp((w - old.a) * 6.0, 0.0, 1.0);
        col = mix(old.rgb, c, t);
        a = max(old.a, w);
    } else if (uBlend == 2) {
        // Tylko puste miejsca.
        float t = old.a < 0.004 ? 1.0 : w * (1.0 - old.a);
        col = mix(old.rgb, c, t);
        a = max(old.a, w);
    } else {
        col = mix(old.rgb, c, w);
        a = max(old.a, w);
    }
    outColor = vec4(col, a);
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
uniform sampler2D uCam;
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
        col = mix(col, s.rgb, smoothstep(0.0, 0.05, s.a));
    }
    vec3 p = camProject(uPanoToCam, uCamTan, d);
    float m = (uHasCam && p.z < 0.0) ? max(abs(p.x), abs(p.y)) : 10.0;
    float fw = min(fwidth(m), 0.05) * 2.2;
    if (m < 1.0) {
        vec3 c = texture(uCam, camUv(vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5), uCamRot)).rgb;
        col = mix(col, c, uLiveAlpha);
    }
    if (m < 2.0) {
        float edge = smoothstep(1.0 - fw * 1.5, 1.0 - fw * 0.5, m) * (1.0 - smoothstep(1.0, 1.0 + fw * 0.5, m));
        col = mix(col, uFrameColor.rgb, edge * uFrameColor.a);
    }
    outColor = vec4(col, 1.0);
}
"""

    /** Kopia płótna z wagą przemnożoną – źródło piramidy do wypełniania dziur. */
    val PREMUL = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uCanvas;
void main() {
    vec4 c = texture(uCanvas, vUv);
    float a = smoothstep(0.0, 0.05, c.a);
    outColor = vec4(c.rgb * a, a);
}
"""

    /** Eksport pasami: kadr płótna + wypełnienie dziur (rozmyte z piramidy albo kolorem). */
    val EXPORT = """#version 300 es
precision highp float;
out vec4 outColor;
uniform sampler2D uCanvas;
uniform sampler2D uFill;
uniform int uFillMode;
uniform vec3 uFillColor;
uniform vec2 uFullSize;
uniform vec2 uOffset;
uniform float uMaxLod;
void main() {
    vec2 px = gl_FragCoord.xy + uOffset;
    vec2 uv = px / uFullSize;
    uv.x = fract(uv.x);
    vec4 c = texture(uCanvas, uv);
    vec3 fill = uFillColor;
    if (uFillMode == 0) {
        for (float lod = 1.0; lod <= 12.0; lod += 1.0) {
            if (lod > uMaxLod) break;
            vec4 f = textureLod(uFill, uv, lod);
            if (f.a > 0.04) {
                vec4 f2 = textureLod(uFill, uv, min(lod + 1.0, uMaxLod));
                vec3 a = f.rgb / f.a;
                vec3 b = f2.a > 0.0 ? f2.rgb / f2.a : a;
                fill = mix(a, b, 0.5);
                break;
            }
        }
    }
    float a = smoothstep(0.0, 0.05, c.a);
    outColor = vec4(mix(fill, c.rgb, a), 1.0);
}
"""
}

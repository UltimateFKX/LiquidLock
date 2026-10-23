package com.guizin.lockscreenliquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Liquid glass nos relógios da tela de bloqueio/AOD da One UI 8 (UN1CA).
 *
 * Todas as views de relógio vêm do plugin do app com.samsung.android.app.aodservice
 * (UfClockProvider -> AbsClockView e subclasses). Esse plugin é carregado DENTRO da SystemUI
 * por um PathClassLoader próprio, então o classLoader do onPackageReady não enxerga as classes.
 * Por isso o hook entra em PluginInstance.PluginFactory.createPlugin, pega o classLoader do
 * plugin recém-criado e instala os hooks em AbsClockView uma vez por classLoader.
 *
 * O efeito é um RenderEffect (RuntimeShader) aplicado na própria view do relógio, lendo os
 * pixels já desenhados. Assim vale para fonte padrão, fonte externa e relógios animados.
 */
class ModuleMain : XposedModule() {
    companion object {
        const val TAG = "LSLiquidGlass"
        const val SYSTEMUI = "com.android.systemui"
        const val AOD_PKG = "com.samsung.android.app.aodservice"
        const val ABS_CLOCK_VIEW = "com.samsung.android.uniform.widget.clock.AbsClockView"
        const val PLUGIN_FACTORY = "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
        const val PLUGIN_INSTANCE = "com.android.systemui.shared.plugins.PluginInstance"
    }

    // Parâmetros (mesmas chaves do MainActivity)
    @Volatile var blurRadius = 20f
    @Volatile var refractionHeight = 40f
    @Volatile var refractionAmount = 40f
    @Volatile var chromaticAberration = 0f
    @Volatile var saturation = 1.0f
    @Volatile var edgeHighlight = 0.5f
    @Volatile var highlightConc = 0.5f
    // Novas chaves (sem UI ainda): lock ligado, AOD desligado por padrão (bateria)
    @Volatile var applyOnLock = true
    @Volatile var applyOnAod = false
    @Volatile var useBackdrop = true
    @Volatile var baseColorArgb = 0xFFFFFFFF.toInt()
    @Volatile var blendMode = 0
    // cor do relógio escolhida no app (ARGB): alfa 0 = vidro total, alfa 255 = relógio sólido nessa cor
    @Volatile var clockColorArgb = 0x00FFFFFF

    @Volatile private var prefsGen = 0
    @Volatile private var lastPrefsLoad = 0L

    private class GlassState(val shader: RuntimeShader, val aod: Boolean) {
        var w = 0
        var h = 0
        var gen = -1
        var applied = false
        var x = Int.MIN_VALUE
        var y = Int.MIN_VALUE
        var bdKey = 0
        var observer: ViewTreeObserver? = null
    }

    private val dummy: BitmapShader by lazy {
        BitmapShader(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
    }

    private val states: MutableMap<View, GlassState> =
        Collections.synchronizedMap(WeakHashMap<View, GlassState>())
    private val hookedLoaders: MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            SYSTEMUI -> {
                log(Log.INFO, TAG, "onPackageReady: systemui")
                hookPluginLoading(param.classLoader)
            }
            // processo do próprio aodservice (previews): as classes estão no classLoader normal
            AOD_PKG -> {
                log(Log.INFO, TAG, "onPackageReady: aodservice")
                installClockHooks(param.classLoader)
            }
        }
    }

    // ---- 1) capturar o classLoader do plugin quando a SystemUI o carrega ----

    private fun hookPluginLoading(systemUiLoader: ClassLoader) {
        var hooked = false
        try {
            val factory = Class.forName(PLUGIN_FACTORY, false, systemUiLoader)
            val m = factory.declaredMethods.first { it.name == "createPlugin" && it.parameterCount == 1 }
            hook(m).intercept { chain ->
                val plugin = chain.proceed()
                onPluginCreated(plugin)
                plugin
            }
            hooked = true
            log(Log.INFO, TAG, "Hooked PluginFactory.createPlugin")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "createPlugin hook failed: ${t.message}")
        }
        if (hooked) return
        // fallback: PluginInstance.loadPlugin() e ler getPlugin()
        try {
            val inst = Class.forName(PLUGIN_INSTANCE, false, systemUiLoader)
            val load = inst.declaredMethods.first { it.name == "loadPlugin" && it.parameterCount == 0 }
            hook(load).intercept { chain ->
                val r = chain.proceed()
                try {
                    val getPlugin = chain.thisObject!!.javaClass.getMethod("getPlugin")
                    onPluginCreated(getPlugin.invoke(chain.thisObject))
                } catch (_: Throwable) {}
                r
            }
            log(Log.INFO, TAG, "Hooked PluginInstance.loadPlugin (fallback)")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "nenhum hook de plugin funcionou: ${t.message}")
        }
    }

    private fun onPluginCreated(plugin: Any?) {
        if (plugin == null) return
        try {
            val loader = plugin.javaClass.classLoader ?: return
            if (hookedLoaders.contains(loader)) return
            installClockHooks(loader)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "onPluginCreated falhou: ${t.message}")
        }
    }

    // ---- 2) hooks no AbsClockView (base de todos os relógios) ----

    private fun installClockHooks(loader: ClassLoader) {
        val abs = try {
            Class.forName(ABS_CLOCK_VIEW, false, loader)
        } catch (_: ClassNotFoundException) {
            return // plugin que não é o do relógio
        }
        if (!hookedLoaders.add(loader)) return
        try {
            val p = Int::class.javaPrimitiveType
            // final em AbsClockView: toda subclasse passa por aqui
            val onLayout = abs.getDeclaredMethod(
                "onLayout", Boolean::class.javaPrimitiveType, p, p, p, p
            )
            hook(onLayout).intercept { chain ->
                val r = chain.proceed()
                (chain.thisObject as? View)?.let { applyEffect(it) }
                r
            }
            log(Log.INFO, TAG, "Hooked AbsClockView.onLayout (loader=$loader)")
        } catch (t: Throwable) {
            hookedLoaders.remove(loader)
            log(Log.ERROR, TAG, "install hooks falhou: ${t.message}")
        }
    }

    // ---- 3) aplicar o efeito ----

    private fun isAod(view: View): Boolean = try {
        view.javaClass.getMethod("getCategory").invoke(view)?.toString() == "AOD"
    } catch (_: Throwable) {
        false
    }

    private fun applyEffect(view: View) {
        if (Build.VERSION.SDK_INT < 33) return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        try {
            loadPrefsThrottled(view.context)
            var st = states[view]
            if (st == null) {
                st = GlassState(RuntimeShader(GlassTextShader.SRC), isAod(view))
                states[view] = st
            }
            val enabled = if (st.aod) applyOnAod else applyOnLock
            if (!enabled) {
                if (st.applied) {
                    view.setRenderEffect(null)
                    st.applied = false
                    st.gen = -1
                }
                return
            }

            // acompanhar movimento do relógio (translação/animações não chamam onLayout)
            val vto = view.viewTreeObserver
            if (st.observer !== vto && vto.isAlive) {
                st.observer = vto
                vto.addOnPreDrawListener {
                    applyEffect(view)
                    true
                }
            }
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)

            var bd: WallpaperBackdrop.Data? = null
            if (useBackdrop && !st.aod) {
                bd = WallpaperBackdrop.peek()
                WallpaperBackdrop.requestAsync(view.context) {
                    prefsGen++
                    view.post { applyEffect(view) }
                }
            }
            val bdKey = bd?.key ?: 0

            if (st.applied && st.w == w && st.h == h && st.gen == prefsGen &&
                st.bdKey == bdKey && abs(st.x - loc[0]) < 1 && abs(st.y - loc[1]) < 1
            ) return

            if (bd != null) {
                val root = view.rootView
                val dm = view.resources.displayMetrics
                val sw = if (root.width > 0) root.width else dm.widthPixels
                val sh = if (root.height > 0) root.height else dm.heightPixels
                st.shader.setInputShader(
                    "backdrop", WallpaperBackdrop.shaderFor(bd.sharp, sw, sh, loc[0], loc[1])
                )
                st.shader.setInputShader(
                    "backdropBlur", WallpaperBackdrop.shaderFor(bd.blur, sw, sh, loc[0], loc[1])
                )
            } else {
                st.shader.setInputShader("backdrop", dummy)
                st.shader.setInputShader("backdropBlur", dummy)
            }
            GlassTextShader.configure(
                st.shader,
                view.resources.displayMetrics.density,
                hasBackdrop = useBackdrop && !st.aod,
                refractionHeight = refractionHeight,
                refractionAmount = refractionAmount,
                chromaticAberration = chromaticAberration,
                saturation = saturation,
                edgeHighlight = edgeHighlight,
                highlightConc = highlightConc,
                blurRadius = blurRadius,
                baseColorArgb = baseColorArgb,
                blendMode = blendMode,
                viewWidth = w.toFloat(),
                viewHeight = h.toFloat(),
                tintArgb = clockColorArgb,
            )
            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(st.shader, "contents"))
            st.w = w
            st.h = h
            st.gen = prefsGen
            st.bdKey = bdKey
            st.x = loc[0]
            st.y = loc[1]
            st.applied = true
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "applyEffect falhou: ${t.message}")
        }
    }

    // ---- prefs (mesmo mecanismo do módulo anterior, com throttle) ----

    private fun prefsKey(): Int = listOf(
        blurRadius, refractionHeight, refractionAmount, chromaticAberration, saturation,
        edgeHighlight, highlightConc, applyOnLock, applyOnAod, useBackdrop, clockColorArgb
    ).hashCode()

    private fun loadPrefsThrottled(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrefsLoad < 3000) return
        lastPrefsLoad = now
        val before = prefsKey()
        // Prefer world-readable file (works before device unlock and across processes).
        try {
            val f = java.io.File("/data/local/tmp/lsliquidglass.txt")
            if (f.exists() && f.canRead()) {
                f.readLines().forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        when (key) {
                            "blurRadius" -> blurRadius = value.toFloatOrNull() ?: blurRadius
                            "refractionHeight" -> refractionHeight = value.toFloatOrNull() ?: refractionHeight
                            "refractionAmount" -> refractionAmount = value.toFloatOrNull() ?: refractionAmount
                            "chromaticAberration" -> chromaticAberration = value.toFloatOrNull() ?: chromaticAberration
                            "saturation" -> saturation = value.toFloatOrNull() ?: saturation
                            "edgeHighlight" -> edgeHighlight = value.toFloatOrNull() ?: edgeHighlight
                            "highlightConc" -> highlightConc = value.toFloatOrNull() ?: highlightConc
                            "applyOnLock" -> applyOnLock = value.toBooleanStrictOrNull() ?: applyOnLock
                            "applyOnAod" -> applyOnAod = value.toBooleanStrictOrNull() ?: applyOnAod
                            "useBackdrop" -> useBackdrop = value.toBooleanStrictOrNull() ?: useBackdrop
                            "baseColor" -> baseColorArgb = value.toIntOrNull() ?: baseColorArgb
                            "blendMode" -> blendMode = value.toIntOrNull() ?: blendMode
                            "clockColor" -> clockColorArgb = value.toIntOrNull() ?: clockColorArgb
                        }
                    }
                }
                if (prefsKey() != before) prefsGen++
                return
            }
        } catch (_: Throwable) {}
        // Fallback to SharedPreferences (only available after unlock).
        try {
            val appCtx = context.createPackageContext(
                "com.guizin.lockscreenliquidglass", Context.CONTEXT_IGNORE_SECURITY
            )
            val sp = appCtx.getSharedPreferences("lockscreen_liquidglass", Context.MODE_PRIVATE)
            blurRadius = sp.getFloat("blurRadius", blurRadius)
            refractionHeight = sp.getFloat("refractionHeight", refractionHeight)
            refractionAmount = sp.getFloat("refractionAmount", refractionAmount)
            chromaticAberration = sp.getFloat("chromaticAberration", chromaticAberration)
            saturation = sp.getFloat("saturation", saturation)
            edgeHighlight = sp.getFloat("edgeHighlight", edgeHighlight)
            highlightConc = sp.getFloat("highlightConc", highlightConc)
            applyOnLock = sp.getBoolean("applyOnLock", applyOnLock)
            applyOnAod = sp.getBoolean("applyOnAod", applyOnAod)
            useBackdrop = sp.getBoolean("useBackdrop", useBackdrop)
            baseColorArgb = sp.getInt("baseColor", baseColorArgb)
            blendMode = sp.getInt("blendMode", blendMode)
            clockColorArgb = sp.getInt("clockColor", clockColorArgb)
        } catch (_: Throwable) {
            // sem acesso às prefs: mantém os valores atuais/padrão
        }
        if (prefsKey() != before) prefsGen++
    }
}

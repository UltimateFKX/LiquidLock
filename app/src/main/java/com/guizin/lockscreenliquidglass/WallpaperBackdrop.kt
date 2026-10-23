package com.guizin.lockscreenliquidglass

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Obtém o wallpaper da tela de bloqueio (nítido + versão borrada) para o shader refratar.
 * A decodificação roda em thread separada; enquanto não termina, o módulo usa o visual sem fundo.
 */
object WallpaperBackdrop {
    class Data(val sharp: Bitmap, val blur: Bitmap, val key: Int)

    @Volatile private var cached: Data? = null
    @Volatile private var lastCheck = 0L
    private val loading = AtomicBoolean(false)

    fun peek(): Data? = cached

    /** Dispara (no máximo 1 por vez, e no máximo a cada 5 s) a carga/validação do wallpaper. */
    fun requestAsync(ctx: Context, onReady: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck < 5000) return
        if (!loading.compareAndSet(false, true)) return
        lastCheck = now
        val app = ctx.applicationContext ?: ctx
        Thread {
            try {
                val before = cached?.key
                load(app)
                if (cached?.key != before) onReady()
            } catch (_: Throwable) {
            } finally {
                loading.set(false)
            }
        }.start()
    }

    /** Carrega (síncrono) o wallpaper atual e guarda em cache; usado também pelo app de configuração. */
    fun load(ctx: Context) {
        val wm = WallpaperManager.getInstance(ctx)
        var which = WallpaperManager.FLAG_LOCK
        var pfd = wm.getWallpaperFile(which)
        if (pfd == null) { // lock usa o mesmo wallpaper da home
            which = WallpaperManager.FLAG_SYSTEM
            pfd = wm.getWallpaperFile(which)
        }
        if (pfd == null) return
        val key = which * 1_000_003 + wm.getWallpaperId(which)
        if (cached?.key == key) { pfd.close(); return }
        pfd.use {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sharp = BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, opts) ?: return
            cached = fromBitmap(sharp, key)
        }
    }

    /** Shader do bitmap já posicionado em coordenadas locais da view (cobre a tela, center-crop). */
    fun shaderFor(bmp: Bitmap, screenW: Int, screenH: Int, viewX: Int, viewY: Int): BitmapShader {
        val s = max(screenW.toFloat() / bmp.width, screenH.toFloat() / bmp.height)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate((screenW - bmp.width * s) / 2f - viewX, (screenH - bmp.height * s) / 2f - viewY)
        val bs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        bs.setLocalMatrix(m)
        return bs
    }

    /** Monta o par nítido + borrado a partir de qualquer bitmap (blur barato: reduções sucessivas por 2 até ~1/16). */
    fun fromBitmap(sharp: Bitmap, key: Int): Data {
        var soft = sharp
        var f = 1
        while (f < 16 && soft.width > 8 && soft.height > 8) {
            soft = Bitmap.createScaledBitmap(soft, soft.width / 2, soft.height / 2, true)
            f *= 2
        }
        return Data(sharp, soft, key)
    }
}

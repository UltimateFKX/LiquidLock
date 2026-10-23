package com.guizin.lockscreenliquidglass

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Estado + persistência (mesmas chaves/arquivo de antes: o ModuleMain continua lendo igual)
// ---------------------------------------------------------------------------------------------

private const val PREFS_NAME = "lockscreen_liquidglass"
private const val STATE_FILE = "/data/local/tmp/lsliquidglass.txt"
private const val ONE_UI_BASE_COLOR = 0xFFB6C5FF.toInt()
private const val DEFAULT_BASE_COLOR = 0xFFFFFFFF.toInt()

data class GlassUiState(
    val blurRadius: Float = 20f,
    val refractionHeight: Float = 40f,
    val refractionAmount: Float = 40f,
    val chromaticAberration: Float = 0f,
    // Sem controle na UI nova: ficam com o valor salvo/padrão (o shader usa os padrões nativos).
    val saturation: Float = 1f,
    val edgeHighlight: Float = 0.5f,
    val highlightConc: Float = 0.5f,
    val blendMode: Int = 0,
    val baseColorArgb: Int = DEFAULT_BASE_COLOR,
    val useOneUIColor: Boolean = true,
    val applyOnLock: Boolean = true,
    val applyOnAod: Boolean = false,
    val useBackdrop: Boolean = true,
    // cor do relógio (ARGB): alfa 0 = vidro total, alfa 255 = relógio sólido nessa cor
    val clockColorArgb: Int = 0x00FFFFFF,
)

private fun GlassUiState.resolvedBaseColorArgb(): Int = if (useOneUIColor) ONE_UI_BASE_COLOR else baseColorArgb

private object GlassUiStore {
    fun load(context: Context): GlassUiState {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val d = GlassUiState()
        return GlassUiState(
            blurRadius = sp.getFloat("blurRadius", d.blurRadius),
            refractionHeight = sp.getFloat("refractionHeight", d.refractionHeight),
            refractionAmount = sp.getFloat("refractionAmount", d.refractionAmount),
            chromaticAberration = sp.getFloat("chromaticAberration", d.chromaticAberration),
            saturation = sp.getFloat("saturation", d.saturation),
            edgeHighlight = sp.getFloat("edgeHighlight", d.edgeHighlight),
            highlightConc = sp.getFloat("highlightConc", d.highlightConc),
            blendMode = 0,
            baseColorArgb = sp.getInt("baseColor", d.baseColorArgb),
            useOneUIColor = sp.getBoolean("useOneUIColor", d.useOneUIColor),
            applyOnLock = sp.getBoolean("applyOnLock", d.applyOnLock),
            applyOnAod = sp.getBoolean("applyOnAod", d.applyOnAod),
            useBackdrop = sp.getBoolean("useBackdrop", d.useBackdrop),
            clockColorArgb = sp.getInt("clockColor", d.clockColorArgb),
        )
    }

    fun save(context: Context, state: GlassUiState) {
        val resolvedBaseColor = state.resolvedBaseColorArgb()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("blurRadius", state.blurRadius)
            .putFloat("refractionHeight", state.refractionHeight)
            .putFloat("refractionAmount", state.refractionAmount)
            .putFloat("chromaticAberration", state.chromaticAberration)
            .putFloat("saturation", state.saturation)
            .putFloat("edgeHighlight", state.edgeHighlight)
            .putFloat("highlightConc", state.highlightConc)
            .putInt("blendMode", state.blendMode)
            .putInt("baseColor", resolvedBaseColor)
            .putBoolean("useOneUIColor", state.useOneUIColor)
            .putBoolean("applyOnLock", state.applyOnLock)
            .putBoolean("applyOnAod", state.applyOnAod)
            .putBoolean("useBackdrop", state.useBackdrop)
            .putInt("clockColor", state.clockColorArgb)
            .apply()

        try {
            val file = java.io.File(STATE_FILE)
            file.writeText(
                buildString {
                    appendLine("blurRadius=${state.blurRadius}")
                    appendLine("useOneUIColor=${state.useOneUIColor}")
                    appendLine("refractionHeight=${state.refractionHeight}")
                    appendLine("refractionAmount=${state.refractionAmount}")
                    appendLine("chromaticAberration=${state.chromaticAberration}")
                    appendLine("saturation=${state.saturation}")
                    appendLine("edgeHighlight=${state.edgeHighlight}")
                    appendLine("highlightConc=${state.highlightConc}")
                    appendLine("blendMode=${state.blendMode}")
                    appendLine("baseColor=$resolvedBaseColor")
                    appendLine("applyOnLock=${state.applyOnLock}")
                    appendLine("applyOnAod=${state.applyOnAod}")
                    appendLine("useBackdrop=${state.useBackdrop}")
                    appendLine("clockColor=${state.clockColorArgb}")
                }
            )
            file.setReadable(true, false)
        } catch (_: Throwable) {
            // Caminho opcional: sem acesso a /data/local/tmp, as prefs continuam valendo.
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sliders do design: label, faixa e como mapear no estado
// ---------------------------------------------------------------------------------------------

private class SliderSpec(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val get: (GlassUiState) -> Float,
    val set: (GlassUiState, Float) -> GlassUiState,
    // posições Y no frame do Figma (1080 x 3120)
    val labelY: Float,
    val sliderY: Float,
    val valueY: Float,
)

private val SLIDERS = listOf(
    SliderSpec("Blur Radius", 0f..100f, { it.blurRadius }, { s, v -> s.copy(blurRadius = v) }, 643f, 698f, 711f),
    SliderSpec("Refraction", 0f..180f, { it.refractionAmount }, { s, v -> s.copy(refractionAmount = v) }, 791f, 846f, 859f),
    // "Depth" do design = refractionHeight (largura/espessura do bisel da refração)
    SliderSpec("Depth", 0f..180f, { it.refractionHeight }, { s, v -> s.copy(refractionHeight = v) }, 939f, 994f, 1007f),
    SliderSpec(
        "Chromatic Aberration", 0f..100f,
        { it.chromaticAberration }, { s, v -> s.copy(chromaticAberration = v) }, 1087f, 1142f, 1155f
    ),
)

/** Valor máximo aceito ao digitar um valor customizado (o shader satura internamente antes disso). */
private const val CUSTOM_MAX = 1000f

// ---------------------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.dark(transparent),
        )
        setContent {
            val ctx = LocalContext.current
            // Material You: cores do wallpaper (Android 12+); abaixo disso, esquema escuro padrão.
            val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else darkColorScheme()
            MaterialTheme(colorScheme = scheme) {
                var ui by remember { mutableStateOf(GlassUiStore.load(ctx)) }
                // Salva sozinho, um pouco depois da última mudança (o design não tem botão Salvar).
                LaunchedEffect(ui) {
                    delay(300)
                    withContext(Dispatchers.IO) { GlassUiStore.save(ctx, ui) }
                }
                LiquidClockScreen(state = ui, onChange = { ui = it })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tela (coordenadas do Figma: frame de 1080 x 3577, escalado pela largura da tela)
// ---------------------------------------------------------------------------------------------

@Composable
private fun LiquidClockScreen(state: GlassUiState, onChange: (GlassUiState) -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val primaryArgb = scheme.primary.toArgb()
    val tertiaryArgb = scheme.tertiary.toArgb()

    // Foto de fundo (os retângulos #FF0000 do design). Enquanto carrega, não mostra nada.
    val backdrop by produceState<WallpaperBackdrop.Data?>(null, ctx) {
        value = loadBackdrop(ctx, primaryArgb, tertiaryArgb)
    }

    // índice do slider cujo valor está sendo editado no diálogo
    var editing by remember { mutableStateOf<Int?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface)
    ) {
        val sc: Dp = maxWidth / 1080f                       // dp por unidade do design
        val unitPx = with(density) { sc.toPx() }            // px por unidade do design
        fun d(v: Float): Dp = sc * v
        fun sp(v: Float): TextUnit = with(density) { (sc * v).toSp() }
        fun px(v: Float): Float = v * unitPx

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(d(3577f))
            ) {
                // ---- Cartão do título: foto + "Liquid Clock" com o shader (retângulo r=66 em 26,153) ----
                PhotoBox(
                    data = backdrop,
                    modifier = Modifier
                        .offset(d(26f), d(153f))
                        .size(d(1028f), d(394f))
                        .clip(RoundedCornerShape(d(66f)))
                )
                GlassLayer(
                    modifier = Modifier
                        .offset(d(254f), d(161f))
                        .size(d(536f), d(387f)),
                    state = state,
                    data = backdrop,
                    photoW = px(1028f).roundToInt(),
                    photoH = px(394f).roundToInt(),
                    offX = px(254f - 26f).roundToInt(),
                    offY = px(161f - 153f).roundToInt(),
                    density = density.density,
                ) {
                    // Texto sempre opaco: a transparência do relógio é que controla o vidro no shader.
                    Text(
                        text = "Liquid\nClock",
                        color = Color.White,
                        fontSize = sp(160f),
                        lineHeight = sp(193f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                    )
                }

                // ---- Sliders ----
                SLIDERS.forEachIndexed { i, spec ->
                    Text(
                        text = spec.label,
                        color = scheme.onSurface,
                        fontSize = sp(50f),
                        lineHeight = sp(60f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.offset(d(47f), d(spec.labelY - 3.5f)),
                    )
                    GlassSlider(
                        value = spec.get(state),
                        range = spec.range,
                        sc = sc,
                        onChange = { onChange(spec.set(state, it)) },
                        modifier = Modifier
                            .offset(d(47f), d(spec.sliderY))
                            .size(d(621f), d(77f)),
                    )
                    // "%Value% DP" — toque para digitar um valor customizado
                    Box(
                        modifier = Modifier
                            .offset(d(710f), d(spec.valueY))
                            .height(d(52f))
                            .clip(RoundedCornerShape(d(16f)))
                            .clickable { editing = i }
                            .padding(horizontal = d(12f)),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "${spec.get(state).roundToInt()} DP",
                            color = scheme.onSurface,
                            fontSize = sp(50f),
                            lineHeight = sp(60f),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                // ---- Color picker: label + cor atual (toque em qualquer um abre o seletor) ----
                Box(
                    modifier = Modifier
                        .offset(d(47f - 8f), d(1296f))
                        .height(d(53f))
                        .clip(RoundedCornerShape(d(16f)))
                        .clickable { pickerOpen = true }
                        .padding(horizontal = d(8f)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Color picker",
                        color = scheme.onSurface,
                        fontSize = sp(50f),
                        lineHeight = sp(60f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(d(373f), d(1292f))
                        .size(d(295f), d(60f))
                        .clip(RoundedCornerShape(d(20f)))
                        .checkerboard(d(10f))
                        .background(Color(state.clockColorArgb))
                        .clickable { pickerOpen = true }
                )

                // ---- "Preview" ----
                Box(
                    modifier = Modifier
                        .offset(d(214f), d(1383f - 24f))
                        .width(d(614f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Preview",
                        color = scheme.onSurface,
                        fontSize = sp(100f),
                        lineHeight = sp(120f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }

                // ---- Foto grande (retângulo r=210 em 0,1504) + quadrado de preview (r=112 em 240,2016) ----
                PhotoBox(
                    data = backdrop,
                    modifier = Modifier
                        .offset(d(0f), d(1504f))
                        .size(d(1080f), d(1532f))
                        .clip(RoundedCornerShape(d(210f)))
                )
                GlassLayer(
                    modifier = Modifier
                        .offset(d(240f), d(2016f))
                        .size(d(590f), d(590f)),
                    state = state,
                    data = backdrop,
                    photoW = px(1080f).roundToInt(),
                    photoH = px(1532f).roundToInt(),
                    offX = px(240f).roundToInt(),
                    offY = px(2016f - 1504f).roundToInt(),
                    density = density.density,
                    tintArgb = state.clockColorArgb,   // cor atual aplicada no preview do vidro
                ) {
                    // Só a forma: quem desenha o vidro é o shader (sem borda/sombra/enfeite extra).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White, RoundedCornerShape(d(112f)))
                    )
                }

                // ---- Rodapé ----
                Box(
                    modifier = Modifier
                        .offset(d(403f), d(3116f))
                        .width(d(265f)),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = "made by guizin\nXposed Module",
                        color = scheme.onSurface.copy(alpha = 0.11f),
                        fontSize = sp(30f),
                        lineHeight = sp(38f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        val initial = remember { state.clockColorArgb }
        ColorPickerDialog(
            initial = initial,
            onLive = { onChange(state.copy(clockColorArgb = it)) },
            onDismiss = { pickerOpen = false },
        )
    }

    editing?.let { idx ->
        val spec = SLIDERS[idx]
        ValueDialog(
            title = spec.label,
            initial = spec.get(state),
            onDismiss = { editing = null },
            onConfirm = { v ->
                onChange(spec.set(state, v))
                editing = null
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Componentes
// ---------------------------------------------------------------------------------------------

/** Foto de fundo preenchendo a caixa (center-crop, o mesmo mapeamento que o shader usa para refratar). */
@Composable
private fun PhotoBox(data: WallpaperBackdrop.Data?, modifier: Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (data != null) {
            val bmp = remember(data) { data.sharp.asImageBitmap() }
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * Camada com o liquid glass: o conteúdo (texto ou forma, em branco opaco) vira o "contents" do
 * mesmo GlassTextShader usado no relógio da lockscreen. O fundo refratado é a mesma foto desenhada
 * atrás, com o mesmo recorte, deslocada para as coordenadas locais da camada.
 *
 * photoW/photoH = tamanho (px) da caixa onde a foto está desenhada; offX/offY = posição (px) desta
 * camada dentro dessa caixa.
 */
@Composable
private fun GlassLayer(
    modifier: Modifier,
    state: GlassUiState,
    data: WallpaperBackdrop.Data?,
    photoW: Int,
    photoH: Int,
    offX: Int,
    offY: Int,
    density: Float,
    tintArgb: Int = 0x00FFFFFF,   // padrão: alfa 0 = vidro total
    content: @Composable BoxScope.() -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val effect: androidx.compose.ui.graphics.RenderEffect? =
        if (Build.VERSION.SDK_INT >= 33 && data != null && size.width > 0 && size.height > 0) {
            remember(
                state.blurRadius, state.refractionHeight, state.refractionAmount,
                state.chromaticAberration, state.saturation, state.edgeHighlight, state.highlightConc,
                data, size, photoW, photoH, offX, offY, density, tintArgb,
            ) {
                buildGlassEffect(state, data, size, photoW, photoH, offX, offY, density, tintArgb)
            }
        } else null

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .graphicsLayer {
                renderEffect = effect
                // sem efeito (Android < 13 ou foto ainda carregando): só um vidro translúcido simples
                alpha = if (effect == null) 0.3f else 1f
            },
        content = content,
    )
}

@RequiresApi(33)
private fun buildGlassEffect(
    state: GlassUiState,
    data: WallpaperBackdrop.Data,
    size: IntSize,
    photoW: Int,
    photoH: Int,
    offX: Int,
    offY: Int,
    density: Float,
    tintArgb: Int,
): androidx.compose.ui.graphics.RenderEffect {
    val rs = RuntimeShader(GlassTextShader.SRC)
    rs.setInputShader("backdrop", WallpaperBackdrop.shaderFor(data.sharp, photoW, photoH, offX, offY))
    rs.setInputShader("backdropBlur", WallpaperBackdrop.shaderFor(data.blur, photoW, photoH, offX, offY))
    GlassTextShader.configure(
        rs,
        density,
        hasBackdrop = true,
        refractionHeight = state.refractionHeight,
        refractionAmount = state.refractionAmount,
        chromaticAberration = state.chromaticAberration,
        saturation = state.saturation,
        edgeHighlight = state.edgeHighlight,
        highlightConc = state.highlightConc,
        blurRadius = state.blurRadius,
        baseColorArgb = state.resolvedBaseColorArgb(),
        blendMode = state.blendMode,
        viewWidth = size.width.toFloat(),
        viewHeight = size.height.toFloat(),
        tintArgb = tintArgb,
    )
    return RenderEffect.createRuntimeShaderEffect(rs, "contents").asComposeRenderEffect()
}

/** "Standard slider" do Material 3 como no design: trilho 56, alça 4x68, vão de 6, pontas 16 / meio 2. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    sc: Dp,
    onChange: (Float) -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val active = scheme.primary
    val inactive = scheme.secondaryContainer
    val stop = scheme.onSecondaryContainer

    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(sc * 4f, sc * 68f)
                    .background(active, RoundedCornerShape(sc * 2f))
            )
        },
        track = { st ->
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sc * 56f)
            ) {
                val k = sc.toPx()
                val span = (st.valueRange.endInclusive - st.valueRange.start).coerceAtLeast(0.0001f)
                val frac = ((st.value - st.valueRange.start) / span).coerceIn(0f, 1f)
                val cx = size.width * frac
                val halfThumb = 2f * k
                val gap = 6f * k
                val outer = CornerRadius(16f * k)
                val inner = CornerRadius(2f * k)
                val h = size.height

                val activeEnd = (cx - halfThumb - gap).coerceAtLeast(0f)
                val inactiveStart = (cx + halfThumb + gap).coerceAtMost(size.width)

                if (activeEnd > 0f) {
                    drawPath(
                        Path().apply {
                            addRoundRect(RoundRect(0f, 0f, activeEnd, h, outer, inner, inner, outer))
                        },
                        color = active,
                    )
                }
                if (inactiveStart < size.width) {
                    drawPath(
                        Path().apply {
                            addRoundRect(RoundRect(inactiveStart, 0f, size.width, h, inner, outer, outer, inner))
                        },
                        color = inactive,
                    )
                    if (size.width - inactiveStart > 14f * k) {
                        drawCircle(stop, radius = 2f * k, center = Offset(size.width - 5.5f * k, h / 2f))
                    }
                }
            }
        },
    )
}

@Composable
private fun ValueDialog(
    title: String,
    initial: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(initial.roundToInt().toString()) }
    val parsed = text.replace(',', '.').toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                singleLine = true,
                suffix = { Text("DP") },
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { onConfirm(it.coerceIn(0f, CUSTOM_MAX)) } },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Fundo xadrez (padrão de transparência). Aplicar depois do clip. */
private fun Modifier.checkerboard(cell: Dp): Modifier = drawBehind {
    val c = cell.toPx()
    drawRect(Color.White)
    val cols = ceil(size.width / c).toInt()
    val rows = ceil(size.height / c).toInt()
    for (r in 0 until rows) {
        for (col in 0 until cols) {
            if ((r + col) % 2 == 0) {
                drawRect(Color(0xFFBDBDBD), Offset(col * c, r * c), Size(c, c))
            }
        }
    }
}

/** ARGB (Int) -> "#RRGGBBAA" (alfa por último, como no Figma). */
private fun toHex(argb: Int): String = "#%02X%02X%02X%02X".format(
    android.graphics.Color.red(argb), android.graphics.Color.green(argb),
    android.graphics.Color.blue(argb), android.graphics.Color.alpha(argb),
)

/** "#RRGGBBAA" ou "RRGGBB" (alfa FF) -> ARGB (Int); null se inválido. */
private fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 && t.length != 8) return null
    val v = t.toLongOrNull(16) ?: return null
    return if (t.length == 8) {
        val r = ((v shr 24) and 0xFF).toInt()
        val g = ((v shr 16) and 0xFF).toInt()
        val b = ((v shr 8) and 0xFF).toInt()
        val a = (v and 0xFF).toInt()
        android.graphics.Color.argb(a, r, g, b)
    } else {
        0xFF000000.toInt() or v.toInt()
    }
}

/**
 * Seletor de cor com alfa: espectro (saturação/brilho), matiz, opacidade e campo HEX (#RRGGBBAA).
 * Aplica a cor ao vivo (onLive); Cancelar volta para a cor inicial.
 *
 * Observação: o seletor nativo da Samsung não tem API pública para apps de terceiros (só existe
 * a versão da biblioteca da comunidade "sesl"), então este segue o mesmo layout em Compose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerDialog(
    initial: Int,
    onLive: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val liveNow by rememberUpdatedState(onLive)
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableStateOf(hsv0[0]) }
    var sat by remember { mutableStateOf(hsv0[1]) }
    var value by remember { mutableStateOf(hsv0[2]) }
    var alpha by remember { mutableStateOf(android.graphics.Color.alpha(initial) / 255f) }
    var hexText by remember { mutableStateOf(toHex(initial)) }
    var hexDirty by remember { mutableStateOf(false) }

    val hsv = floatArrayOf(hue, sat, value)
    val argb = android.graphics.Color.HSVToColor((alpha * 255f).roundToInt().coerceIn(0, 255), hsv)
    val opaque = Color(android.graphics.Color.HSVToColor(255, hsv))

    LaunchedEffect(argb) {
        liveNow(argb)
        if (!hexDirty) hexText = toHex(argb)
    }

    fun applyArgb(c: Int) {
        val f = FloatArray(3)
        android.graphics.Color.colorToHSV(c, f)
        hue = f[0]; sat = f[1]; value = f[2]
        alpha = android.graphics.Color.alpha(c) / 255f
    }

    val cancel = {
        liveNow(initial)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Color picker") },
        text = {
            Column {
                // espectro: saturação (x) e brilho (y)
                var boxSize by remember { mutableStateOf(IntSize.Zero) }
                fun pick(pos: Offset) {
                    if (boxSize.width <= 0 || boxSize.height <= 0) return
                    hexDirty = false
                    sat = (pos.x / boxSize.width).coerceIn(0f, 1f)
                    value = 1f - (pos.y / boxSize.height).coerceIn(0f, 1f)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                        .clip(RoundedCornerShape(12.dp))
                        .onSizeChanged { boxSize = it }
                        .pointerInput(Unit) { detectTapGestures { pick(it) } }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragStart = { pick(it) }) { change, _ ->
                                pick(change.position)
                                change.consume()
                            }
                        }
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val center = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.Black.copy(alpha = 0.35f), 12.dp.toPx(), center, style = Stroke(4.dp.toPx()))
                        drawCircle(Color.White, 11.dp.toPx(), center, style = Stroke(2.5f.dp.toPx()))
                    }
                }

                Spacer(Modifier.height(12.dp))
                // matiz
                val rainbow = remember { List(7) { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) } }
                Slider(
                    value = hue,
                    onValueChange = { hexDirty = false; hue = it },
                    valueRange = 0f..360f,
                    track = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Brush.horizontalGradient(rainbow))
                        )
                    },
                )
                // opacidade (0 = vidro total, 1 = relógio sólido na cor)
                Text("Opacity", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = alpha,
                    onValueChange = { hexDirty = false; alpha = it },
                    valueRange = 0f..1f,
                    track = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .checkerboard(7.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(opaque.copy(alpha = 0f), opaque.copy(alpha = 1f))
                                    )
                                )
                        )
                    },
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .checkerboard(8.dp)
                            .background(Color(argb))
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { t ->
                            val clean = t.filter { c -> c.isLetterOrDigit() || c == '#' }.take(9).uppercase()
                            hexText = clean
                            hexDirty = true
                            if (clean.removePrefix("#").length == 8) parseHex(clean)?.let { applyArgb(it) }
                        },
                        label = { Text("HEX (#RRGGBBAA)") },
                        singleLine = true,
                        isError = parseHex(hexText) == null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            parseHex(hexText)?.let { applyArgb(it); hexDirty = false }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------------------------------------
// Foto de fundo: wallpaper atual (mesmo carregador do módulo) com fallbacks
// ---------------------------------------------------------------------------------------------

private suspend fun loadBackdrop(ctx: Context, colorA: Int, colorB: Int): WallpaperBackdrop.Data =
    withContext(Dispatchers.IO) {
        val app = ctx.applicationContext ?: ctx

        // 1) wallpaper da lockscreen (ou da home, se for o mesmo) pelo carregador do módulo
        runCatching { WallpaperBackdrop.load(app) }
        WallpaperBackdrop.peek()?.let { return@withContext it }

        // 2) drawable do WallpaperManager
        runCatching {
            val drawable = WallpaperManager.getInstance(app).drawable
            if (drawable != null) {
                val w0 = drawable.intrinsicWidth
                val h0 = drawable.intrinsicHeight
                if (w0 > 0 && h0 > 0) {
                    val s = minOf(1f, 1600f / maxOf(w0, h0))
                    val w = (w0 * s).roundToInt().coerceAtLeast(1)
                    val h = (h0 * s).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    return@withContext WallpaperBackdrop.fromBitmap(bmp, 2)
                }
            }
        }

        // 3) sem acesso ao wallpaper: gradiente nas cores do tema, para o preview não ficar vazio
        val bmp = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 540f, 1200f, colorA, colorB, Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bmp).drawRect(0f, 0f, 540f, 1200f, paint)
        WallpaperBackdrop.fromBitmap(bmp, 3)
    }

package com.ardeman.krida.congklak

import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import com.ardeman.krida.core.games.CongklakBoard
import com.ardeman.krida.core.games.CongklakGame
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the voxel congklak board with Filament on a [SurfaceView] (the game) or a
 * [TextureView] (the menu card's preview, which Compose can clip and snapshot). Seeds glide toward the
 * targets set with [setSeedTargets]; frames are only rendered while something changes.
 * Call everything on the main thread.
 */
class BoardRenderer private constructor(attach: (UiHelper) -> Unit) : Choreographer.FrameCallback {

    constructor(surfaceView: SurfaceView) : this({ it.attachTo(surfaceView) })
    constructor(textureView: TextureView) : this({ it.attachTo(textureView) })

    /** True once a frame has been rendered to the current surface. */
    var hasDrawn = false
        private set

    /** Called after the first frame has been rendered, once per renderer. */
    var onFirstFrame: (() -> Unit)? = null


    /** Pit centers on screen, in pixels; updated on resize. */
    var onPitsProjected: ((List<FloatArray>) -> Unit)? = null

    /** The seat on the near side of the board (0 or 1). */
    var viewSeat = 0
        set(value) {
            field = value
            if (width > 1) { updateCamera(); dirty = true }
        }

    private val engine: Engine
    private val renderer: Renderer
    private val filamentScene: com.google.android.filament.Scene
    private val view: View
    private val camera: Camera
    private val cameraEntity: Int
    private val materials: UbershaderProvider
    private val assetLoader: AssetLoader
    private val resourceLoader: ResourceLoader
    private val asset: FilamentAsset
    private val seedEntities: IntArray
    /** Gold ring entity per playable pit; only the shown ones are in the scene. */
    private val ringEntities: Map<Int, Int>
    private var shownRings = emptySet<Int>()
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private val choreographer = Choreographer.getInstance()

    private val current = Array(CongklakBoard.SEED_COUNT) { FloatArray(3) }
    private val target = Array(CongklakBoard.SEED_COUNT) { FloatArray(3) }
    private var placed = false
    private var dirty = true
    private var lastFrameNanos = 0L
    private var width = 1
    private var height = 1
    private var running = false

    init {
        ensureInit()
        engine = Engine.create()
        renderer = engine.createRenderer()
        filamentScene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        // Unlit vertex colors: no exposure scaling, no tone curve, so colors stay as authored.
        camera.setExposure(1f)
        view.scene = filamentScene
        view.camera = camera
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            val bg = CongklakBoard.background.map(GlbWriter::srgbToLinear)
            clearColor = floatArrayOf(bg[0], bg[1], bg[2], 1f)
        }

        materials = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materials, EntityManager.get())
        resourceLoader = ResourceLoader(engine)
        asset = checkNotNull(assetLoader.createAsset(CongklakGlb.build())) { "board glb rejected" }
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        filamentScene.addEntities(asset.entities)
        seedEntities = IntArray(CongklakBoard.SEED_COUNT) { asset.getFirstEntityByName(CongklakGlb.seedName(it)) }
        ringEntities = (0 until CongklakGame.PITS).filterNot(CongklakGame::isStore)
            .associateWith { asset.getFirstEntityByName(CongklakGlb.ringName(it)) }
        ringEntities.values.forEach(filamentScene::removeEntity)

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let(engine::destroySwapChain)
                swapChain = engine.createSwapChain(surface)
                hasDrawn = false
                dirty = true
            }

            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(w: Int, h: Int) {
                width = maxOf(w, 1)
                height = maxOf(h, 1)
                view.viewport = Viewport(0, 0, width, height)
                updateCamera()
                dirty = true
            }
        }
        attach(uiHelper)
    }

    /** Sets where every seed should rest. The first call places them without animation. */
    /** [targets] holds x, y, z per seed, as [com.ardeman.krida.core.games.CongklakSeeds.targets] returns. */
    fun setSeedTargets(targets: FloatArray) {
        for (i in target.indices) targets.copyInto(target[i], 0, i * 3, i * 3 + 3)
        if (!placed) {
            for (i in target.indices) target[i].copyInto(current[i])
            placed = true
        }
        dirty = true
    }

    /** Draws the scene again on the next frame, even if nothing changed. */
    fun invalidate() {
        dirty = true
    }

    /** Shows gold rings around exactly these pits. */
    fun showRings(pits: Set<Int>) {
        if (pits == shownRings) return
        ringEntities.forEach { (pit, e) ->
            if (pit in pits && pit !in shownRings) filamentScene.addEntity(e)
            if (pit !in pits && pit in shownRings) filamentScene.removeEntity(e)
        }
        shownRings = pits
        dirty = true
    }

    fun resume() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    fun destroy() {
        pause()
        uiHelper.detach()
        filamentScene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
        resourceLoader.destroy()
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(filamentScene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = frameTimeNanos
        if (stepSeeds(dt)) dirty = true
        if (!dirty) return

        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            dirty = false
            hasDrawn = true
            onFirstFrame?.let { onFirstFrame = null; it() }
        }
    }

    /** Eases seeds toward their targets. Returns true while any seed is still moving. */
    private fun stepSeeds(dt: Float): Boolean {
        if (!placed) return false
        val tm = engine.transformManager
        val t = min(1f, dt * EASE_PER_SECOND)
        var moving = false
        val m = FloatArray(16)
        for (i in current.indices) {
            val c = current[i]
            val g = target[i]
            var changed = false
            for (a in 0..2) {
                val d = g[a] - c[a]
                if (abs(d) > 0.002f) { c[a] += d * t; changed = true } else if (d != 0f) { c[a] = g[a]; changed = true }
            }
            if (changed || dirty) {
                if (changed) moving = true
                yawMatrix(CongklakBoard.yaw(i), c, m)
                tm.setTransform(tm.getInstance(seedEntities[i]), m)
            }
        }
        return moving
    }

    private fun updateCamera() {
        val aspect = width.toDouble() / height
        // Seat 1 looks from the other long side: turn the camera 180 degrees around the y axis.
        val flip = if (viewSeat == 1) -1f else 1f
        val eye = CongklakBoard.cameraEye.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        val at = CongklakBoard.cameraTarget.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        camera.setProjection(CongklakBoard.CAMERA_HORIZONTAL_FOV_DEGREES.toDouble(), aspect, 0.5, 100.0, Camera.Fov.HORIZONTAL)
        camera.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            at[0].toDouble(), at[1].toDouble(), at[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        onPitsProjected?.invoke(List(CongklakGame.PITS) { CongklakBoard.pitAnchor(it).let { (x, y, z) -> project(x, y, z) } })
    }

    /** World point to screen pixels (origin top-left). */
    private fun project(x: Float, y: Float, z: Float): FloatArray {
        val v = camera.getViewMatrix(DoubleArray(16))
        val p = camera.getProjectionMatrix(DoubleArray(16))
        val eye = mul(v, doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble(), 1.0))
        val clip = mul(p, eye)
        val nx = clip[0] / clip[3]
        val ny = clip[1] / clip[3]
        return floatArrayOf(((nx + 1) / 2 * width).toFloat(), ((1 - ny) / 2 * height).toFloat())
    }

    private fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(4) { r ->
        m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3]
    }

    private fun yawMatrix(yaw: Float, pos: FloatArray, out: FloatArray) {
        val c = cos(yaw)
        val s = sin(yaw)
        out.fill(0f)
        out[0] = c; out[2] = -s
        out[5] = 1f
        out[8] = s; out[10] = c
        out[12] = pos[0]; out[13] = pos[1]; out[14] = pos[2]; out[15] = 1f
    }

    companion object {
        private const val EASE_PER_SECOND = 14f

        private var initialized = false

        private fun ensureInit() {
            if (initialized) return
            Filament.init()
            Gltfio.init()
            initialized = true
        }
    }
}

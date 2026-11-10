package com.ardeman.krida.ui

import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
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
import java.nio.ByteBuffer

/**
 * A Filament scene of one unlit voxel .glb on a transparent [TextureView] (which Compose can
 * turn, clip and snapshot), so the screen's backdrop gradient shows around the board. Frames
 * are only rendered while something changes: subclasses animate in [step] and call [invalidate]. Call everything on the main thread.
 */
abstract class VoxelStage(textureView: TextureView, glb: ByteBuffer) : Choreographer.FrameCallback {

    /** True once a frame has been rendered to the current surface. */
    var hasDrawn = false
        private set

    /** Called after the first frame has been rendered, once per renderer. */
    var onFirstFrame: (() -> Unit)? = null

    protected val engine: Engine
    protected val camera: Camera
    protected var width = 1
        private set
    protected var height = 1
        private set

    private val renderer: Renderer
    private val scene: com.google.android.filament.Scene
    private val view: View
    private val cameraEntity: Int
    private val materials: UbershaderProvider
    private val assetLoader: AssetLoader
    private val resourceLoader: ResourceLoader
    private val asset: FilamentAsset
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private val choreographer = Choreographer.getInstance()
    private var dirty = true
    private var lastFrameNanos = 0L
    private var running = false

    init {
        ensureInit()
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        // Unlit vertex colors: no exposure scaling, no tone curve, so colors stay as authored.
        camera.setExposure(1f)
        view.scene = scene
        view.camera = camera
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
        // Clear to transparent: the backdrop is drawn by Compose behind the view.
        view.blendMode = View.BlendMode.TRANSLUCENT
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        }

        materials = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materials, EntityManager.get())
        resourceLoader = ResourceLoader(engine)
        asset = checkNotNull(assetLoader.createAsset(glb)) { "glb rejected" }
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)

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
                onResize()
                dirty = true
            }
        }
        uiHelper.isOpaque = false
        uiHelper.attachTo(textureView)
    }

    /** The surface changed size ([width], [height]): set the camera's projection here. */
    protected abstract fun onResize()

    /** Advances animations by [dt] seconds. Returns true while anything is still moving. */
    protected open fun step(dt: Float): Boolean = false

    /** The first node named [name] in the .glb. */
    protected fun entity(name: String): Int = asset.getFirstEntityByName(name)

    protected fun setTransform(entity: Int, matrix: FloatArray) {
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(entity), matrix)
    }

    /** Adds [entity] to the scene or takes it out. */
    protected fun show(entity: Int, visible: Boolean) {
        if (visible) scene.addEntity(entity) else scene.removeEntity(entity)
        dirty = true
    }

    /** Draws the scene again on the next frame, even if nothing changed. */
    fun invalidate() {
        dirty = true
        // A watch/fit after animations stop must restart the choreographer, or the
        // camera ease never advances (dirty alone does not call [resume]).
        resume()
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
        scene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
        resourceLoader.destroy()
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = frameTimeNanos
        if (step(dt)) dirty = true
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

    /** True when the next frame will be drawn anyway; lets [step] skip unchanged transforms. */
    protected val redrawPending: Boolean get() = dirty

    /** World point to screen pixels (origin top-left). */
    protected fun project(x: Float, y: Float, z: Float): FloatArray {
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

    companion object {
        private var initialized = false

        private fun ensureInit() {
            if (initialized) return
            Filament.init()
            Gltfio.init()
            initialized = true
        }
    }
}


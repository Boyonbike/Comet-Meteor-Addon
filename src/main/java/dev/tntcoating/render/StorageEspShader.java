package dev.tntcoating.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.renderer.ExtendedRenderPipelineBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.postprocess.OutlineUniforms;
import meteordevelopment.meteorclient.utils.render.postprocess.PostProcessShader;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** A separate storage mask using Meteor's outline effect and retained model geometry. */
public final class StorageEspShader extends PostProcessShader implements AutoCloseable {
    public static final RenderPipeline MASK_PIPELINE = new ExtendedRenderPipelineBuilder()
        .withLocation(id("pipeline/storage_mask"))
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
        .withVertexShader(id("shaders/storage_mask.vert"))
        .withFragmentShader(id("shaders/storage_mask.frag"))
        .withUniform("MeshData", UniformType.UNIFORM_BUFFER)
        .withUniform("StorageData", UniformType.UNIFORM_BUFFER)
        // This depth belongs only to the mask. Terrain must never hide storage ESP.
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
        .withDepthWrite(true)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build();

    private static final int MASK_UNIFORM_SIZE = new Std140SizeCalculator().putVec4().get();
    private final DynamicUniformStorage<MaskData> maskUniforms;
    private int outlineWidth;
    private float opacity;
    private int shape;
    private float glow;
    private boolean closed;

    /** Construct on the render thread, after resources and the GPU device are ready. */
    public StorageEspShader() {
        super(MeteorRenderPipelines.POST_OUTLINE);
        try {
            var resources = mc.getResourceManager();
            RenderSystem.getDevice().precompilePipeline(MASK_PIPELINE, (identifier, type) -> {
                try (var input = resources.open(identifier)) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
            maskUniforms = new DynamicUniformStorage<>("Comet - Storage ESP UBO", MASK_UNIFORM_SIZE, 256);
        } catch (RuntimeException exception) {
            framebuffer.destroyBuffers();
            throw exception;
        }
    }

    /** Begin the mask for this frame. The caller submits all visible batches before compositing. */
    public void prepare() {
        if (closed) throw new IllegalStateException("Storage ESP shader is closed");
        int width = Math.max(1, mc.getWindow().getWidth());
        int height = Math.max(1, mc.getWindow().getHeight());
        if (framebuffer.width != width || framebuffer.height != height) onResized(width, height);
        // Rotate before writing this frame so previous GPU draws retain their uniform values.
        maskUniforms.endFrame();
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            framebuffer.getColorTexture(), Color.CLEAR.getPacked(), framebuffer.getDepthTexture(), 1.0);
    }

    /** The model vertices retain build-origin-relative X/Z and world Y. */
    public GpuBufferSlice maskUniform(double originX, double originZ, double playerX, double playerY,
                                     double playerZ, double fadeDistance) {
        return maskUniforms.writeUniform(new MaskData((float) (playerX - originX), (float) playerY,
            (float) (playerZ - originZ), (float) fadeDistance));
    }

    public void composite(int width, int fillOpacity, ShapeMode shapeMode, double glowMultiplier) {
        outlineWidth = width;
        opacity = fillOpacity / 255.0f;
        shape = shapeMode.ordinal();
        glow = (float) glowMultiplier;
        render();
    }

    @Override protected boolean shouldDraw() { return !closed; }

    @Override protected void setupPass(MeshRenderer renderer) {
        // Meteor rotates these shared outline/post uniforms at the actual end of each frame.
        renderer.uniform("OutlineData", OutlineUniforms.write(outlineWidth, opacity, shape, glow));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        maskUniforms.close();
        framebuffer.destroyBuffers();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("tnt-coating", path);
    }

    private record MaskData(float playerX, float playerY, float playerZ, float fadeDistance)
        implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer).putVec4(playerX, playerY, playerZ, fadeDistance);
        }
    }
}

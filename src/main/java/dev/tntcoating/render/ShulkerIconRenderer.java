package dev.tntcoating.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ResourcePacksReloadedEvent;
import meteordevelopment.orbit.EventHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.shaders.UniformType;

public final class ShulkerIconRenderer {
    /** Subscribed once at start-up so a resource reload, which can reset the device's pipeline cache, recompiles. */
    public static final ReloadListener RELOAD_LISTENER = new ReloadListener();

    public static final class ReloadListener {
        private ReloadListener() {}

        @EventHandler private void onReload(ResourcePacksReloadedEvent event) { pipelineState = PipelineState.Unchecked; }
    }
    private enum PipelineState { Unchecked, Ready, Failed }

    // GUI extraction and Meteor's deferred widget draws run on the render thread.
    private static Integer extractingColour;
    private static volatile PipelineState pipelineState = PipelineState.Unchecked;

    private ShulkerIconRenderer() {}

    public static Integer extractingColour() { return extractingColour; }

    public static void withColour(Integer colour, Runnable draw) {
        Integer previous = extractingColour;
        extractingColour = colour;
        try { draw.run(); }
        finally { extractingColour = previous; }
    }

    public static void draw(GuiGraphics graphics, ItemStack item, Integer colour) {
        withColour(colour, () -> graphics.renderItem(item, 0, 0));
    }

    public static BlitRenderState colourBlit(BlitRenderState original, GuiItemRenderState item) {
        Integer colour = ((ShulkerIconRenderState) (Object) item).comet$iconColour();
        if (colour == null) return original;
        RenderPipeline pipeline = pipeline();
        // Without the silhouette shader, keep the item's normal icon rather than failing the GUI frame.
        if (pipeline == null) return original;
        return new BlitRenderState(pipeline, original.textureSetup(), original.pose(),
            original.x0(), original.y0(), original.x1(), original.y1(), original.u0(), original.u1(),
            original.v0(), original.v1(), colour, original.scissorArea(), original.bounds());
    }

    private static final class Pipeline {
        private static final RenderPipeline INSTANCE = create();

        private static RenderPipeline create() {
            RenderPipeline base = RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA;
            return RenderPipeline.builder()
                .withLocation(id("pipeline/shulker_icon"))
                .withVertexShader(id("shaders/shulker_icon.vert"))
                .withFragmentShader(id("shaders/shulker_icon.frag"))
                .withSampler("Sampler0")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                .withDepthTestFunction(base.getDepthTestFunction())
                .withDepthWrite(base.isWriteDepth())
                .withBlend(base.getBlendFunction().orElseThrow())
                .withColorWrite(base.isWriteColor(), base.isWriteAlpha())
                .withCull(base.isCull())
                .build();
        }
    }

    /** Compiles the silhouette pipeline once per resource load; null if it cannot be compiled. */
    private static RenderPipeline pipeline() {
        PipelineState state = pipelineState;
        if (state == PipelineState.Ready) return Pipeline.INSTANCE;
        if (state == PipelineState.Failed) return null;
        try {
            RenderPipeline pipeline = Pipeline.INSTANCE;
            RenderSystem.getDevice().precompilePipeline(pipeline, (identifier, type) -> {
                try (var input = Minecraft.getInstance().getResourceManager().open(identifier)) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException exception) { throw new UncheckedIOException(exception); }
            });
            pipelineState = PipelineState.Ready;
            return pipeline;
        } catch (RuntimeException | LinkageError e) {
            MeteorClient.LOG.error("Shulker Icons could not compile its solid-colour shader; showing normal icons", e);
            pipelineState = PipelineState.Failed;
            return null;
        }
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("tnt-coating", path); }
}

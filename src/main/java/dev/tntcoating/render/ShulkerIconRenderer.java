package dev.tntcoating.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class ShulkerIconRenderer {
    // GUI extraction and Meteor's deferred widget draws run on the render thread.
    private static Integer extractingColour;

    private ShulkerIconRenderer() {}

    public static Integer extractingColour() { return extractingColour; }

    public static void withColour(Integer colour, Runnable draw) {
        Integer previous = extractingColour;
        extractingColour = colour;
        try { draw.run(); }
        finally { extractingColour = previous; }
    }

    public static void draw(GuiGraphicsExtractor graphics, ItemStack item, Integer colour) {
        withColour(colour, () -> graphics.item(item, 0, 0));
    }

    public static BlitRenderState colourBlit(BlitRenderState original, GuiItemRenderState item) {
        Integer colour = ((ShulkerIconRenderState) (Object) item).comet$iconColour();
        if (colour == null) return original;
        return new BlitRenderState(pipeline(), original.textureSetup(), original.pose(),
            original.x0(), original.y0(), original.x1(), original.y1(), original.u0(), original.u1(),
            original.v0(), original.v1(), colour, original.scissorArea(), original.bounds());
    }

    private static final class Pipeline {
        private static final RenderPipeline INSTANCE = create();

        private static RenderPipeline create() {
            RenderPipeline base = RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA;
            var builder = RenderPipeline.builder()
                .withLocation(id("pipeline/shulker_icon"))
                .withVertexShader(id("shaders/shulker_icon.vert"))
                .withFragmentShader(id("shaders/shulker_icon.frag"))
                .withVertexBinding(0, base.getVertexFormatBinding(0))
                .withPrimitiveTopology(base.getPrimitiveTopology())
                .withDepthStencilState(java.util.Optional.ofNullable(base.getDepthStencilState()))
                .withColorTargetState(base.getColorTargetState())
                .withCull(base.isCull());
            base.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
            return builder.build();
        }
    }

    private static RenderPipeline pipeline() {
        RenderPipeline pipeline = Pipeline.INSTANCE;
        // The device caches compiled pipelines. Re-checking also handles a resource reload/device cache reset.
        RenderSystem.getDevice().precompilePipeline(pipeline, (identifier, type) -> {
            try (var input = Minecraft.getInstance().getResourceManager().open(identifier)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) { throw new UncheckedIOException(exception); }
        });
        return pipeline;
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("tnt-coating", path); }
}

package dev.tntcoating.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import meteordevelopment.meteorclient.renderer.MeshBuilder;

/** Creates storage meshes whose uploaded GPU buffers can be retained and closed by their batch. */
public final class StorageEspMesh {
    private StorageEspMesh() { }

    /**
     * Uses the mask pipeline's position/color layout with a fresh format for each builder.
     * Minecraft caches immediate GPU buffers on the format itself, so sharing the default
     * format would overwrite other batches and let batch cleanup close Meteor's buffers.
     * Upload each builder only once, then let the batch own and close the returned buffers.
     */
    public static MeshBuilder createBuilder() {
        VertexFormat format = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .build();
        return new MeshBuilder(format, VertexFormat.Mode.TRIANGLES);
    }
}

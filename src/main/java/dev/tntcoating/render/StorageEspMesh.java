package dev.tntcoating.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import meteordevelopment.meteorclient.renderer.MeshBuilder;

/** Creates storage meshes whose uploaded GPU buffers can be retained and closed by their batch. */
public final class StorageEspMesh {
    private StorageEspMesh() { }

    /**
     * Uses the mask pipeline's position/color layout. In 26.2 a MeshBuilder owns its vertex and
     * index buffers, so sharing the default format cannot overwrite another batch's geometry.
     * Upload each builder only once, then let the batch own and close the returned buffers.
     */
    public static MeshBuilder createBuilder() {
        return new MeshBuilder(DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.TRIANGLES);
    }
}

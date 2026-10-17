package dev.tntcoating.render;

import dev.tntcoating.core.StorageEspIndex.CellKey;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Camera transforms for retained Meteor meshes, whose horizontal origin is fixed at build time. */
public final class StorageEspView {
    private StorageEspView() { }

    /**
     * Reuses the event's view/bob matrix and shifts the old horizontal mesh origin to this frame's
     * camera. Subtraction must happen in double precision before the result is stored as a float.
     * MeshRenderer applies the current vertical camera translation separately.
     */
    public static Matrix4f batchTransform(Matrix4f out, Matrix4fc eventMatrices,
                                          double buildOriginX, double buildOriginZ,
                                          double cameraX, double cameraZ) {
        return out.set(eventMatrices).translate((float) (buildOriginX - cameraX), 0,
            (float) (buildOriginZ - cameraZ));
    }

    /** Matches MeshRenderer's projection, model view, event matrix, and vertical camera order. */
    public static Matrix4f frustumTransform(Matrix4f out, Matrix4fc projection, Matrix4fc modelView,
                                            Matrix4fc eventMatrices, double cameraY) {
        return out.set(projection).mul(modelView).mul(eventMatrices).translate(0, (float) -cameraY, 0);
    }

    /**
     * Tests only the view frustum; terrain visibility must never suppress storage behind walls.
     * Bounds use current-camera-relative X/Z and absolute Y, matching the frustum transform.
     * Two blocks of padding retain open chest and shulker geometry at cell boundaries.
     */
    public static boolean visible(FrustumIntersection frustum, CellKey key, double cameraX, double cameraZ) {
        double x = key.minX() - cameraX;
        double y = key.minY();
        double z = key.minZ() - cameraZ;
        return frustum.testAab((float) (x - 2), (float) (y - 2), (float) (z - 2),
            (float) (x + 10), (float) (y + 10), (float) (z + 10));
    }
}

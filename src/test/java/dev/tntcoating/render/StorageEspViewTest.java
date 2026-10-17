package dev.tntcoating.render;

import dev.tntcoating.core.StorageEspIndex.CellKey;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageEspViewTest {
    @Test void stationaryCameraPreservesEventTransformAndReusingOutputDoesNotAccumulateMotion() {
        Matrix4f event = eventTransform();
        Matrix4f eventSnapshot = new Matrix4f(event);
        Matrix4f out = new Matrix4f().rotateX(1.2f).translate(30, 40, 50);
        assertSame(out, StorageEspView.batchTransform(out, event, 10.25, -11.5, 10.25, -11.5));
        assertTrue(event.equals(out, 0));

        StorageEspView.batchTransform(out, event, 10.25, -11.5, 14.5, -8.125);
        StorageEspView.batchTransform(out, event, 10.25, -11.5, 10.25, -11.5);
        assertTrue(event.equals(out, 0));
        assertTrue(eventSnapshot.equals(event, 0), "The caller's event matrix must remain unchanged.");
    }

    @Test void horizontalSubBlockMotionRetainsPrecisionAtTheWorldBorder() {
        for (double origin : new double[] {-30_000_000.125, 30_000_000.125}) {
            Matrix4f transform = StorageEspView.batchTransform(new Matrix4f(), new Matrix4f(),
                origin, -origin, origin + .03125, -origin - .015625);
            assertEquals(-.03125f, transform.m30(), 0);
            assertEquals(.015625f, transform.m32(), 0);
            assertEquals(0, transform.m31(), 0, "MeshRenderer owns vertical camera movement.");
        }
    }

    @Test void retainedBatchesMatchPerFrameMeteorVerticesWhileCameraMovesRotatesAndBobs() {
        Matrix4f projection = perspective();
        Matrix4f modelView = new Matrix4f().rotateZ(.037f).translate(.025f, -.04f, .01f);
        Matrix4f event = eventTransform();
        for (double worldOrigin : new double[] {0, -30_000_000, 30_000_000}) {
            double buildX = worldOrigin + .125;
            double buildZ = -worldOrigin + .375;
            for (double motion : new double[] {0, .03125, 8.375, -25.625}) {
                double cameraX = buildX + motion;
                double cameraZ = buildZ - motion * .5;
                float cameraY = 72.625f + (float) motion * .125f;
                Matrix4f retainedTransform = new Matrix4f(projection).mul(modelView)
                    .mul(StorageEspView.batchTransform(new Matrix4f(), event,
                        buildX, buildZ, cameraX, cameraZ)).translate(0, -cameraY, 0);
                for (double localX : new double[] {-12.0625, .4375, 17.9375}) {
                    double worldX = worldOrigin + localX;
                    double worldZ = -worldOrigin - 24.8125;
                    float worldY = 80.75f;

                    // MeshBuilder stores X/Z relative to the camera captured in begin(), and absolute Y.
                    Vector4f retained = new Vector4f((float) (worldX - buildX), worldY,
                        (float) (worldZ - buildZ), 1);
                    retainedTransform.transform(retained);

                    // A fresh per-frame Meteor mesh uses the current horizontal camera instead.
                    Vector4f fresh = new Vector4f((float) (worldX - cameraX), worldY - cameraY,
                        (float) (worldZ - cameraZ), 1);
                    event.transform(fresh);
                    modelView.transform(fresh);
                    projection.transform(fresh);
                    assertVectorEquals(fresh, retained, 0.0002f);
                }
            }
        }
    }

    @Test void frustumTransformMatchesTheDrawPipelineOrderWithoutMutatingInputs() {
        Matrix4f projection = perspective();
        Matrix4f modelView = new Matrix4f().rotateZ(-.11f).translate(.07f, -.03f, .02f);
        Matrix4f event = eventTransform();
        Matrix4f projectionSnapshot = new Matrix4f(projection);
        Matrix4f viewSnapshot = new Matrix4f(modelView);
        Matrix4f eventSnapshot = new Matrix4f(event);
        Matrix4f out = new Matrix4f().translation(100, 200, 300);
        assertSame(out, StorageEspView.frustumTransform(out, projection, modelView, event, 91.375));
        Vector4f actual = out.transform(new Vector4f(-7.25f, 104.5f, -23.125f, 1));
        Vector4f expected = new Vector4f(-7.25f, 104.5f - 91.375f, -23.125f, 1);
        event.transform(expected);
        modelView.transform(expected);
        projection.transform(expected);
        assertVectorEquals(expected, actual, 0.0002f);
        assertTrue(projectionSnapshot.equals(projection, 0));
        assertTrue(viewSnapshot.equals(modelView, 0));
        assertTrue(eventSnapshot.equals(event, 0));
    }

    @Test void perspectiveCullingKeepsFrontCellsAndRejectsBehindSideVerticalAndDistantCells() {
        FrustumIntersection frustum = frustum(perspective(), new Matrix4f(), 68);
        assertTrue(StorageEspView.visible(frustum, new CellKey(0, 8, -4), 4, 0));
        assertFalse(StorageEspView.visible(frustum, new CellKey(0, 8, 4), 4, 0));
        assertFalse(StorageEspView.visible(frustum, new CellKey(20, 8, -4), 4, 0));
        assertFalse(StorageEspView.visible(frustum, new CellKey(0, 40, -4), 4, 0));
        assertFalse(StorageEspView.visible(frustum, new CellKey(0, 8, -200), 4, 0));
    }

    @Test void cullingFollowsViewRotationAndUsesCurrentCameraHeight() {
        Matrix4f reverseView = new Matrix4f().rotateY((float) Math.PI);
        FrustumIntersection frustum = frustum(perspective(), reverseView, 68);
        assertTrue(StorageEspView.visible(frustum, new CellKey(0, 8, 4), 4, 0));
        assertFalse(StorageEspView.visible(frustum, new CellKey(0, 8, -4), 4, 0));

        frustum = frustum(perspective(), reverseView, 244);
        assertFalse(StorageEspView.visible(frustum, new CellKey(0, 8, 4), 4, 0));
        assertTrue(StorageEspView.visible(frustum, new CellKey(0, 30, 4), 4, 0));
    }

    @Test void paddingKeepsOpenContainerGeometryAtHorizontalAndVerticalCellEdges() {
        Matrix4f projection = new Matrix4f().ortho(-4, 4, -4, 4, .1f, 100);
        FrustumIntersection frustum = frustum(projection, new Matrix4f(), 68);
        // Only the two-block extension intersects the narrow view, beyond nominal cell [8, 16].
        CellKey side = new CellKey(1, 8, -2);
        assertTrue(StorageEspView.visible(frustum, side, 2.1, 0));
        assertFalse(StorageEspView.visible(frustum, side, 1.9, 0));
        // The opposite edge also extends beyond the cell's nominal maximum of eight blocks.
        CellKey otherSide = new CellKey(0, 8, -2);
        assertTrue(StorageEspView.visible(frustum, otherSide, 13.9, 0));
        assertFalse(StorageEspView.visible(frustum, otherSide, 14.1, 0));

        CellKey above = new CellKey(0, 9, -2);
        assertTrue(StorageEspView.visible(frustum(projection, new Matrix4f(), 66.1), above, 4, 0));
        assertFalse(StorageEspView.visible(frustum(projection, new Matrix4f(), 65.9), above, 4, 0));
    }

    @Test void cullingAtTheWorldBorderPreservesTheSameFractionalThresholdsAsAtOrigin() {
        FrustumIntersection frustum = frustum(new Matrix4f().ortho(-4, 4, -4, 4, .1f, 100),
            new Matrix4f(), 68);
        for (int origin : new int[] {-30_000_000, 0, 30_000_000}) {
            CellKey key = new CellKey((origin + 8) >> 3, 8, (origin - 16) >> 3);
            assertTrue(StorageEspView.visible(frustum, key, origin + 2.03125, origin));
            assertFalse(StorageEspView.visible(frustum, key, origin + 1.96875, origin));
        }
    }

    private static Matrix4f eventTransform() {
        return new Matrix4f().rotateX(-.41f).rotateY(.83f).rotateZ(.023f)
            .translate(.045f, .065f, -.025f);
    }

    private static Matrix4f perspective() {
        return new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 512);
    }

    private static FrustumIntersection frustum(Matrix4fc projection, Matrix4fc event, double cameraY) {
        return new FrustumIntersection(StorageEspView.frustumTransform(new Matrix4f(), projection,
            new Matrix4f(), event, cameraY));
    }

    private static void assertVectorEquals(Vector4f expected, Vector4f actual, float tolerance) {
        assertEquals(expected.x, actual.x, tolerance, "clip X");
        assertEquals(expected.y, actual.y, tolerance, "clip Y");
        assertEquals(expected.z, actual.z, tolerance, "clip Z");
        assertEquals(expected.w, actual.w, tolerance, "clip W");
    }
}

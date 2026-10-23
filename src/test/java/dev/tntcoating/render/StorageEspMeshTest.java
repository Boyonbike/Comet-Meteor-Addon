package dev.tntcoating.render;

import com.mojang.blaze3d.GraphicsWorkarounds;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class StorageEspMeshTest {
    @Test void sharedFormatControlReproducesClosedBufferFailure() throws Exception {
        try (GpuFixture fixture = new GpuFixture()) {
            Uploaded first = upload(populate(ordinaryBuilder(), 2, 1));
            Uploaded second = upload(populate(ordinaryBuilder(), 1, 20));
            assertSame(first.vertices, second.vertices);
            assertSame(first.indices, second.indices);
            first.close();

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> upload(populate(ordinaryBuilder(), 1, 40)));
            assertEquals("Buffer already closed", failure.getMessage());
            assertTrue(fixture.device.writes > 0, "Exercise Minecraft's immediate-buffer reuse path.");
        }
    }

    @Test void retainedBatchesKeepIndependentGeometryAndExactIndexLengths() throws Exception {
        try (GpuFixture ignored = new GpuFixture()) {
            Uploaded first = upload(populate(StorageEspMesh.createBuilder(), 2, 1));
            byte[] firstVertices = first.vertices.data.clone();
            byte[] firstIndices = first.indices.data.clone();
            Uploaded second = upload(populate(StorageEspMesh.createBuilder(), 1, 20));

            assertNotSame(first.vertices, second.vertices);
            assertNotSame(first.indices, second.indices);
            assertArrayEquals(firstVertices, first.vertices.data);
            assertArrayEquals(firstIndices, first.indices.data);
            assertEquals(8 * 16, first.vertices.size());
            assertEquals(12 * Integer.BYTES, first.indices.size());
            assertEquals(4 * 16, second.vertices.size());
            assertEquals(6 * Integer.BYTES, second.indices.size());
            assertVertexLayout(second.vertices.data, 20);
            assertArrayEquals(new int[] {0, 1, 2, 2, 3, 0}, ints(second.indices.data));

            byte[] secondVertices = second.vertices.data.clone();
            first.close();
            assertFalse(second.vertices.isClosed());
            assertFalse(second.indices.isClosed());
            assertArrayEquals(secondVertices, second.vertices.data);
            second.close();
        }
    }

    @Test void replacingAndUnloadingBatchesLeavesMeteorImmediateBuffersUsable() throws Exception {
        try (GpuFixture fixture = new GpuFixture()) {
            Uploaded ordinary = upload(populate(ordinaryBuilder(), 4, 100));
            Uploaded old = upload(populate(StorageEspMesh.createBuilder(), 3, 1));
            Uploaded neighbor = upload(populate(StorageEspMesh.createBuilder(), 2, 20));
            Uploaded replacement = upload(populate(StorageEspMesh.createBuilder(), 1, 40));
            byte[] neighborVertices = neighbor.vertices.data.clone();
            byte[] replacementVertices = replacement.vertices.data.clone();

            old.close();
            assertFalse(neighbor.vertices.isClosed());
            assertFalse(neighbor.indices.isClosed());
            assertFalse(replacement.vertices.isClosed());
            assertFalse(replacement.indices.isClosed());
            assertFalse(ordinary.vertices.isClosed());
            assertFalse(ordinary.indices.isClosed());
            assertArrayEquals(neighborVertices, neighbor.vertices.data);
            assertArrayEquals(replacementVertices, replacement.vertices.data);

            Uploaded nextOrdinary = assertDoesNotThrow(() -> upload(populate(ordinaryBuilder(), 1, 200)));
            assertSame(ordinary.vertices, nextOrdinary.vertices);
            assertSame(ordinary.indices, nextOrdinary.indices);
            assertVertexLayout(nextOrdinary.vertices.data, 200);
            assertArrayEquals(neighborVertices, neighbor.vertices.data);
            assertArrayEquals(replacementVertices, replacement.vertices.data);

            neighbor.close();
            replacement.close();
            assertDoesNotThrow(() -> upload(populate(ordinaryBuilder(), 1, 300)));
            assertTrue(fixture.device.writes >= 4);
        }
    }

    private static MeshBuilder ordinaryBuilder() {
        return new MeshBuilder(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES);
    }

    private static MeshBuilder populate(MeshBuilder mesh, int quads, int x) {
        Color color = new Color(11, 22, 33, 44);
        mesh.begin();
        for (int i = 0; i < quads; i++) {
            mesh.ensureQuadCapacity();
            mesh.quad(
                mesh.vec3(x + i, 2, 3).color(color).next(),
                mesh.vec3(x + i + 1, 2, 3).color(color).next(),
                mesh.vec3(x + i + 1, 3, 3).color(color).next(),
                mesh.vec3(x + i, 3, 3).color(color).next());
        }
        mesh.end();
        return mesh;
    }

    private static Uploaded upload(MeshBuilder mesh) {
        return new Uploaded((MemoryBuffer) mesh.getVertexBuffer(), (MemoryBuffer) mesh.getIndexBuffer());
    }

    private static void assertVertexLayout(byte[] data, int x) {
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        assertEquals((float) x, bytes.getFloat());
        assertEquals(2f, bytes.getFloat());
        assertEquals(3f, bytes.getFloat());
        assertEquals(11, Byte.toUnsignedInt(bytes.get()));
        assertEquals(22, Byte.toUnsignedInt(bytes.get()));
        assertEquals(33, Byte.toUnsignedInt(bytes.get()));
        assertEquals(44, Byte.toUnsignedInt(bytes.get()));
    }

    private static int[] ints(byte[] data) {
        int[] result = new int[data.length / Integer.BYTES];
        ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).asIntBuffer().get(result);
        return result;
    }

    private record Uploaded(MemoryBuffer vertices, MemoryBuffer indices) implements AutoCloseable {
        @Override public void close() {
            vertices.close();
            indices.close();
        }
    }

    /** Replaces only the GPU transport; MeshBuilder and VertexFormat execute their real upload code. */
    private static final class GpuFixture implements AutoCloseable {
        private final Field launcherField = field(FabricLauncherBase.class, "launcher");
        private final Field deviceField = field(RenderSystem.class, "DEVICE");
        private final Field workaroundField = field(GraphicsWorkarounds.class, "instance");
        private final Field vertexField = field(VertexFormat.class, "immediateDrawVertexBuffer");
        private final Field indexField = field(VertexFormat.class, "immediateDrawIndexBuffer");
        private final Object previousLauncher;
        private final Object previousDevice;
        private final Object previousWorkaround;
        private final Object previousVertices;
        private final Object previousIndices;
        private final boolean previousRendering;
        private final RecordingDevice device = new RecordingDevice();

        GpuFixture() throws IllegalAccessException {
            previousLauncher = launcherField.get(null);
            previousDevice = deviceField.get(null);
            previousWorkaround = workaroundField.get(null);
            previousVertices = vertexField.get(DefaultVertexFormat.POSITION_COLOR);
            previousIndices = indexField.get(DefaultVertexFormat.POSITION_COLOR);
            // MeshBuilder reads Fabric's development flag during static initialization.
            launcherField.set(null, Proxy.newProxyInstance(FabricLauncher.class.getClassLoader(),
                new Class<?>[] {FabricLauncher.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("isDevelopment")) return false;
                    throw new AssertionError("Unexpected launcher call: " + method.getName());
                }));
            deviceField.set(null, device.proxy);
            vertexField.set(DefaultVertexFormat.POSITION_COLOR, null);
            indexField.set(DefaultVertexFormat.POSITION_COLOR, null);
            // Utils initializes a projection UBO, so install the recording device before loading it.
            previousRendering = Utils.rendering3D;
            Utils.rendering3D = false;
        }

        @Override public void close() throws IllegalAccessException {
            Utils.rendering3D = previousRendering;
            vertexField.set(DefaultVertexFormat.POSITION_COLOR, previousVertices);
            indexField.set(DefaultVertexFormat.POSITION_COLOR, previousIndices);
            workaroundField.set(null, previousWorkaround);
            deviceField.set(null, previousDevice);
            launcherField.set(null, previousLauncher);
            device.uploaded.forEach(MemoryBuffer::close);
        }

        private static Field field(Class<?> owner, String name) {
            try {
                Field result = owner.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class RecordingDevice {
        final List<MemoryBuffer> uploaded = new ArrayList<>();
        int writes;

        final GpuDevice proxy = (GpuDevice) Proxy.newProxyInstance(GpuDevice.class.getClassLoader(),
            new Class<?>[] {GpuDevice.class}, (instance, method, args) -> switch (method.getName()) {
                case "createBuffer" -> args[2] instanceof ByteBuffer bytes
                    ? createBuffer(null, (int) args[1], bytes) : createBuffer(null, (int) args[1], (long) args[2]);
                case "createCommandEncoder" -> createCommandEncoder();
                case "getRenderer" -> "Comet regression fixture";
                default -> throw new AssertionError("Unexpected device call: " + method.getName());
            });

        public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
            return new MemoryBuffer(usage, Math.toIntExact(size));
        }

        public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer source) {
            MemoryBuffer result = new MemoryBuffer(usage, source.remaining());
            result.write(0, source);
            uploaded.add(result);
            return result;
        }

        public CommandEncoder createCommandEncoder() {
            return (CommandEncoder) Proxy.newProxyInstance(CommandEncoder.class.getClassLoader(),
                new Class<?>[] {CommandEncoder.class}, (instance, method, args) -> {
                    if (!method.getName().equals("writeToBuffer"))
                        throw new AssertionError("Unexpected encoder call: " + method.getName());
                    GpuBufferSlice target = (GpuBufferSlice) args[0];
                    ((MemoryBuffer) target.buffer()).write(Math.toIntExact(target.offset()), (ByteBuffer) args[1]);
                    writes++;
                    return null;
                });
        }
    }

    private static final class MemoryBuffer extends GpuBuffer {
        final byte[] data;
        private boolean closed;

        MemoryBuffer(int usage, int size) {
            super(usage, size);
            data = new byte[size];
        }

        void write(int offset, ByteBuffer source) {
            if (closed) throw new IllegalStateException("Buffer already closed");
            source.duplicate().get(data, offset, source.remaining());
        }

        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
    }
}

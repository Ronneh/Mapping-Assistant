import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SketchTest {
    @Test
    void createsTheSameLayoutInAllOrthographicPlanes() {
        SketchDocument document = new SketchDocument();
        SketchShape top = document.addBox(SketchView.TOP, 0, 32, 128, 256);
        SketchShape front = document.addBox(SketchView.FRONT, 0, 64, 128, 192);
        SketchShape side = document.addBox(SketchView.SIDE, 16, 64, 96, 192);

        assertEquals(0, top.minX);
        assertEquals(32, top.minY);
        assertEquals(-64, top.minZ);
        assertEquals(-64, front.minY);
        assertEquals(-64, side.minX);
        assertEquals(16, side.minY);
    }

    @Test
    void savesAndLoadsTheEditableDocument() throws Exception {
        SketchDocument original = new SketchDocument();
        original.grid = 16;
        original.defaultDepth = 96;
        original.addLine(SketchView.TOP, -32, 0, 160, 64);
        original.addBox(SketchView.FRONT, 0, 32, 128, 160);

        Path file = Files.createTempFile("mapping-assistant-sketch", ".json");
        try {
            original.save(file);
            SketchDocument loaded = SketchDocument.load(file);
            assertEquals(16, loaded.grid);
            assertEquals(96, loaded.defaultDepth);
            assertEquals(2, loaded.shapes.size());
            assertEquals(original.shapes.get(1).maxZ, loaded.shapes.get(1).maxZ);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void exportsOnlySolidObjectsAsT3dBrushes() {
        SketchDocument document = new SketchDocument();
        document.addLine(SketchView.TOP, 0, 0, 64, 64);
        document.addBox(SketchView.TOP, 0, 0, 128, 128);

        String t3d = SketchT3dExporter.export(document);
        assertTrue(t3d.contains("Begin Actor Class=Brush Name=SketchBox2"));
        assertTrue(t3d.contains("Vertex   +00000.000000,+00000.000000,-00064.000000"));
        assertTrue(t3d.contains("Begin PolyList"));
        assertTrue(!t3d.contains("Guide 1"));
    }

    @Test
    void solidCornersCanMoveIndependentlyForRamps() {
        SketchDocument document = new SketchDocument();
        SketchShape ramp = document.addBox(SketchView.SIDE, 0, 0, 128, 128);
        ramp.vertexData()[4][2] = 32;
        ramp.vertexData()[5][2] = 32;
        ramp.refreshBounds();

        assertEquals(32, ramp.vertexData()[4][2]);
        assertEquals(128, ramp.maxZ);
        Path file = writeTemporary(document);
        try {
            SketchDocument loaded = SketchDocument.load(file);
            assertEquals(32, loaded.shapes.get(0).vertexData()[4][2]);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        } finally {
            try { Files.deleteIfExists(file); } catch (Exception ignored) { }
        }
    }

    private static Path writeTemporary(SketchDocument document) {
        try {
            Path file = Files.createTempFile("mapping-assistant-ramp", ".json");
            document.save(file);
            return file;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}

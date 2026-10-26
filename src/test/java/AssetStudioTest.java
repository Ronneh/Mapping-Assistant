import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AssetStudioTest {
    @Test
    void demoCubeIsValidAndUsesPerCornerUvs() {
        AssetStudioProject project = AssetStudioProject.createDemoCube();

        assertTrue(project.validate().isEmpty());
        assertEquals(8, project.vertices.size());
        assertEquals(12, project.triangles.size());
        assertArrayEquals(new int[] {0, 255, 255}, project.triangles.get(0).u);
        assertArrayEquals(new int[] {0, 0, 255}, project.triangles.get(0).v);
    }

    @Test
    void lowPolyAnimalUsesEditableObjectsAndFlattensTransformsForExport() {
        AssetStudioProject project = AssetStudioProject.createLowPolyAnimal();
        AssetStudioProject.MeshObject body = project.objects.get(0);
        double originalX = project.flattenedMesh().vertices.get(0).x;

        body.x += 12;
        body.rotationZ = 15;
        body.scaleX = body.scaleY = body.scaleZ = 1.25;
        AssetStudioProject.MeshData flattened = project.flattenedMesh();

        assertTrue(project.objects.size() >= 8);
        assertTrue(project.validate().isEmpty());
        assertEquals(project.objects.stream().mapToInt(object -> object.vertices.size()).sum(),
                flattened.vertices.size());
        assertEquals(project.objects.stream().mapToInt(object -> object.triangles.size()).sum(),
                flattened.triangles.size());
        assertTrue(flattened.vertices.get(0).x != originalX);
    }

    @Test
    void objectEditorPanelCanBeConstructedWithStarterScene() {
        assertDoesNotThrow(AssetStudioPanel::new);
    }

    @Test
    void projectRoundTripsThroughVersionedJson() throws Exception {
        Path folder = Files.createTempDirectory("asset-studio-project");
        Path file = folder.resolve("demo.maasset");
        AssetStudioProject original = AssetStudioProject.createDemoCube();
        original.unrealTournamentRoot = "C:\\Games\\UT";
        original.save(file);

        AssetStudioProject loaded = AssetStudioProject.load(file);

        assertEquals(AssetStudioProject.CURRENT_FORMAT_VERSION, loaded.formatVersion);
        assertEquals(original.packageName, loaded.packageName);
        assertEquals(original.unrealTournamentRoot, loaded.unrealTournamentRoot);
        assertEquals(original.vertices.size(), loaded.vertices.size());
        assertEquals(original.triangles.size(), loaded.triangles.size());
        assertArrayEquals(original.triangles.get(5).vertexIndices, loaded.triangles.get(5).vertexIndices);
    }

    @Test
    void exporterWritesClassicUt99SourceFiles() throws Exception {
        Path root = Files.createTempDirectory("ut99-root");
        AssetStudioProject project = AssetStudioProject.createDemoCube();

        Ut99AssetExporter.ExportResult result = Ut99AssetExporter.export(project, root);

        assertTrue(Files.isRegularFile(result.dataFile()));
        assertTrue(Files.isRegularFile(result.animationFile()));
        assertTrue(Files.isRegularFile(result.textureFile()));
        assertTrue(Files.isRegularFile(result.classFile()));
        assertEquals(48 + 12 * 16, Files.size(result.dataFile()));
        assertEquals(4 + 8 * 4, Files.size(result.animationFile()));
        byte[] data = Files.readAllBytes(result.dataFile());
        assertEquals(12, unsignedShort(data, 0));
        assertEquals(8, unsignedShort(data, 2));
        assertTrue(Files.readString(result.classFile()).contains("#exec MESH IMPORT"));
        assertTrue(Files.readString(result.classFile()).contains("#exec TEXTURE IMPORT"));
        assertTrue(Files.readString(result.classFile()).contains("FILE=MODELS\\AssetStudioTexture.pcx"));
        assertEquals(0x0A, Files.readAllBytes(result.textureFile())[0] & 0xFF);
    }

    @Test
    void invalidTriangleIsRejectedBeforeExport() throws Exception {
        AssetStudioProject project = AssetStudioProject.createDemoCube();
        project.triangles.get(0).vertexIndices[0] = 999;

        List<String> errors = project.validate();

        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("outside the mesh"));
    }

    @Test
    void editPackagesEntryIsAddedOnlyOnce() throws Exception {
        Path root = Files.createTempDirectory("ut99-config");
        Path system = Files.createDirectories(root.resolve("System"));
        Path ini = system.resolve("UnrealTournament.ini");
        Files.writeString(ini, "EditPackages=Core\r\nEditPackages=Engine\r\n");

        assertFalse(Ut99AssetExporter.isPackageListed(root, "AssetStudioDemo"));
        Ut99AssetExporter.addPackageToEditPackages(root, "AssetStudioDemo");
        Ut99AssetExporter.addPackageToEditPackages(root, "AssetStudioDemo");

        String contents = Files.readString(ini);
        assertEquals(1, contents.lines()
                .filter(line -> line.trim().equals("EditPackages=AssetStudioDemo")).count());
    }

    @Test
    void importedTextureIsEmbeddedAndExportedAsPcx() throws Exception {
        AssetStudioProject project = AssetStudioProject.createDemoCube();
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        image.setRGB(1, 0, Color.GREEN.getRGB());
        image.setRGB(0, 1, Color.BLUE.getRGB());
        image.setRGB(1, 1, Color.WHITE.getRGB());
        project.setTexture(image, "custom.jpg");
        Path folder = Files.createTempDirectory("ut99-custom-texture");

        AssetStudioProject loaded;
        Path projectFile = folder.resolve("custom.maasset");
        project.save(projectFile);
        loaded = AssetStudioProject.load(projectFile);
        Ut99AssetExporter.ExportResult result = Ut99AssetExporter.export(loaded, folder);

        assertTrue(loaded.hasImportedTexture());
        assertEquals("custom.jpg", loaded.textureOriginalFileName);
        assertEquals(0x0A, Files.readAllBytes(result.textureFile())[0] & 0xFF);
        assertTrue(Files.size(result.textureFile()) > 128 + 32 * 32,
                "The exported PCX should contain a header, pixel data, and palette.");
    }

    @Test
    void nonPowerOfTwoTextureIsRejected() {
        AssetStudioProject project = AssetStudioProject.createDemoCube();
        BufferedImage image = new BufferedImage(30, 32, BufferedImage.TYPE_INT_RGB);

        assertThrows(java.io.IOException.class, () -> project.setTexture(image, "invalid.png"));
        assertTrue(AssetStudioProject.isValidUedTextureSize(64, 128));
        assertFalse(AssetStudioProject.isValidUedTextureSize(30, 32));
    }

    @Test
    void multipleTextureSlotsExportAndCanBeAssignedPerFace() throws Exception {
        AssetStudioProject project = AssetStudioProject.createDemoCube();
        project.setTexture(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "first.png");
        int second = project.addTexture(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "second.png");
        project.objects.get(0).triangles.get(0).textureIndex = second;
        Path folder = Files.createTempDirectory("ut99-multiple-textures");

        Ut99AssetExporter.ExportResult result = Ut99AssetExporter.export(project, folder);

        assertTrue(Files.isRegularFile(result.packageRoot().resolve(Path.of("Models", "AssetStudioTexture.pcx"))));
        assertTrue(Files.isRegularFile(result.packageRoot().resolve(Path.of("Models", "AssetStudioTexture1.pcx"))));
        String script = Files.readString(result.classFile());
        assertTrue(script.contains("NUM=1 TEXTURE=AssetStudioTexture1"));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }
}

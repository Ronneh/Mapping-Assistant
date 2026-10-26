import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/** Writes the classic Unreal 1 mesh import files used by UT99's #exec MESH IMPORT. */
public final class Ut99AssetExporter {
    private static final int DATA_HEADER_SIZE = 48;
    private static final int TRIANGLE_SIZE = 16;

    private Ut99AssetExporter() { }

    public static ExportResult export(AssetStudioProject project, Path unrealTournamentRoot)
            throws IOException {
        List<String> errors = project.validate();
        if (!errors.isEmpty()) throw new IOException(String.join(System.lineSeparator(), errors));
        if (unrealTournamentRoot == null)
            throw new IOException("Choose the UT99 installation root first.");

        Path root = unrealTournamentRoot.toAbsolutePath().normalize();
        Path packageRoot = root.resolve(project.packageName);
        Path models = packageRoot.resolve("Models");
        Path classes = packageRoot.resolve("Classes");
        Files.createDirectories(models);
        Files.createDirectories(classes);

        Path dataFile = models.resolve(project.meshName + "_d.3d");
        Path animationFile = models.resolve(project.meshName + "_a.3d");
        project.ensureTextureSlots();
        project.normalizeTextureNames();
        Path textureFile = models.resolve(project.textureName + ".pcx");
        Path classFile = classes.resolve(project.actorClassName + ".uc");
        writeDataFile(project, dataFile);
        writeAnimationFile(project, animationFile);
        writeTextures(project, models, textureFile);
        Files.writeString(classFile, createUnrealScript(project), StandardCharsets.US_ASCII);
        Files.writeString(packageRoot.resolve("AssetStudio-build-notes.txt"),
                buildNotes(project, root), StandardCharsets.UTF_8);
        Files.writeString(packageRoot.resolve("compile-asset-studio.cmd"),
                compileScript(project, root), StandardCharsets.US_ASCII);
        return new ExportResult(packageRoot, dataFile, animationFile, textureFile, classFile);
    }

    public static CompileResult compile(AssetStudioProject project, Path unrealTournamentRoot,
                                        Path uccExecutable) throws IOException, InterruptedException {
        ExportResult exported = export(project, unrealTournamentRoot);
        if (uccExecutable == null || !Files.isRegularFile(uccExecutable))
            throw new IOException("UCC executable was not found: " + uccExecutable);

        Path system = findSystemDirectory(unrealTournamentRoot, uccExecutable);
        Path expectedPackage = system.resolve(project.packageName + ".u");
        Process process = new ProcessBuilder(uccExecutable.toString(), "make")
                .directory(system.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        Path packageFile = expectedPackage;
        if (!Files.isRegularFile(packageFile)) {
            Path rootPackageFile = unrealTournamentRoot.resolve(project.packageName + ".u");
            if (Files.isRegularFile(rootPackageFile)) packageFile = rootPackageFile;
        }
        boolean compilerProcessedPackage = output.lines()
                .anyMatch(line -> line.contains(project.packageName));
        boolean success = exitCode == 0 && Files.isRegularFile(packageFile) && compilerProcessedPackage;
        return new CompileResult(exported, success, exitCode, output, packageFile);
    }

    public static Path unrealTournamentIni(Path unrealTournamentRoot) {
        return unrealTournamentRoot.resolve("System").resolve("UnrealTournament.ini");
    }

    public static boolean isPackageListed(Path unrealTournamentRoot, String packageName) throws IOException {
        Path ini = unrealTournamentIni(unrealTournamentRoot);
        if (!Files.isRegularFile(ini)) return false;
        String contents = Files.readString(ini, Charset.defaultCharset());
        String target = "EditPackages=" + packageName;
        List<String> lines = Arrays.asList(contents.split("\\R", -1));
        int editorSection = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase("[Editor.EditorEngine]")) {
                editorSection = i;
                break;
            }
        }
        if (editorSection < 0) return lines.stream().anyMatch(line -> line.trim().equals(target));
        for (int i = editorSection + 1; i < lines.size() && !lines.get(i).trim().startsWith("["); i++)
            if (lines.get(i).trim().equals(target)) return true;
        return false;
    }

    public static void addPackageToEditPackages(Path unrealTournamentRoot, String packageName)
            throws IOException {
        Path ini = unrealTournamentIni(unrealTournamentRoot);
        if (!Files.isRegularFile(ini))
            throw new IOException("UnrealTournament.ini was not found: " + ini);
        Charset charset = Charset.defaultCharset();
        String contents = Files.readString(ini, charset);
        String newline = contents.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(contents.split("\\R", -1)));
        int editorSection = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase("[Editor.EditorEngine]")) {
                editorSection = i;
                break;
            }
        }
        if (editorSection >= 0) {
            int sectionEnd = editorSection + 1;
            while (sectionEnd < lines.size() && !lines.get(sectionEnd).trim().startsWith("[")) sectionEnd++;
            boolean listedInEditor = false;
            for (int i = editorSection + 1; i < sectionEnd; i++)
                if (lines.get(i).trim().equals("EditPackages=" + packageName)) listedInEditor = true;
            if (listedInEditor) return;
            lines.removeIf(line -> line.trim().equals("EditPackages=" + packageName));
            editorSection = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[Editor.EditorEngine]")) {
                    editorSection = i;
                    break;
                }
            }
            int insertAt = editorSection + 1;
            while (insertAt < lines.size() && !lines.get(insertAt).trim().startsWith("[")) insertAt++;
            while (insertAt > editorSection + 1 && lines.get(insertAt - 1).isBlank()) insertAt--;
            lines.add(insertAt, "EditPackages=" + packageName);
            Files.writeString(ini, String.join(newline, lines), charset);
        } else {
            if (contents.lines().anyMatch(line -> line.trim().equals("EditPackages=" + packageName))) return;
            String separator = contents.endsWith("\n") || contents.endsWith("\r") ? "" : newline;
            Files.writeString(ini, contents + separator + "EditPackages=" + packageName + newline, charset);
        }
    }

    private static Path findSystemDirectory(Path root, Path ucc) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedUcc = ucc.toAbsolutePath().normalize();
        Path rootSystem = normalizedRoot.resolve("System");
        if (Files.isDirectory(rootSystem)) return rootSystem;
        Path parent = normalizedUcc.getParent();
        if (parent != null && Files.isDirectory(parent)) return parent;
        throw new IOException("Could not determine the UT99 System directory.");
    }

    private static void writeDataFile(AssetStudioProject project, Path file) throws IOException {
        AssetStudioProject.MeshData mesh = project.flattenedMesh();
        try (OutputStream output = Files.newOutputStream(file)) {
            writeLittleEndianShort(output, mesh.triangles.size());
            writeLittleEndianShort(output, mesh.vertices.size());
            output.write(new byte[DATA_HEADER_SIZE - 4]);
            for (AssetStudioProject.Triangle triangle : mesh.triangles) {
                for (int vertexIndex : triangle.vertexIndices) writeLittleEndianShort(output, vertexIndex);
                output.write(0); // Normal, one-sided triangle.
                output.write(0); // Flat/Gouraud color byte.
                for (int corner = 0; corner < 3; corner++) {
                    output.write(triangle.u[corner]);
                    output.write(triangle.v[corner]);
                }
                output.write(triangle.textureIndex);
                output.write(0); // Reserved mesh flags.
            }
        }
    }

    private static void writeAnimationFile(AssetStudioProject project, Path file) throws IOException {
        AssetStudioProject.MeshData mesh = project.flattenedMesh();
        try (OutputStream output = Files.newOutputStream(file)) {
            writeLittleEndianShort(output, 1); // One static frame for this first workflow.
            writeLittleEndianShort(output, mesh.vertices.size() * 4);
            for (AssetStudioProject.Vertex vertex : mesh.vertices) {
                int x = packSigned(vertex.x * 8.0, 11);
                int y = packSigned(vertex.y * 8.0, 11);
                int z = packSigned(vertex.z * 4.0, 10);
                long packed = (x & 0x7FFL) | ((y & 0x7FFL) << 11) | ((z & 0x3FFL) << 22);
                writeLittleEndianInt(output, packed);
            }
        }
    }

    private static int packSigned(double value, int bits) {
        long rounded = Math.round(value);
        long min = -(1L << (bits - 1));
        long max = (1L << (bits - 1)) - 1;
        if (rounded < min || rounded > max)
            throw new IllegalArgumentException("Mesh coordinate exceeds the UT99 packed vertex range.");
        return (int) rounded;
    }

    private static void writeTexture(AssetStudioProject project, Path file) throws IOException {
        BufferedImage image = project.loadTexture();
        if (image == null) image = createDemoTexture();
        writePcx(image, file);
    }

    private static void writeTextures(AssetStudioProject project, Path models, Path primaryFile)
            throws IOException {
        if (project.textures == null || project.textures.isEmpty()) {
            writeTexture(project, primaryFile);
            return;
        }
        for (AssetStudioProject.TextureAsset texture : project.textures)
            writePcx(project.loadTexture(project.textures.indexOf(texture)),
                    models.resolve(texture.name + ".pcx"));
    }

    private static BufferedImage createDemoTexture() {
        final int width = 256;
        final int height = 256;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean grid = x % 32 == 0 || y % 32 == 0;
                boolean light = ((x / 32 + y / 32) & 1) == 0;
                image.setRGB(x, y, grid ? 0xEBEBEB : light ? 0x1E6EAA : 0x46AADC);
            }
        }
        return image;
    }

    private static void writePcx(BufferedImage source, Path file) throws IOException {
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (!AssetStudioProject.isValidUedTextureSize(width, height))
            throw new IOException("Texture dimensions are not valid for UT99: " + width + "x" + height);
        final int bytesPerLine = (width + 1) & ~1;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(128 + bytesPerLine * height + 769);
        output.write(0x0A); // ZSoft PCX manufacturer.
        output.write(5); // PCX version.
        output.write(1); // RLE encoding.
        output.write(8); // 8 bits per pixel.
        writeLittleEndianShort(output, 0);
        writeLittleEndianShort(output, 0);
        writeLittleEndianShort(output, width - 1);
        writeLittleEndianShort(output, height - 1);
        writeLittleEndianShort(output, width);
        writeLittleEndianShort(output, height);
        output.write(new byte[48]);
        output.write(0);
        output.write(1); // One color plane.
        writeLittleEndianShort(output, bytesPerLine);
        writeLittleEndianShort(output, 1);
        writeLittleEndianShort(output, width);
        writeLittleEndianShort(output, height);
        output.write(new byte[54]);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xFF;
                int green = (rgb >>> 8) & 0xFF;
                int blue = rgb & 0xFF;
                int value = ((red * 7 + 127) / 255 << 5)
                        | ((green * 7 + 127) / 255 << 2)
                        | ((blue * 3 + 127) / 255);
                if (value >= 0xC0) output.write(0xC1);
                output.write(value);
            }
            if (bytesPerLine > width) output.write(0);
        }
        output.write(0x0C);
        for (int index = 0; index < 256; index++) {
            output.write(((index >>> 5) & 7) * 255 / 7);
            output.write(((index >>> 2) & 7) * 255 / 7);
            output.write((index & 3) * 255 / 3);
        }
        Files.write(file, output.toByteArray());
    }

    private static String createUnrealScript(AssetStudioProject project) {
        String modelPath = "MODELS\\" + project.meshName;
        StringBuilder script = new StringBuilder("class ").append(project.actorClassName)
                .append(" extends Actor;\n\n")
                .append("#exec MESH IMPORT MESH=").append(project.meshName)
                .append(" ANIVFILE=").append(modelPath).append("_a.3d DATAFILE=").append(modelPath)
                .append("_d.3d X=0 Y=0 Z=0\n")
                .append("#exec MESH ORIGIN MESH=").append(project.meshName).append(" X=0 Y=0 Z=0\n")
                .append("#exec MESH SEQUENCE MESH=").append(project.meshName)
                .append(" SEQ=All STARTFRAME=0 NUMFRAMES=1\n")
                .append("#exec MESH SEQUENCE MESH=").append(project.meshName)
                .append(" SEQ=Still STARTFRAME=0 NUMFRAMES=1\n");
        if (project.textures == null || project.textures.isEmpty()) {
            script.append("#exec TEXTURE IMPORT NAME=").append(project.textureName)
                    .append(" FILE=MODELS\\").append(project.textureName).append(".pcx GROUP=\"Skins\"\n");
        } else {
            for (AssetStudioProject.TextureAsset texture : project.textures)
                script.append("#exec TEXTURE IMPORT NAME=").append(texture.name)
                        .append(" FILE=MODELS\\").append(texture.name).append(".pcx GROUP=\"Skins\"\n");
        }
        script.append("#exec MESHMAP NEW MESHMAP=").append(project.meshName).append(" MESH=")
                .append(project.meshName).append("\n")
                .append("#exec MESHMAP SCALE MESHMAP=").append(project.meshName)
                .append(" X=1.0 Y=1.0 Z=1.0\n");
        if (project.textures == null || project.textures.isEmpty()) {
            script.append("#exec MESHMAP SETTEXTURE MESHMAP=").append(project.meshName)
                    .append(" NUM=0 TEXTURE=").append(project.textureName).append("\n");
        } else {
            for (int index = 0; index < project.textures.size(); index++)
                script.append("#exec MESHMAP SETTEXTURE MESHMAP=").append(project.meshName)
                        .append(" NUM=").append(index).append(" TEXTURE=")
                        .append(project.textures.get(index).name).append("\n");
        }
        return script.append("\ndefaultproperties\n{\n")
                .append("    DrawType=DT_Mesh\n")
                .append("    Mesh=Mesh'").append(project.packageName).append(".").append(project.meshName)
                .append("'\n}\n").toString();
    }

    private static String buildNotes(AssetStudioProject project, Path root) {
        return "Asset Studio minimal UT99 export\n"
                + "Package source: " + root.resolve(project.packageName) + "\n"
                + "The package folder contains Classes, Models, and the generated PCX texture.\n"
                + "Classic UCC builds packages listed with EditPackages in UnrealTournament.ini.\n"
                + "If UCC does not discover this package automatically, add:\n"
                + "EditPackages=" + project.packageName + "\n"
                + "Then remove the old System\\" + project.packageName + ".u and run compile-asset-studio.cmd again.\n";
    }

    private static String compileScript(AssetStudioProject project, Path root) {
        return "@echo off\r\n"
                + "cd /d \"" + root.resolve("System") + "\"\r\n"
                + "ucc.exe make\r\n"
                + "if errorlevel 1 (echo UCC failed & exit /b %errorlevel%)\r\n"
                + "if not exist \"" + root.resolve("System").resolve(project.packageName + ".u")
                + "\" (echo Package was not created & exit /b 1)\r\n"
                + "echo Built " + project.packageName + ".u\r\n";
    }

    private static void writeLittleEndianShort(OutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void writeLittleEndianInt(OutputStream output, long value) throws IOException {
        output.write((int) value & 0xFF);
        output.write((int) (value >>> 8) & 0xFF);
        output.write((int) (value >>> 16) & 0xFF);
        output.write((int) (value >>> 24) & 0xFF);
    }

    public record ExportResult(Path packageRoot, Path dataFile, Path animationFile,
                               Path textureFile, Path classFile) { }

    public record CompileResult(ExportResult export, boolean success, int exitCode,
                                String output, Path packageFile) { }
}

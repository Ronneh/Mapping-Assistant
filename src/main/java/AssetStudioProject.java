import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Versioned, self-contained source model for the Asset Studio workflow. */
public final class AssetStudioProject {
    public static final int CURRENT_FORMAT_VERSION = 4;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public int formatVersion = CURRENT_FORMAT_VERSION;
    public String packageName = "AssetStudioDemo";
    public String actorClassName = "AssetStudioDemoActor";
    public String meshName = "AssetStudioFigure";
    public String textureName = "AssetStudioTexture";
    public String uccPath = "";
    public String unrealTournamentRoot = "";
    public String textureOriginalFileName = "";
    public String textureImageBase64 = "";
    public int textureWidth;
    public int textureHeight;
    /** Legacy flattened data kept for compatibility with version 1/2 projects and tests. */
    public List<Vertex> vertices = new ArrayList<>();
    public List<Triangle> triangles = new ArrayList<>();
    /** Editable scene objects. Their vertices are local to each object. */
    public List<MeshObject> objects = new ArrayList<>();
    /** Imported textures available to face material assignments. */
    public List<TextureAsset> textures = new ArrayList<>();

    public static AssetStudioProject createDemoCube() {
        AssetStudioProject project = new AssetStudioProject();
        project.objects.add(createBoxObject("Cube", 32, 32, 32, 0, 0, 0));
        project.syncLegacyFromObjects();
        project.meshName = "AssetStudioCube";
        return project;
    }

    public static AssetStudioProject createLowPolyFigure() {
        AssetStudioProject project = new AssetStudioProject();
        project.objects.add(createBoxObject("Body", 42, 20, 24, 0, 15, 0));
        project.objects.add(createBoxObject("Head", 22, 19, 20, 27, 25, 0));
        project.objects.add(createBoxObject("Snout", 13, 15, 10, 42, 20, 0));
        project.objects.add(createBoxObject("FrontLeftLeg", 8, 8, 18, 14, 0, -6));
        project.objects.add(createBoxObject("FrontRightLeg", 8, 8, 18, 14, 0, 6));
        project.objects.add(createBoxObject("BackLeftLeg", 8, 8, 18, -14, 0, -6));
        project.objects.add(createBoxObject("BackRightLeg", 8, 8, 18, -14, 0, 6));
        project.objects.add(createPyramidObject("EarLeft", 9, 9, 13, 24, 43, -6));
        project.objects.add(createPyramidObject("EarRight", 9, 9, 13, 24, 43, 6));
        MeshObject tail = createBoxObject("Tail", 18, 7, 7, -28, 24, 0);
        tail.rotationZ = -24;
        project.objects.add(tail);
        project.meshName = "AssetStudioFigure";
        project.syncLegacyFromObjects();
        return project;
    }

    /** Kept for compatibility with earlier development builds. */
    @Deprecated
    public static AssetStudioProject createLowPolyAnimal() {
        return createLowPolyFigure();
    }

    public static MeshObject createBoxObject(String name, double width, double depth,
                                              double height, double x, double y, double z) {
        MeshObject object = new MeshObject();
        object.name = name;
        object.primitiveType = "box";
        object.x = x;
        object.y = y;
        object.z = z;
        object.vertices.addAll(List.of(
                new Vertex(-width / 2, -height / 2, -depth / 2),
                new Vertex(width / 2, -height / 2, -depth / 2),
                new Vertex(width / 2, height / 2, -depth / 2),
                new Vertex(-width / 2, height / 2, -depth / 2),
                new Vertex(-width / 2, -height / 2, depth / 2),
                new Vertex(width / 2, -height / 2, depth / 2),
                new Vertex(width / 2, height / 2, depth / 2),
                new Vertex(-width / 2, height / 2, depth / 2)));
        addQuad(object, 0, 1, 5, 4);
        addQuad(object, 3, 7, 6, 2);
        addQuad(object, 0, 4, 7, 3);
        addQuad(object, 1, 2, 6, 5);
        addQuad(object, 0, 3, 2, 1);
        addQuad(object, 4, 5, 6, 7);
        return object;
    }

    public static MeshObject createPyramidObject(String name, double width, double depth,
                                                  double height, double x, double y, double z) {
        MeshObject object = new MeshObject();
        object.name = name;
        object.primitiveType = "pyramid";
        object.x = x;
        object.y = y;
        object.z = z;
        object.vertices.addAll(List.of(
                new Vertex(-width / 2, 0, -depth / 2),
                new Vertex(width / 2, 0, -depth / 2),
                new Vertex(width / 2, 0, depth / 2),
                new Vertex(-width / 2, 0, depth / 2),
                new Vertex(0, height, 0)));
        object.triangles.add(Triangle.withUv(0, 1, 2, 0, 0, 255, 0, 255, 255));
        object.triangles.add(Triangle.withUv(0, 2, 3, 0, 0, 255, 255, 0, 255));
        object.triangles.add(Triangle.withUv(0, 4, 1, 0, 255, 128, 0, 255, 255));
        object.triangles.add(Triangle.withUv(1, 4, 2, 0, 255, 128, 0, 255, 255));
        object.triangles.add(Triangle.withUv(2, 4, 3, 0, 255, 128, 0, 255, 255));
        object.triangles.add(Triangle.withUv(3, 4, 0, 0, 255, 128, 0, 255, 255));
        return object;
    }

    public boolean hasImportedTexture() {
        return (textures != null && !textures.isEmpty())
                || (textureImageBase64 != null && !textureImageBase64.isBlank());
    }

    public static boolean isValidUedTextureSize(int width, int height) {
        return width >= 32 && height >= 32 && isPowerOfTwo(width) && isPowerOfTwo(height);
    }

    private static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0;
    }

    public void setTexture(BufferedImage source, String originalFileName) throws IOException {
        if (source == null) throw new IOException("The selected file does not contain a readable image.");
        if (!isValidUedTextureSize(source.getWidth(), source.getHeight()))
            throw new IOException("UT99 textures must use power-of-two dimensions of at least 32 pixels, "
                    + "for example 32x32, 64x128, or 256x256. The selected image is "
                    + source.getWidth() + "x" + source.getHeight() + ".");
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        TextureAsset asset = newTextureAsset(rgb, originalFileName, 0);
        textureImageBase64 = asset.imageBase64;
        textureOriginalFileName = asset.originalFileName;
        textureWidth = asset.width;
        textureHeight = asset.height;
        if (textures == null) textures = new ArrayList<>();
        if (textures.isEmpty()) textures.add(asset);
        else textures.set(0, asset);
    }

    public BufferedImage loadTexture() throws IOException {
        return loadTexture(0);
    }

    public BufferedImage loadTexture(int textureIndex) throws IOException {
        if (textures != null && !textures.isEmpty()) {
            if (textureIndex < 0 || textureIndex >= textures.size())
                throw new IOException("Texture slot " + textureIndex + " does not exist.");
            return decodeTexture(textures.get(textureIndex).imageBase64);
        }
        if (!hasImportedTexture()) return null;
        return decodeTexture(textureImageBase64);
    }

    public int addTexture(BufferedImage source, String originalFileName) throws IOException {
        if (source == null) throw new IOException("The selected file does not contain a readable image.");
        if (!isValidUedTextureSize(source.getWidth(), source.getHeight()))
            throw new IOException("UT99 textures must use power-of-two dimensions of at least 32 pixels, "
                    + "for example 32x32, 64x128, or 256x256. The selected image is "
                    + source.getWidth() + "x" + source.getHeight() + ".");
        if (textures == null) textures = new ArrayList<>();
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        TextureAsset asset = newTextureAsset(rgb, originalFileName, textures.size());
        textures.add(asset);
        if (textures.size() == 1) {
            textureImageBase64 = asset.imageBase64;
            textureOriginalFileName = asset.originalFileName;
            textureWidth = asset.width;
            textureHeight = asset.height;
        }
        return textures.size() - 1;
    }

    public void ensureTextureSlots() throws IOException {
        if (textures != null && !textures.isEmpty()) return;
        if (textureImageBase64 == null || textureImageBase64.isBlank()) return;
        TextureAsset asset = new TextureAsset();
        asset.name = textureName;
        asset.originalFileName = textureOriginalFileName;
        asset.imageBase64 = textureImageBase64;
        asset.width = textureWidth;
        asset.height = textureHeight;
        if (asset.width <= 0 || asset.height <= 0) {
            BufferedImage image = decodeTexture(asset.imageBase64);
            asset.width = image.getWidth();
            asset.height = image.getHeight();
        }
        textures = new ArrayList<>();
        textures.add(asset);
    }

    public void normalizeTextureNames() {
        if (textures == null || textures.isEmpty()) return;
        for (int index = 0; index < textures.size(); index++)
            textures.get(index).name = index == 0 ? textureName : textureName + index;
    }

    private TextureAsset newTextureAsset(BufferedImage source, String originalFileName, int index)
            throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(source, "png", png)) throw new IOException("PNG encoding is not available.");
        TextureAsset asset = new TextureAsset();
        asset.name = index == 0 ? textureName : textureName + index;
        asset.originalFileName = originalFileName == null ? "imported-texture" : originalFileName;
        asset.imageBase64 = Base64.getEncoder().encodeToString(png.toByteArray());
        asset.width = source.getWidth();
        asset.height = source.getHeight();
        return asset;
    }

    private static BufferedImage decodeTexture(String encoded) throws IOException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IOException("The embedded texture data is invalid.", exception);
        }
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("The embedded texture cannot be decoded.");
        return image;
    }

    /** Returns a single export mesh after applying every object transformation. */
    public MeshData flattenedMesh() {
        ensureSceneObjects();
        return flattenObjects();
    }

    public void syncLegacyFromObjects() {
        if (objects == null || objects.isEmpty()) {
            vertices = new ArrayList<>();
            triangles = new ArrayList<>();
            return;
        }
        MeshData flattened = flattenObjects();
        vertices = flattened.vertices;
        triangles = flattened.triangles;
    }

    private MeshData flattenObjects() {
        MeshData flattened = new MeshData();
        if (objects == null) objects = new ArrayList<>();
        for (MeshObject object : objects) {
            if (object == null || object.vertices == null || object.triangles == null) continue;
            int offset = flattened.vertices.size();
            for (Vertex vertex : object.vertices) flattened.vertices.add(transform(object, vertex));
            for (Triangle triangle : object.triangles) {
                if (triangle == null || triangle.vertexIndices == null || triangle.vertexIndices.length != 3) continue;
                Triangle copy = new Triangle();
                copy.vertexIndices = new int[] {triangle.vertexIndices[0] + offset,
                        triangle.vertexIndices[1] + offset, triangle.vertexIndices[2] + offset};
                copy.u = triangle.u == null ? new int[] {0, 255, 255} : triangle.u.clone();
                copy.v = triangle.v == null ? new int[] {0, 0, 255} : triangle.v.clone();
                copy.textureIndex = triangle.textureIndex;
                flattened.triangles.add(copy);
            }
        }
        return flattened;
    }

    public void ensureSceneObjects() {
        if (objects != null && !objects.isEmpty()) return;
        if (vertices == null || vertices.isEmpty()) return;
        MeshObject legacy = new MeshObject();
        legacy.name = "LegacyMesh";
        legacy.primitiveType = "mesh";
        legacy.vertices.addAll(vertices);
        if (triangles != null) legacy.triangles.addAll(triangles);
        objects = new ArrayList<>();
        objects.add(legacy);
    }

    public static Vertex transform(MeshObject object, Vertex vertex) {
        double x = vertex.x * object.scaleX;
        double y = vertex.y * object.scaleY;
        double z = vertex.z * object.scaleZ;
        double rx = Math.toRadians(object.rotationX);
        double ry = Math.toRadians(object.rotationY);
        double rz = Math.toRadians(object.rotationZ);
        double cos = Math.cos(rx), sin = Math.sin(rx);
        double nextY = y * cos - z * sin;
        double nextZ = y * sin + z * cos;
        y = nextY; z = nextZ;
        cos = Math.cos(ry); sin = Math.sin(ry);
        double nextX = x * cos + z * sin;
        nextZ = -x * sin + z * cos;
        x = nextX; z = nextZ;
        cos = Math.cos(rz); sin = Math.sin(rz);
        nextX = x * cos - y * sin;
        nextY = x * sin + y * cos;
        return new Vertex(nextX + object.x, nextY + object.y, z + object.z);
    }

    public void save(Path path) throws IOException {
        formatVersion = CURRENT_FORMAT_VERSION;
        if (objects != null && !objects.isEmpty()) syncLegacyFromObjects();
        ensureTextureSlots();
        normalizeTextureNames();
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), this);
    }

    public static AssetStudioProject load(Path path) throws IOException {
        AssetStudioProject project = JSON.readValue(path.toFile(), AssetStudioProject.class);
        if (project.formatVersion < 1 || project.formatVersion > CURRENT_FORMAT_VERSION)
            throw new IOException("Unsupported Asset Studio project version: " + project.formatVersion);
        if (project.objects == null) project.objects = new ArrayList<>();
        if (project.textures == null) project.textures = new ArrayList<>();
        if (project.formatVersion < 3) project.ensureSceneObjects();
        project.formatVersion = CURRENT_FORMAT_VERSION;
        return project;
    }

    /** Creates an in-memory snapshot used by the editor undo/redo history. */
    public AssetStudioProject copy() {
        return JSON.convertValue(this, AssetStudioProject.class);
    }

    public String snapshotSignature() {
        try {
            return JSON.writeValueAsString(this);
        } catch (IOException exception) {
            return Integer.toHexString(System.identityHashCode(this));
        }
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        validateIdentifier(errors, "Package name", packageName);
        validateIdentifier(errors, "Actor class name", actorClassName);
        validateIdentifier(errors, "Mesh name", meshName);
        validateIdentifier(errors, "Texture name", textureName);
        MeshData mesh = flattenedMesh();
        validateMesh(errors, mesh.vertices, mesh.triangles);
        // Keep the compatibility view validated as well so old callers cannot silently bypass errors.
        if (vertices != null && triangles != null && (vertices != mesh.vertices || triangles != mesh.triangles))
            validateMesh(errors, vertices, triangles);
        if (objects != null) {
            for (int i = 0; i < objects.size(); i++) {
                MeshObject object = objects.get(i);
                if (object == null || object.vertices == null || object.triangles == null)
                    errors.add("Object " + i + " is incomplete.");
                else if (textures != null && !textures.isEmpty()) {
                    for (int triangleIndex = 0; triangleIndex < object.triangles.size(); triangleIndex++) {
                        Triangle triangle = object.triangles.get(triangleIndex);
                        if (triangle != null && (triangle.textureIndex < 0
                                || triangle.textureIndex >= textures.size()))
                            errors.add("Object " + i + ", triangle " + triangleIndex
                                    + " references texture slot " + triangle.textureIndex + " outside the project.");
                    }
                }
            }
        }
        return errors;
    }

    private static void validateMesh(List<String> errors, List<Vertex> vertices, List<Triangle> triangles) {
        if (vertices == null || vertices.size() < 3) errors.add("The mesh needs at least three vertices.");
        if (triangles == null || triangles.isEmpty()) errors.add("The mesh needs at least one triangle.");
        if (vertices == null || triangles == null) return;
        for (int i = 0; i < triangles.size(); i++) {
            Triangle triangle = triangles.get(i);
            if (triangle == null || triangle.vertexIndices == null || triangle.vertexIndices.length != 3) {
                errors.add("Triangle " + i + " does not have exactly three vertex indices.");
                continue;
            }
            for (int vertexIndex : triangle.vertexIndices) {
                if (vertexIndex < 0 || vertexIndex >= vertices.size()) {
                    errors.add("Triangle " + i + " references vertex " + vertexIndex + " outside the mesh.");
                    break;
                }
            }
            if (triangle.u == null || triangle.v == null || triangle.u.length != 3 || triangle.v.length != 3)
                errors.add("Triangle " + i + " does not have three UV pairs.");
            else for (int corner = 0; corner < 3; corner++)
                if (triangle.u[corner] < 0 || triangle.u[corner] > 255
                        || triangle.v[corner] < 0 || triangle.v[corner] > 255)
                    errors.add("Triangle " + i + " has UV coordinates outside 0..255.");
        }
    }

    private static void validateIdentifier(List<String> errors, String label, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches())
            errors.add(label + " must start with a letter and contain only letters, digits, or underscores.");
    }

    private static void addQuad(MeshObject object, int a, int b, int c, int d) {
        object.triangles.add(Triangle.withUv(a, b, c, 0, 0, 255, 0, 255, 255));
        object.triangles.add(Triangle.withUv(a, c, d, 0, 0, 255, 255, 0, 255));
    }

    public static final class MeshData {
        public final List<Vertex> vertices = new ArrayList<>();
        public final List<Triangle> triangles = new ArrayList<>();
    }

    public static final class TextureAsset {
        public String name = "AssetStudioTexture";
        public String originalFileName = "";
        public String imageBase64 = "";
        public int width;
        public int height;
    }

    public static final class MeshObject {
        public String name = "Object";
        public String primitiveType = "mesh";
        public List<Vertex> vertices = new ArrayList<>();
        public List<Triangle> triangles = new ArrayList<>();
        public double x;
        public double y;
        public double z;
        public double rotationX;
        public double rotationY;
        public double rotationZ;
        public double scaleX = 1;
        public double scaleY = 1;
        public double scaleZ = 1;

        @Override public String toString() {
            return name == null || name.isBlank() ? "Unnamed object" : name;
        }
    }

    public static final class Vertex {
        public double x;
        public double y;
        public double z;

        public Vertex() { }

        public Vertex(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class Triangle {
        public int[] vertexIndices;
        public int[] u;
        public int[] v;
        public int textureIndex;

        public Triangle() { }

        private Triangle(int[] vertexIndices, int[] u, int[] v) {
            this.vertexIndices = vertexIndices;
            this.u = u;
            this.v = v;
        }

        static Triangle withUv(int a, int b, int c, int u0, int v0,
                               int u1, int v1, int u2, int v2) {
            return new Triangle(new int[] {a, b, c},
                    new int[] {u0, u1, u2}, new int[] {v0, v1, v2});
        }
    }
}

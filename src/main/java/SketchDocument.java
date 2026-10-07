import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Mutable model for a small, grid-aligned 3D layout sketch. */
public final class SketchDocument {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public int grid = 32;
    public int defaultDepth = 128;
    public long nextId = 1;
    public List<SketchShape> shapes = new ArrayList<>();

    public SketchShape addBox(SketchView view, double u1, double v1, double u2, double v2) {
        long id = nextId++;
        double minU = Math.min(u1, u2);
        double maxU = Math.max(u1, u2);
        double minV = Math.min(v1, v2);
        double maxV = Math.max(v1, v2);
        double halfDepth = Math.max(grid, defaultDepth) / 2.0;
        SketchShape shape;
        if (view == SketchView.TOP) {
            shape = SketchShape.box(id, "Platform " + id,
                    minU, minV, -halfDepth, maxU, maxV, halfDepth);
        } else if (view == SketchView.FRONT) {
            shape = SketchShape.box(id, "Wall " + id,
                    minU, -halfDepth, minV, maxU, halfDepth, maxV);
        } else {
            shape = SketchShape.box(id, "Wall " + id,
                    -halfDepth, minU, minV, halfDepth, maxU, maxV);
        }
        shapes.add(shape);
        return shape;
    }

    public SketchShape addLine(SketchView view, double u1, double v1, double u2, double v2) {
        long id = nextId++;
        SketchShape shape;
        if (view == SketchView.TOP) {
            shape = SketchShape.line(id, "Guide " + id, u1, v1, 0, u2, v2, 0);
        } else if (view == SketchView.FRONT) {
            shape = SketchShape.line(id, "Guide " + id, u1, 0, v1, u2, 0, v2);
        } else {
            shape = SketchShape.line(id, "Guide " + id, 0, u1, v1, 0, u2, v2);
        }
        shapes.add(shape);
        return shape;
    }

    public SketchDocument copy() {
        SketchDocument copy = new SketchDocument();
        copy.grid = grid;
        copy.defaultDepth = defaultDepth;
        copy.nextId = nextId;
        copy.shapes = new ArrayList<>();
        for (SketchShape shape : shapes) copy.shapes.add(shape.copy());
        return copy;
    }

    public void copyFrom(SketchDocument source) {
        grid = source.grid;
        defaultDepth = source.defaultDepth;
        nextId = source.nextId;
        shapes = new ArrayList<>();
        for (SketchShape shape : source.shapes) shapes.add(shape.copy());
    }

    public void removeIds(java.util.Set<Long> ids) {
        shapes.removeIf(shape -> ids.contains(shape.id));
    }

    public void save(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), this);
    }

    public static SketchDocument load(Path path) throws IOException {
        SketchDocument document = JSON.readValue(path.toFile(), SketchDocument.class);
        if (document.shapes == null) document.shapes = new ArrayList<>();
        long largestId = 0;
        for (SketchShape shape : document.shapes) {
            if (shape.kind == null) shape.kind = SketchShape.Kind.BOX;
            if (shape.kind == SketchShape.Kind.BOX) shape.refreshBounds();
            largestId = Math.max(largestId, shape.id);
        }
        document.nextId = Math.max(document.nextId, largestId + 1);
        document.grid = Math.max(1, document.grid);
        document.defaultDepth = Math.max(document.grid, document.defaultDepth);
        return document;
    }
}

enum SketchView {
    TOP("Top", "X / Y"),
    FRONT("Front", "X / Z"),
    SIDE("Side", "Y / Z");

    private final String label;
    private final String axes;

    SketchView(String label, String axes) {
        this.label = label;
        this.axes = axes;
    }

    String label() { return label; }
    String axes() { return axes; }

    @Override public String toString() { return label + " (" + axes + ")"; }
}

enum SketchTool {
    SELECT("Select / move"),
    LINE("Line"),
    RECTANGLE("Rectangle");

    private final String label;

    SketchTool(String label) { this.label = label; }
    @Override public String toString() { return label; }
}

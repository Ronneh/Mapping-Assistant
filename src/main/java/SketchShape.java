/** A single editable object in a Sketch document. */
public final class SketchShape {
    public enum Kind { BOX, LINE }

    public long id;
    public Kind kind;
    public String name;
    public double minX;
    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;
    public double x1;
    public double y1;
    public double z1;
    public double x2;
    public double y2;
    public double z2;
    /** The eight solid corners: bottom ring 0..3, top ring 4..7. */
    public double[][] vertices;

    public SketchShape() {
        kind = Kind.BOX;
        name = "Sketch object";
    }

    static SketchShape box(long id, String name, double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {
        SketchShape shape = new SketchShape();
        shape.id = id;
        shape.kind = Kind.BOX;
        shape.name = name;
        shape.minX = minX;
        shape.minY = minY;
        shape.minZ = minZ;
        shape.maxX = maxX;
        shape.maxY = maxY;
        shape.maxZ = maxZ;
        shape.vertices = vertices(minX, minY, minZ, maxX, maxY, maxZ);
        return shape;
    }

    static SketchShape line(long id, String name, double x1, double y1, double z1,
                            double x2, double y2, double z2) {
        SketchShape shape = new SketchShape();
        shape.id = id;
        shape.kind = Kind.LINE;
        shape.name = name;
        shape.x1 = x1;
        shape.y1 = y1;
        shape.z1 = z1;
        shape.x2 = x2;
        shape.y2 = y2;
        shape.z2 = z2;
        return shape;
    }

    SketchShape copy() {
        SketchShape copy = new SketchShape();
        copy.id = id;
        copy.kind = kind;
        copy.name = name;
        copy.minX = minX;
        copy.minY = minY;
        copy.minZ = minZ;
        copy.maxX = maxX;
        copy.maxY = maxY;
        copy.maxZ = maxZ;
        copy.x1 = x1;
        copy.y1 = y1;
        copy.z1 = z1;
        copy.x2 = x2;
        copy.y2 = y2;
        copy.z2 = z2;
        if (vertices != null) {
            copy.vertices = new double[vertices.length][];
            for (int index = 0; index < vertices.length; index++)
                copy.vertices[index] = vertices[index].clone();
        }
        return copy;
    }

    double[][] vertexData() {
        if (kind == Kind.BOX && (vertices == null || vertices.length != 8))
            vertices = vertices(minX, minY, minZ, maxX, maxY, maxZ);
        return vertices;
    }

    void refreshBounds() {
        if (kind != Kind.BOX) return;
        double[][] data = vertexData();
        minX = maxX = data[0][0];
        minY = maxY = data[0][1];
        minZ = maxZ = data[0][2];
        for (double[] vertex : data) {
            minX = Math.min(minX, vertex[0]); maxX = Math.max(maxX, vertex[0]);
            minY = Math.min(minY, vertex[1]); maxY = Math.max(maxY, vertex[1]);
            minZ = Math.min(minZ, vertex[2]); maxZ = Math.max(maxZ, vertex[2]);
        }
    }

    private static double[][] vertices(double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        return new double[][] {
                {minX, minY, minZ}, {maxX, minY, minZ},
                {maxX, maxY, minZ}, {minX, maxY, minZ},
                {minX, minY, maxZ}, {maxX, minY, maxZ},
                {maxX, maxY, maxZ}, {minX, maxY, maxZ}
        };
    }

    double centerX() { return kind == Kind.BOX ? (minX + maxX) / 2.0 : (x1 + x2) / 2.0; }
    double centerY() { return kind == Kind.BOX ? (minY + maxY) / 2.0 : (y1 + y2) / 2.0; }
    double centerZ() { return kind == Kind.BOX ? (minZ + maxZ) / 2.0 : (z1 + z2) / 2.0; }

    double minX() { return kind == Kind.BOX ? minX : Math.min(x1, x2); }
    double minY() { return kind == Kind.BOX ? minY : Math.min(y1, y2); }
    double minZ() { return kind == Kind.BOX ? minZ : Math.min(z1, z2); }
    double maxX() { return kind == Kind.BOX ? maxX : Math.max(x1, x2); }
    double maxY() { return kind == Kind.BOX ? maxY : Math.max(y1, y2); }
    double maxZ() { return kind == Kind.BOX ? maxZ : Math.max(z1, z2); }
}

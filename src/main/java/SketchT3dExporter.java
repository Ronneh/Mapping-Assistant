import java.util.Locale;

/** Exports Sketch solids as UnrealEd-compatible additive T3D brushes. */
public final class SketchT3dExporter {
    private SketchT3dExporter() { }

    public static String export(SketchDocument document) {
        StringBuilder code = new StringBuilder("Begin Map\n");
        int exported = 0;
        for (SketchShape shape : document.shapes) {
            if (shape.kind != SketchShape.Kind.BOX) continue;
            exported++;
            String name = "SketchBox" + shape.id;
            double[][] vertices = shape.vertexData();
            code.append("Begin Actor Class=Brush Name=").append(name).append('\n')
                    .append("    CsgOper=CSG_Add\n")
                    .append("    MainScale=(SheerAxis=SHEER_ZX)\n")
                    .append("    PostScale=(SheerAxis=SHEER_ZX)\n")
                    .append("    Level=LevelInfo'MyLevel.LevelInfo0'\n")
                    .append("    Tag=\"Brush\"\n")
                    .append("    Region=(Zone=LevelInfo'MyLevel.LevelInfo0',iLeaf=-1)\n")
                    .append("    bSelected=True\n")
                    .append("    Begin Brush Name=").append(name).append("Model\n")
                    .append("        Begin PolyList\n");
            int[][] faces = {
                    {0, 1, 2, 3}, {4, 7, 6, 5}, {0, 4, 5, 1},
                    {3, 2, 6, 7}, {0, 3, 7, 4}, {1, 5, 6, 2}
            };
            for (int index = 0; index < faces.length; index++) {
                double[][] polygon = new double[faces[index].length][];
                for (int vertex = 0; vertex < faces[index].length; vertex++)
                    polygon[vertex] = vertices[faces[index][vertex]];
                appendFace(code, polygon, index == 1);
            }
            code.append("        End PolyList\n")
                    .append("    End Brush\n")
                    .append("    Brush=Model'MyLevel.").append(name).append("Model'\n")
                    .append("    Name=\"").append(name).append("\"\n")
                    .append("End Actor\n");
        }
        if (exported == 0) code.append("// No solid Sketch boxes to export.\n");
        return code.append("Begin Surface\nEnd Surface\nEnd Map\n").toString();
    }

    private static void appendFace(StringBuilder code, double[][] vertices, boolean cap) {
        Basis basis = basis(vertices);
        if (basis == null) return;
        code.append("            Begin Polygon Item=").append(cap ? "Cap" : "Wall").append('\n')
                .append("                Origin   ").append(vector(vertices[0])).append('\n')
                .append("                Normal   ").append(vector(basis.normalX, basis.normalY, basis.normalZ)).append('\n')
                .append("                TextureU ").append(vector(basis.uX, basis.uY, basis.uZ)).append('\n')
                .append("                TextureV ").append(vector(basis.vX, basis.vY, basis.vZ)).append('\n');
        for (double[] vertex : vertices)
            code.append("                Vertex   ").append(vector(vertex)).append('\n');
        code.append("            End Polygon\n");
    }

    private static Basis basis(double[][] vertices) {
        double[] a = vertices[0], b = vertices[1], c = vertices[2];
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double edgeLength = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (normalLength < 0.000001 || edgeLength < 0.000001) return null;
        nx /= normalLength; ny /= normalLength; nz /= normalLength;
        ux = ux / edgeLength * 1024.0;
        uy = uy / edgeLength * 1024.0;
        uz = uz / edgeLength * 1024.0;
        double tx = ny * uz - nz * uy;
        double ty = nz * ux - nx * uz;
        double tz = nx * uy - ny * ux;
        return new Basis(nx, ny, nz, ux, uy, uz, tx, ty, tz);
    }

    private static String vector(double[] values) {
        return vector(values[0], values[1], values[2]);
    }

    private static String vector(double x, double y, double z) {
        return String.format(Locale.ROOT, "%+013.6f,%+013.6f,%+013.6f", x, y, z);
    }

    private record Basis(double normalX, double normalY, double normalZ,
                         double uX, double uY, double uZ,
                         double vX, double vY, double vZ) { }
}

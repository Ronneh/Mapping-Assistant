import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.swing.JPanel;

/** Small software-rendered perspective preview for Sketch layouts. */
public final class SketchViewport3D extends JPanel {
    private static final Color FLOOR = new Color(41, 50, 62);
    private static final Color FLOOR_MAJOR = new Color(59, 72, 88);
    private static final Color BOX = new Color(74, 145, 184, 185);
    private static final Color BOX_SELECTED = new Color(101, 201, 232, 225);
    private static final Color LINE = new Color(246, 190, 82);
    private static final double NEAR = 8;

    private final SketchDocument document;
    private final Set<Long> selection;
    private final Runnable changed;
    private double cameraX = 700;
    private double cameraY = -700;
    private double cameraZ = 500;
    private double yaw = Math.toRadians(135);
    private double pitch = Math.toRadians(-18);
    private double focal = 650;
    private Point2D lastMouse;
    private boolean leftDown;
    private boolean rightDown;

    public SketchViewport3D(SketchDocument document, Set<Long> selection, Runnable changed) {
        this.document = document;
        this.selection = selection;
        this.changed = changed;
        setBackground(Color.BLACK);
        setOpaque(true);
        setPreferredSize(new Dimension(560, 620));
        setBorder(AssistantTheme.titled("3D Preview"));
        setFocusable(true);
        installMouseHandling();
    }

    public void fitAll() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (SketchShape shape : document.shapes) {
            minX = Math.min(minX, shape.minX()); maxX = Math.max(maxX, shape.maxX());
            minY = Math.min(minY, shape.minY()); maxY = Math.max(maxY, shape.maxY());
            minZ = Math.min(minZ, shape.minZ()); maxZ = Math.max(maxZ, shape.maxZ());
        }
        if (document.shapes.isEmpty()) {
            minX = minY = minZ = -256;
            maxX = maxY = maxZ = 256;
        }
        double targetX = (minX + maxX) / 2.0;
        double targetY = (minY + maxY) / 2.0;
        double targetZ = (minZ + maxZ) / 2.0;
        double radius = Math.max(256, Math.sqrt(Math.pow(maxX - minX, 2)
                + Math.pow(maxY - minY, 2) + Math.pow(maxZ - minZ, 2)) / 2.0);
        double distance = radius * 2.8;
        double cosPitch = Math.cos(pitch);
        cameraX = targetX - Math.cos(yaw) * cosPitch * distance;
        cameraY = targetY - Math.sin(yaw) * cosPitch * distance;
        cameraZ = targetZ - Math.sin(pitch) * distance;
        focal = Math.max(420, Math.min(950, Math.min(getWidth(), getHeight()) * 0.9));
        repaint();
    }

    private void installMouseHandling() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                lastMouse = event.getPoint();
                if (event.getButton() == MouseEvent.BUTTON1) leftDown = true;
                if (event.getButton() == MouseEvent.BUTTON3) rightDown = true;
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (lastMouse == null) lastMouse = event.getPoint();
                double dx = event.getX() - lastMouse.getX();
                double dy = event.getY() - lastMouse.getY();
                if (leftDown && rightDown) {
                    moveStrafe(dx * Math.max(1, document.grid) * 0.18);
                    moveVertical(-dy * Math.max(1, document.grid) * 0.18);
                } else if (rightDown) {
                    yaw += dx * 0.008;
                    pitch = Math.max(Math.toRadians(-80), Math.min(Math.toRadians(80), pitch - dy * 0.006));
                } else if (leftDown) {
                    moveForward(-dy * Math.max(1, document.grid) * 0.18);
                }
                lastMouse = event.getPoint();
                repaint();
            }

            @Override public void mouseReleased(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1) leftDown = false;
                if (event.getButton() == MouseEvent.BUTTON3) rightDown = false;
                if (!leftDown && !rightDown) lastMouse = null;
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(event -> {
            moveForward(-event.getPreciseWheelRotation() * document.grid * 3.0);
            repaint();
            event.consume();
        });
    }

    private void moveForward(double amount) {
        cameraX += Math.cos(yaw) * amount;
        cameraY += Math.sin(yaw) * amount;
    }

    private void moveStrafe(double amount) {
        cameraX += -Math.sin(yaw) * amount;
        cameraY += Math.cos(yaw) * amount;
    }

    private void moveVertical(double amount) {
        cameraZ += amount;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawFloor(g);
        List<Face> faces = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (SketchShape shape : document.shapes) {
            if (shape.kind == SketchShape.Kind.BOX) addBoxFaces(faces, shape);
            else lines.add(new Line(shape,
                    new double[] {shape.x1, shape.y1, shape.z1},
                    new double[] {shape.x2, shape.y2, shape.z2}));
        }
        faces.sort(Comparator.comparingDouble(Face::depth).reversed());
        for (Face face : faces) drawFace(g, face);
        for (Line line : lines) drawLine(g, line);
        g.setColor(AssistantTheme.MUTED);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString("Right-drag: look  |  left-drag: horizontal fly  |  both: free move  |  wheel: move",
                12, getHeight() - 12);
        g.dispose();
    }

    private void drawFloor(Graphics2D g) {
        double span = 1024;
        for (SketchShape shape : document.shapes) {
            span = Math.max(span, Math.max(Math.abs(shape.minX()), Math.abs(shape.maxX())));
            span = Math.max(span, Math.max(Math.abs(shape.minY()), Math.abs(shape.maxY())));
        }
        span = Math.min(8192, span * 1.7);
        int step = Math.max(document.grid, 16);
        for (double value = -span; value <= span; value += step) {
            g.setColor(Math.abs(Math.round(value / step)) % 4 == 0 ? FLOOR_MAJOR : FLOOR);
            drawWorldLine(g, value, -span, 0, value, span, 0);
            drawWorldLine(g, -span, value, 0, span, value, 0);
        }
        g.setColor(new Color(104, 128, 146));
        drawWorldLine(g, -span, 0, 0, span, 0, 0);
        drawWorldLine(g, 0, -span, 0, 0, span, 0);
    }

    private void addBoxFaces(List<Face> faces, SketchShape shape) {
        double[][] vertices = shape.vertexData();
        int[][] facesByVertex = {
                {0, 1, 2, 3}, {4, 7, 6, 5}, {0, 4, 5, 1},
                {3, 2, 6, 7}, {0, 3, 7, 4}, {1, 5, 6, 2}
        };
        for (int[] face : facesByVertex) {
            double[][] polygon = new double[face.length][];
            for (int index = 0; index < face.length; index++) polygon[index] = vertices[face[index]];
            addFace(faces, shape, polygon);
        }
    }

    private void addFace(List<Face> faces, SketchShape shape, double[][] vertices) {
        List<ScreenPoint> projected = new ArrayList<>();
        double depth = 0;
        for (double[] vertex : vertices) {
            ScreenPoint point = project(vertex[0], vertex[1], vertex[2]);
            if (point == null) return;
            projected.add(point);
            depth += point.depth;
        }
        faces.add(new Face(shape, projected, depth / vertices.length));
    }

    private void drawFace(Graphics2D g, Face face) {
        Polygon polygon = new Polygon();
        for (ScreenPoint point : face.points) polygon.addPoint((int) Math.round(point.x), (int) Math.round(point.y));
        boolean selected = selection.contains(face.shape.id);
        g.setColor(selected ? BOX_SELECTED : BOX);
        g.fillPolygon(polygon);
        g.setColor(selected ? new Color(186, 239, 255) : new Color(91, 176, 216));
        g.setStroke(new BasicStroke(selected ? 1.7f : 0.9f));
        g.drawPolygon(polygon);
    }

    private void drawLine(Graphics2D g, Line line) {
        g.setColor(selection.contains(line.shape.id) ? new Color(255, 231, 142) : LINE);
        g.setStroke(new BasicStroke(selection.contains(line.shape.id) ? 3f : 1.8f,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawWorldLine(g, line.first[0], line.first[1], line.first[2],
                line.second[0], line.second[1], line.second[2]);
    }

    private void drawWorldLine(Graphics2D g, double x1, double y1, double z1,
                               double x2, double y2, double z2) {
        CameraPoint first = cameraPoint(x1, y1, z1);
        CameraPoint second = cameraPoint(x2, y2, z2);
        if (first.depth < NEAR && second.depth < NEAR) return;
        if (first.depth < NEAR) first = clipToNear(first, second);
        if (second.depth < NEAR) second = clipToNear(second, first);
        ScreenPoint projectedFirst = screenPoint(first);
        ScreenPoint projectedSecond = screenPoint(second);
        g.draw(new Line2D.Double(projectedFirst.x, projectedFirst.y,
                projectedSecond.x, projectedSecond.y));
    }

    private CameraPoint clipToNear(CameraPoint behind, CameraPoint inFront) {
        double fraction = (NEAR - behind.depth) / (inFront.depth - behind.depth);
        return new CameraPoint(
                behind.right + (inFront.right - behind.right) * fraction,
                behind.up + (inFront.up - behind.up) * fraction,
                NEAR);
    }

    private ScreenPoint screenPoint(CameraPoint camera) {
        return new ScreenPoint(getWidth() / 2.0 + camera.right * focal / camera.depth,
                getHeight() / 2.0 - camera.up * focal / camera.depth, camera.depth);
    }

    private void drawProjectedLine(Graphics2D g, ScreenPoint first, ScreenPoint second) {
        if (first == null || second == null) return;
        g.draw(new Line2D.Double(first.x, first.y, second.x, second.y));
    }

    private ScreenPoint project(double x, double y, double z) {
        CameraPoint camera = cameraPoint(x, y, z);
        return camera.depth <= NEAR ? null : screenPoint(camera);
    }

    private CameraPoint cameraPoint(double x, double y, double z) {
        double dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ;
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
        double horizontal = cosYaw * dx + sinYaw * dy;
        double right = -sinYaw * dx + cosYaw * dy;
        double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
        double depth = cosPitch * horizontal + sinPitch * dz;
        double up = -sinPitch * horizontal + cosPitch * dz;
        return new CameraPoint(right, up, depth);
    }

    private record CameraPoint(double right, double up, double depth) { }
    private record ScreenPoint(double x, double y, double depth) { }
    private record Face(SketchShape shape, List<ScreenPoint> points, double depth) { }
    private record Line(SketchShape shape, double[] first, double[] second) { }
}

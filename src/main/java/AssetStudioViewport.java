import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JPanel;

/** Lightweight software viewport with object and triangle-face selection. */
public final class AssetStudioViewport extends JPanel {
    public enum InteractionMode { MOVE, ROTATE, SCALE }
    public record FaceSelection(AssetStudioProject.MeshObject object,
                                AssetStudioProject.Triangle triangle) { }

    private AssetStudioProject project;
    private double yaw = 0.65;
    private double pitch = -0.45;
    private double zoom = 1.0;
    private int lastX;
    private int lastY;
    private AssetStudioProject.MeshObject selectedObject;
    private AssetStudioProject.Triangle selectedTriangle;
    private InteractionMode interactionMode = InteractionMode.MOVE;
    private Consumer<AssetStudioProject.MeshObject> selectionListener = object -> { };
    private Consumer<FaceSelection> faceSelectionListener = selection -> { };
    private Runnable changeListener = () -> { };

    public AssetStudioViewport(AssetStudioProject project) {
        this.project = project;
        setPreferredSize(new Dimension(640, 520));
        setBackground(AssistantTheme.CODE_BACKGROUND);
        setBorder(AssistantTheme.titled("Asset Studio Preview"));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                lastX = event.getX();
                lastY = event.getY();
                if (event.getButton() == MouseEvent.BUTTON1) {
                    FaceSelection hit = hitTestFace(event.getX(), event.getY());
                    if (hit != null) selectFace(hit);
                    else {
                        AssetStudioProject.MeshObject object = hitTestObject(event.getX(), event.getY());
                        if (object != null) selectObject(object);
                    }
                }
            }

            @Override public void mouseDragged(MouseEvent event) {
                int dx = event.getX() - lastX;
                int dy = event.getY() - lastY;
                if ((event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0
                        && selectedObject != null) transformSelected(dx, dy);
                else if ((event.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0
                        || (event.getModifiersEx() & MouseEvent.BUTTON2_DOWN_MASK) != 0) orbit(dx, dy);
                lastX = event.getX();
                lastY = event.getY();
            }

            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                zoom *= Math.pow(1.12, -event.getPreciseWheelRotation());
                zoom = Math.max(0.25, Math.min(6.0, zoom));
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void setProject(AssetStudioProject project) {
        this.project = project;
        selectedObject = null;
        selectedTriangle = null;
        repaint();
    }

    public void setSelectionListener(Consumer<AssetStudioProject.MeshObject> listener) {
        selectionListener = listener == null ? object -> { } : listener;
    }

    public void setFaceSelectionListener(Consumer<FaceSelection> listener) {
        faceSelectionListener = listener == null ? selection -> { } : listener;
    }

    public void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    public void setSelectedObject(AssetStudioProject.MeshObject object) {
        selectedObject = object;
        selectedTriangle = null;
        repaint();
    }

    public void setSelectedFace(AssetStudioProject.MeshObject object,
                                AssetStudioProject.Triangle triangle) {
        selectedObject = object;
        selectedTriangle = triangle;
        repaint();
    }

    public void setInteractionMode(InteractionMode mode) {
        interactionMode = mode == null ? InteractionMode.MOVE : mode;
    }

    private void selectObject(AssetStudioProject.MeshObject object) {
        selectedObject = object;
        selectedTriangle = null;
        selectionListener.accept(object);
        repaint();
    }

    private void selectFace(FaceSelection selection) {
        selectedObject = selection.object();
        selectedTriangle = selection.triangle();
        selectionListener.accept(selectedObject);
        faceSelectionListener.accept(selection);
        repaint();
    }

    private void transformSelected(int dx, int dy) {
        if (interactionMode == InteractionMode.MOVE) {
            double worldPerPixel = 0.18 / Math.max(0.25, zoom);
            selectedObject.x += dx * worldPerPixel;
            selectedObject.y -= dy * worldPerPixel;
        } else if (interactionMode == InteractionMode.ROTATE) {
            selectedObject.rotationY += dx * 0.8;
            selectedObject.rotationX += dy * 0.8;
        } else {
            double factor = Math.exp(-dy * 0.012);
            selectedObject.scaleX = clampScale(selectedObject.scaleX * factor);
            selectedObject.scaleY = clampScale(selectedObject.scaleY * factor);
            selectedObject.scaleZ = clampScale(selectedObject.scaleZ * factor);
        }
        changeListener.run();
        repaint();
    }

    private static double clampScale(double value) {
        return Math.max(0.05, Math.min(20.0, value));
    }

    private void orbit(int dx, int dy) {
        yaw += dx * 0.012;
        pitch += dy * 0.012;
        pitch = Math.max(-1.45, Math.min(1.45, pitch));
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawGrid(g);
        List<Face> faces = renderFaces();
        Map<Integer, BufferedImage> textureCache = new HashMap<>();
        faces.sort(Comparator.comparingDouble(Face::depth).reversed());
        for (Face face : faces) {
            Polygon polygon = polygon(face.points());
            boolean selectedFace = face.object() == selectedObject && face.triangle() == selectedTriangle;
            boolean selectedObjectFace = face.object() == selectedObject;
            BufferedImage texture = textureCache.computeIfAbsent(face.triangle().textureIndex,
                    this::loadTextureSafely);
            if (texture == null || !drawTexturedFace(g, polygon, face, texture)) {
                g.setColor(selectedFace ? new Color(238, 177, 47, 245)
                        : selectedObjectFace ? new Color(225, 154, 55, 235)
                        : new Color(48, 116, 161, 220));
                g.fillPolygon(polygon);
            }
            g.setColor(selectedFace ? new Color(255, 246, 150)
                    : selectedObjectFace ? new Color(255, 220, 100)
                    : new Color(135, 196, 224));
            g.setStroke(new BasicStroke(selectedFace ? 2.8f : selectedObjectFace ? 2.2f : 1.2f));
            g.drawPolygon(polygon);
        }
        g.setColor(AssistantTheme.MUTED);
        g.drawString("Click a face to texture it  |  Left-drag transforms  |  Right-drag orbits  |  Wheel zooms",
                14, getHeight() - 14);
        g.dispose();
    }

    private BufferedImage loadTextureSafely(int textureIndex) {
        try {
            return project == null ? null : project.loadTexture(textureIndex);
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean drawTexturedFace(Graphics2D g, Polygon polygon, Face face, BufferedImage image) {
        AssetStudioProject.Triangle triangle = face.triangle();
        if (triangle.u == null || triangle.v == null || triangle.u.length != 3 || triangle.v.length != 3)
            return false;
        double[] sourceX = new double[3];
        double[] sourceY = new double[3];
        double[] destinationX = new double[3];
        double[] destinationY = new double[3];
        for (int corner = 0; corner < 3; corner++) {
            sourceX[corner] = triangle.u[corner] * Math.max(1, image.getWidth() - 1) / 255.0;
            sourceY[corner] = triangle.v[corner] * Math.max(1, image.getHeight() - 1) / 255.0;
            destinationX[corner] = face.points()[corner].x();
            destinationY[corner] = face.points()[corner].y();
        }
        double determinant = (sourceX[1] - sourceX[0]) * (sourceY[2] - sourceY[0])
                - (sourceX[2] - sourceX[0]) * (sourceY[1] - sourceY[0]);
        if (Math.abs(determinant) < 0.000001) return false;
        double a = ((destinationX[1] - destinationX[0]) * (sourceY[2] - sourceY[0])
                - (destinationX[2] - destinationX[0]) * (sourceY[1] - sourceY[0])) / determinant;
        double c = ((destinationX[2] - destinationX[0]) * (sourceX[1] - sourceX[0])
                - (destinationX[1] - destinationX[0]) * (sourceX[2] - sourceX[0])) / determinant;
        double b = ((destinationY[1] - destinationY[0]) * (sourceY[2] - sourceY[0])
                - (destinationY[2] - destinationY[0]) * (sourceY[1] - sourceY[0])) / determinant;
        double d = ((destinationY[2] - destinationY[0]) * (sourceX[1] - sourceX[0])
                - (destinationY[1] - destinationY[0]) * (sourceX[2] - sourceX[0])) / determinant;
        double e = destinationX[0] - a * sourceX[0] - c * sourceY[0];
        double f = destinationY[0] - b * sourceX[0] - d * sourceY[0];
        java.awt.Shape oldClip = g.getClip();
        g.clip(polygon);
        g.drawImage(image, new AffineTransform(a, b, c, d, e, f), null);
        g.setClip(oldClip);
        return true;
    }

    private List<Face> renderFaces() {
        List<Face> faces = new ArrayList<>();
        if (project == null) return faces;
        project.ensureSceneObjects();
        for (AssetStudioProject.MeshObject object : project.objects) {
            if (object == null || object.vertices == null || object.triangles == null) continue;
            for (AssetStudioProject.Triangle triangle : object.triangles) {
                if (triangle == null || triangle.vertexIndices == null || triangle.vertexIndices.length != 3) continue;
                Projected[] points = new Projected[3];
                boolean valid = true;
                for (int corner = 0; corner < 3; corner++) {
                    int index = triangle.vertexIndices[corner];
                    if (index < 0 || index >= object.vertices.size()) {
                        valid = false;
                        break;
                    }
                    points[corner] = project(AssetStudioProject.transform(object, object.vertices.get(index)));
                }
                if (valid) faces.add(new Face(points,
                        (points[0].depth + points[1].depth + points[2].depth) / 3,
                        object, triangle));
            }
        }
        return faces;
    }

    private FaceSelection hitTestFace(int mouseX, int mouseY) {
        List<Face> faces = renderFaces();
        faces.sort(Comparator.comparingDouble(Face::depth));
        for (Face face : faces) if (polygon(face.points()).contains(mouseX, mouseY))
            return new FaceSelection(face.object(), face.triangle());
        return null;
    }

    private AssetStudioProject.MeshObject hitTestObject(int mouseX, int mouseY) {
        AssetStudioProject.MeshObject result = null;
        double bestDepth = Double.POSITIVE_INFINITY;
        for (Face face : renderFaces()) {
            Polygon polygon = polygon(face.points());
            if (polygon.contains(mouseX, mouseY) && face.depth() < bestDepth) {
                result = face.object();
                bestDepth = face.depth();
            }
        }
        return result;
    }

    private static Polygon polygon(Projected[] points) {
        Polygon polygon = new Polygon();
        for (Projected point : points) polygon.addPoint((int) point.x(), (int) point.y());
        return polygon;
    }

    private void drawGrid(Graphics2D g) {
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        g.setColor(new Color(37, 46, 59));
        for (int i = -8; i <= 8; i++) {
            int offset = i * 26;
            g.drawLine(centerX - 220, centerY + offset, centerX + 220, centerY + offset);
            g.drawLine(centerX + offset, centerY - 220, centerX + offset, centerY + 220);
        }
        g.setColor(new Color(95, 145, 185));
        g.drawLine(centerX - 220, centerY, centerX + 220, centerY);
        g.setColor(new Color(132, 184, 112));
        g.drawLine(centerX, centerY - 220, centerX, centerY + 220);
    }

    private Projected project(AssetStudioProject.Vertex vertex) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double x = vertex.x * cy - vertex.z * sy;
        double z = vertex.x * sy + vertex.z * cy;
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double y = vertex.y * cp - z * sp;
        z = vertex.y * sp + z * cp;
        double depth = 150 + z;
        double scale = Math.min(getWidth(), getHeight()) * 0.58 * zoom;
        return new Projected(getWidth() / 2.0 + x * scale / depth,
                getHeight() / 2.0 - y * scale / depth, depth);
    }

    private record Projected(double x, double y, double depth) { }
    private record Face(Projected[] points, double depth,
                        AssetStudioProject.MeshObject object,
                        AssetStudioProject.Triangle triangle) { }
}

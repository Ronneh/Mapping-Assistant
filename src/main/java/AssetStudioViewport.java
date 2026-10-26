import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/** Split orthographic/perspective viewport with grid snapping and face selection. */
public final class AssetStudioViewport extends JPanel {
    public enum InteractionMode { MOVE, ROTATE, SCALE, VERTEX }
    public enum View { TOP, FRONT, SIDE }
    public record FaceSelection(AssetStudioProject.MeshObject object,
                                AssetStudioProject.Triangle triangle) { }

    private AssetStudioProject project;
    private final PerspectiveCanvas perspective = new PerspectiveCanvas();
    private final OrthographicCanvas orthographic = new OrthographicCanvas();
    private double gridSize = 8.0;
    private AssetStudioProject.MeshObject selectedObject;
    private final Set<FaceSelection> selectedFaces = new HashSet<>();
    private InteractionMode interactionMode = InteractionMode.MOVE;
    private Consumer<AssetStudioProject.MeshObject> selectionListener = object -> { };
    private Consumer<List<FaceSelection>> faceSelectionListener = selections -> { };
    private Runnable changeListener = () -> { };

    public AssetStudioViewport(AssetStudioProject project) {
        super(new BorderLayout(0, 4));
        this.project = project;
        setBackground(AssistantTheme.CODE_BACKGROUND);
        setBorder(AssistantTheme.titled("Asset Studio Preview"));
        add(createViewToolbar(), BorderLayout.NORTH);
        JPanel views = new JPanel(new GridLayout(2, 1, 0, 4));
        views.setOpaque(false);
        views.add(orthographic);
        views.add(perspective);
        add(views, BorderLayout.CENTER);
    }

    private JPanel createViewToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.setOpaque(false);
        toolbar.add(new JLabel("Orthographic:"));
        ButtonGroup group = new ButtonGroup();
        for (View view : View.values()) {
            JRadioButton button = new JRadioButton(view.name().substring(0, 1));
            button.setToolTipText(view == View.TOP ? "Top view (X/Z)" : view == View.FRONT ? "Front view (X/Y)" : "Side view (Z/Y)");
            button.setOpaque(false);
            button.setFocusable(false);
            button.addActionListener(event -> orthographic.setView(view));
            group.add(button);
            toolbar.add(button);
            if (view == View.TOP) button.setSelected(true);
        }
        toolbar.add(new JLabel("  Grid snap: 8 units | Ctrl-click: multi-select faces | Vertex mode: move corners"));
        return toolbar;
    }

    public void setProject(AssetStudioProject project) {
        this.project = project;
        selectedObject = null;
        selectedFaces.clear();
        repaint();
    }

    public void setSelectionListener(Consumer<AssetStudioProject.MeshObject> listener) {
        selectionListener = listener == null ? object -> { } : listener;
    }

    public void setFaceSelectionListener(Consumer<List<FaceSelection>> listener) {
        faceSelectionListener = listener == null ? selections -> { } : listener;
    }

    public void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    public void setSelectedObject(AssetStudioProject.MeshObject object) {
        selectedObject = object;
        selectedFaces.removeIf(face -> face.object() != object);
        repaint();
    }

    public void setSelectedFace(AssetStudioProject.MeshObject object,
                                AssetStudioProject.Triangle triangle) {
        selectedObject = object;
        selectedFaces.clear();
        if (object != null && triangle != null) selectedFaces.add(new FaceSelection(object, triangle));
        repaint();
    }

    public void setInteractionMode(InteractionMode mode) {
        interactionMode = mode == null ? InteractionMode.MOVE : mode;
        orthographic.setCursorForMode();
        perspective.setCursorForMode();
    }

    private void selectObject(AssetStudioProject.MeshObject object) {
        selectedObject = object;
        selectedFaces.clear();
        selectionListener.accept(object);
        repaint();
    }

    private void selectFace(FaceSelection hit, boolean additive) {
        if (!additive) selectedFaces.clear();
        if (additive && selectedFaces.contains(hit)) selectedFaces.remove(hit);
        else selectedFaces.add(hit);
        selectedObject = hit.object();
        selectionListener.accept(selectedObject);
        faceSelectionListener.accept(List.copyOf(selectedFaces));
        repaint();
    }

    private boolean isSelected(FaceSelection face) { return selectedFaces.contains(face); }

    private void notifyChanged() {
        if (project != null) project.syncLegacyFromObjects();
        changeListener.run();
        repaint();
    }

    private static double clampScale(double value) {
        return Math.max(0.05, Math.min(20.0, value));
    }

    private static double snap(double value, double size) {
        return Math.round(value / size) * size;
    }

    private void transformObject(AssetStudioProject.MeshObject object, int dx, int dy,
                                 double pixelsPerUnit) {
        if (interactionMode == InteractionMode.MOVE) {
            object.x = snap(object.x + dx / Math.max(0.01, pixelsPerUnit), gridSize);
            object.y = snap(object.y - dy / Math.max(0.01, pixelsPerUnit), gridSize);
        } else if (interactionMode == InteractionMode.ROTATE) {
            object.rotationY += dx * 0.8;
            object.rotationX += dy * 0.8;
        } else if (interactionMode == InteractionMode.SCALE) {
            double factor = Math.exp(-dy * 0.012);
            object.scaleX = clampScale(object.scaleX * factor);
            object.scaleY = clampScale(object.scaleY * factor);
            object.scaleZ = clampScale(object.scaleZ * factor);
        }
        notifyChanged();
    }

    private void transformObjectOrthographic(AssetStudioProject.MeshObject object, View view,
                                             int dx, int dy, double pixelsPerUnit) {
        if (interactionMode == InteractionMode.MOVE) {
            double horizontal = dx / Math.max(0.01, pixelsPerUnit);
            double vertical = -dy / Math.max(0.01, pixelsPerUnit);
            if (view == View.TOP) {
                object.x = snap(object.x + horizontal, gridSize);
                object.z = snap(object.z + vertical, gridSize);
            } else if (view == View.FRONT) {
                object.x = snap(object.x + horizontal, gridSize);
                object.y = snap(object.y + vertical, gridSize);
            } else {
                object.z = snap(object.z + horizontal, gridSize);
                object.y = snap(object.y + vertical, gridSize);
            }
            notifyChanged();
        } else {
            transformObject(object, dx, dy, pixelsPerUnit);
        }
    }

    private void moveVertex(AssetStudioProject.MeshObject object, int vertexIndex,
                            View view, int dx, int dy, double pixelsPerUnit) {
        if (vertexIndex < 0 || vertexIndex >= object.vertices.size()) return;
        AssetStudioProject.Vertex vertex = object.vertices.get(vertexIndex);
        double units = dx / Math.max(0.01, pixelsPerUnit);
        double vertical = -dy / Math.max(0.01, pixelsPerUnit);
        if (view == View.TOP) {
            vertex.x = snap(vertex.x + units, gridSize);
            vertex.z = snap(vertex.z + vertical, gridSize);
        } else if (view == View.FRONT) {
            vertex.x = snap(vertex.x + units, gridSize);
            vertex.y = snap(vertex.y + vertical, gridSize);
        } else {
            vertex.z = snap(vertex.z + units, gridSize);
            vertex.y = snap(vertex.y + vertical, gridSize);
        }
        notifyChanged();
    }

    private abstract class ViewCanvas extends JPanel {
        int lastX;
        int lastY;
        AssetStudioProject.MeshObject dragObject;
        int dragVertex = -1;

        ViewCanvas() {
            setBackground(new Color(10, 14, 19));
            setBorder(BorderFactory.createLineBorder(AssistantTheme.BORDER));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    lastX = event.getX();
                    lastY = event.getY();
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) return;
                    Hit hit = hit(event.getX(), event.getY());
                    if (interactionMode == InteractionMode.VERTEX && hit != null && hit.vertexIndex >= 0) {
                        dragObject = hit.object;
                        dragVertex = hit.vertexIndex;
                        selectedObject = dragObject;
                        selectionListener.accept(dragObject);
                    } else if (hit != null && hit.face != null) {
                        selectFace(hit.face, event.isControlDown());
                        dragObject = hit.object;
                    } else if (hit != null) {
                        selectObject(hit.object);
                        dragObject = hit.object;
                    }
                }

                @Override public void mouseDragged(MouseEvent event) {
                    int dx = event.getX() - lastX;
                    int dy = event.getY() - lastY;
                    if (dx == 0 && dy == 0) return;
                    if (dragVertex >= 0 && dragObject != null) {
                        moveVertex(dragObject, dragVertex, view(), dx, dy, pixelsPerUnit());
                    } else if (dragObject != null && interactionMode != InteractionMode.VERTEX) {
                        if (ViewCanvas.this instanceof OrthographicCanvas)
                            transformObjectOrthographic(dragObject, view(), dx, dy, pixelsPerUnit());
                        else transformObject(dragObject, dx, dy, pixelsPerUnit());
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                }

                @Override public void mouseReleased(MouseEvent event) {
                    dragObject = null;
                    dragVertex = -1;
                }

                @Override public void mouseWheelMoved(MouseWheelEvent event) {
                    if (ViewCanvas.this instanceof PerspectiveCanvas) {
                        perspective.zoom *= Math.pow(1.12, -event.getPreciseWheelRotation());
                        perspective.zoom = Math.max(0.25, Math.min(6.0, perspective.zoom));
                        repaint();
                    } else {
                        orthographic.scale *= Math.pow(1.12, -event.getPreciseWheelRotation());
                        orthographic.scale = Math.max(0.5, Math.min(10.0, orthographic.scale));
                        repaint();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            setCursorForMode();
        }

        abstract View view();
        abstract Hit hit(int x, int y);
        abstract double pixelsPerUnit();

        void setCursorForMode() {
            setCursor(interactionMode == InteractionMode.VERTEX
                    ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR)
                    : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.DEFAULT_CURSOR));
        }
    }

    private final class OrthographicCanvas extends ViewCanvas {
        View view = View.TOP;
        double scale = 2.2;

        OrthographicCanvas() { setPreferredSize(new Dimension(640, 260)); }
        void setView(View view) { this.view = view; repaint(); }
        @Override View view() { return view; }
        @Override double pixelsPerUnit() { return scale; }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            g.setColor(new Color(28, 36, 47));
            for (int i = -100; i <= 100; i++) {
                int offset = (int) Math.round(i * gridSize * scale);
                if (cx + offset >= 0 && cx + offset < getWidth()) g.drawLine(cx + offset, 0, cx + offset, getHeight());
                if (cy + offset >= 0 && cy + offset < getHeight()) g.drawLine(0, cy + offset, getWidth(), cy + offset);
            }
            g.setColor(new Color(95, 145, 185)); g.drawLine(0, cy, getWidth(), cy);
            g.setColor(new Color(132, 184, 112)); g.drawLine(cx, 0, cx, getHeight());
            g.setColor(AssistantTheme.MUTED); g.drawString(view.name() + " view | origin (0,0,0) | grid snap " + gridSize, 10, 18);
            if (project != null) {
                project.ensureSceneObjects();
                for (AssetStudioProject.MeshObject object : project.objects) drawObject(g, object, cx, cy);
            }
            g.dispose();
        }

        private void drawObject(Graphics2D g, AssetStudioProject.MeshObject object, int cx, int cy) {
            if (object == null) return;
            g.setColor(object == selectedObject ? new Color(238, 177, 47) : new Color(93, 168, 206));
            for (AssetStudioProject.Triangle triangle : object.triangles) {
                if (triangle == null || triangle.vertexIndices == null || triangle.vertexIndices.length != 3) continue;
                Polygon polygon = new Polygon();
                for (int index : triangle.vertexIndices) {
                    if (index < 0 || index >= object.vertices.size()) continue;
                    double[] point = map(AssetStudioProject.transform(object, object.vertices.get(index)), cx, cy);
                    polygon.addPoint((int) point[0], (int) point[1]);
                }
                if (polygon.npoints == 3) g.drawPolygon(polygon);
            }
            if (object == selectedObject && interactionMode == InteractionMode.VERTEX) {
                for (AssetStudioProject.Vertex vertex : object.vertices) {
                    double[] point = map(AssetStudioProject.transform(object, vertex), cx, cy);
                    g.fillOval((int) point[0] - 4, (int) point[1] - 4, 8, 8);
                }
            }
        }

        private double[] map(AssetStudioProject.Vertex point, int cx, int cy) {
            return switch (view) {
                case TOP -> new double[] {cx + point.x * scale, cy - point.z * scale};
                case FRONT -> new double[] {cx + point.x * scale, cy - point.y * scale};
                case SIDE -> new double[] {cx + point.z * scale, cy - point.y * scale};
            };
        }

        @Override Hit hit(int x, int y) {
            if (project == null) return null;
            project.ensureSceneObjects();
            Hit best = null;
            double bestDistance = 12;
            int cx = getWidth() / 2, cy = getHeight() / 2;
            for (AssetStudioProject.MeshObject object : project.objects) {
                if (object == null) continue;
                if (interactionMode == InteractionMode.VERTEX && object == selectedObject) {
                    for (int index = 0; index < object.vertices.size(); index++) {
                        double[] point = map(AssetStudioProject.transform(object, object.vertices.get(index)), cx, cy);
                        double distance = Math.hypot(point[0] - x, point[1] - y);
                        if (distance < bestDistance) { bestDistance = distance; best = new Hit(object, null, index); }
                    }
                }
                for (AssetStudioProject.Triangle triangle : object.triangles) {
                    if (triangle == null || triangle.vertexIndices == null || triangle.vertexIndices.length != 3) continue;
                    Polygon polygon = new Polygon();
                    for (int index : triangle.vertexIndices) {
                        if (index < 0 || index >= object.vertices.size()) continue;
                        double[] point = map(AssetStudioProject.transform(object, object.vertices.get(index)), cx, cy);
                        polygon.addPoint((int) point[0], (int) point[1]);
                    }
                    if (polygon.npoints == 3 && polygon.contains(x, y)) best = new Hit(object, new FaceSelection(object, triangle), -1);
                }
            }
            return best;
        }
    }

    private final class PerspectiveCanvas extends ViewCanvas {
        double yaw = 0.65;
        double pitch = -0.45;
        double zoom = 1.0;
        int orbitX;
        int orbitY;
        PerspectiveCanvas() { setPreferredSize(new Dimension(640, 260)); }
        @Override View view() { return View.FRONT; }
        @Override double pixelsPerUnit() { return Math.min(getWidth(), getHeight()) * 0.58 * zoom / 150.0; }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawPerspectiveGrid(g);
            List<Face> faces = renderFaces();
            faces.sort(Comparator.comparingDouble(Face::depth).reversed());
            Map<Integer, BufferedImage> cache = new HashMap<>();
            for (Face face : faces) {
                Polygon polygon = polygon(face.points());
                boolean selected = isSelected(new FaceSelection(face.object(), face.triangle()));
                BufferedImage texture = cache.computeIfAbsent(face.triangle().textureIndex, AssetStudioViewport.this::loadTextureSafely);
                if (texture == null || !drawTexturedFace(g, polygon, face, texture)) {
                    g.setColor(selected ? new Color(238, 177, 47, 245) : face.object() == selectedObject ? new Color(225, 154, 55, 235) : new Color(48, 116, 161, 220));
                    g.fillPolygon(polygon);
                }
                g.setColor(selected ? new Color(255, 246, 150) : new Color(135, 196, 224));
                g.setStroke(new BasicStroke(selected ? 2.8f : 1.2f));
                g.drawPolygon(polygon);
            }
            g.setColor(AssistantTheme.MUTED);
            g.drawString("Perspective | left-drag transform | wheel zoom | right-drag orbit", 10, 18);
            g.dispose();
        }

        private void drawPerspectiveGrid(Graphics2D g) {
            int cx = getWidth() / 2, cy = getHeight() / 2;
            g.setColor(new Color(28, 36, 47));
            for (int i = -8; i <= 8; i++) { int offset = i * 26; g.drawLine(cx - 220, cy + offset, cx + 220, cy + offset); g.drawLine(cx + offset, cy - 120, cx + offset, cy + 120); }
            g.setColor(new Color(95, 145, 185)); g.drawLine(cx - 220, cy, cx + 220, cy);
            g.setColor(new Color(132, 184, 112)); g.drawLine(cx, cy - 120, cx, cy + 120);
        }

        @Override Hit hit(int x, int y) {
            FaceSelection face = hitTestFace(x, y);
            if (face != null) return new Hit(face.object(), face, -1);
            AssetStudioProject.MeshObject object = hitTestObject(x, y);
            return object == null ? null : new Hit(object, null, -1);
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
            return new Projected(getWidth() / 2.0 + x * scale / depth, getHeight() / 2.0 - y * scale / depth, depth);
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
                        if (index < 0 || index >= object.vertices.size()) { valid = false; break; }
                        points[corner] = project(AssetStudioProject.transform(object, object.vertices.get(index)));
                    }
                    if (valid) faces.add(new Face(points, (points[0].depth + points[1].depth + points[2].depth) / 3, object, triangle));
                }
            }
            return faces;
        }

        private FaceSelection hitTestFace(int mouseX, int mouseY) {
            List<Face> faces = renderFaces();
            faces.sort(Comparator.comparingDouble(Face::depth));
            for (Face face : faces) if (polygon(face.points()).contains(mouseX, mouseY)) return new FaceSelection(face.object(), face.triangle());
            return null;
        }

        private AssetStudioProject.MeshObject hitTestObject(int mouseX, int mouseY) {
            AssetStudioProject.MeshObject result = null;
            double bestDepth = Double.POSITIVE_INFINITY;
            for (Face face : renderFaces()) if (polygon(face.points()).contains(mouseX, mouseY) && face.depth() < bestDepth) { result = face.object(); bestDepth = face.depth(); }
            return result;
        }

        @Override public void addNotify() {
            super.addNotify();
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseDragged(MouseEvent event) {
                    if ((event.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0) {
                        yaw += (event.getX() - orbitX) * 0.012;
                        pitch += (event.getY() - orbitY) * 0.012;
                        pitch = Math.max(-1.45, Math.min(1.45, pitch));
                        orbitX = event.getX(); orbitY = event.getY(); repaint();
                    }
                }
                @Override public void mouseMoved(MouseEvent event) { orbitX = event.getX(); orbitY = event.getY(); }
            });
        }
    }

    private BufferedImage loadTextureSafely(int textureIndex) {
        try { return project == null ? null : project.loadTexture(textureIndex); }
        catch (IOException exception) { return null; }
    }

    private boolean drawTexturedFace(Graphics2D g, Polygon polygon, Face face, BufferedImage image) {
        AssetStudioProject.Triangle triangle = face.triangle();
        if (triangle.u == null || triangle.v == null || triangle.u.length != 3 || triangle.v.length != 3) return false;
        double[] sx = new double[3], sy = new double[3], dx = new double[3], dy = new double[3];
        for (int corner = 0; corner < 3; corner++) { sx[corner] = triangle.u[corner] * Math.max(1, image.getWidth() - 1) / 255.0; sy[corner] = triangle.v[corner] * Math.max(1, image.getHeight() - 1) / 255.0; dx[corner] = face.points()[corner].x(); dy[corner] = face.points()[corner].y(); }
        double determinant = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0]);
        if (Math.abs(determinant) < 0.000001) return false;
        double a = ((dx[1] - dx[0]) * (sy[2] - sy[0]) - (dx[2] - dx[0]) * (sy[1] - sy[0])) / determinant;
        double c = ((dx[2] - dx[0]) * (sx[1] - sx[0]) - (dx[1] - dx[0]) * (sx[2] - sx[0])) / determinant;
        double b = ((dy[1] - dy[0]) * (sy[2] - sy[0]) - (dy[2] - dy[0]) * (sy[1] - sy[0])) / determinant;
        double d = ((dy[2] - dy[0]) * (sx[1] - sx[0]) - (dy[1] - dy[0]) * (sx[2] - sx[0])) / determinant;
        double e = dx[0] - a * sx[0] - c * sy[0], f = dy[0] - b * sx[0] - d * sy[0];
        java.awt.Shape oldClip = g.getClip();
        g.clip(polygon);
        g.drawImage(image, new AffineTransform(a, b, c, d, e, f), null);
        g.setClip(oldClip);
        return true;
    }

    private static Polygon polygon(Projected[] points) {
        Polygon polygon = new Polygon();
        for (Projected point : points) polygon.addPoint((int) point.x(), (int) point.y());
        return polygon;
    }

    private record Projected(double x, double y, double depth) { }
    private record Face(Projected[] points, double depth, AssetStudioProject.MeshObject object,
                        AssetStudioProject.Triangle triangle) { }
    private record Hit(AssetStudioProject.MeshObject object, FaceSelection face, int vertexIndex) { }
}

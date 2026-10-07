import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.swing.JPanel;

/** Orthographic, editable view of the current Sketch document. */
public final class SketchViewport2D extends JPanel {
    private static final Color GRID = new Color(48, 57, 70);
    private static final Color GRID_MAJOR = new Color(66, 79, 96);
    private static final Color AXIS = new Color(111, 139, 163);
    private static final Color BOX = new Color(82, 157, 199, 80);
    private static final Color BOX_SELECTED = new Color(96, 190, 232, 115);
    private static final Color LINE = new Color(246, 190, 82);
    private static final Color SELECTION = new Color(164, 226, 255);
    private static final int HANDLE_SIZE = 8;

    private final SketchDocument document;
    private final Set<Long> selection;
    private final Runnable beforeEdit;
    private final Runnable changed;
    private SketchView view = SketchView.TOP;
    private SketchTool tool = SketchTool.SELECT;
    private double centerU;
    private double centerV;
    private double scale = 0.85;
    private boolean creating;
    private boolean panning;
    private Point lastMouse;
    private double startU;
    private double startV;
    private Hit dragHit = Hit.none();
    private final Map<Long, SketchShape> originals = new HashMap<>();

    public SketchViewport2D(SketchDocument document, Set<Long> selection,
                            Runnable beforeEdit, Runnable changed) {
        this.document = document;
        this.selection = selection;
        this.beforeEdit = beforeEdit;
        this.changed = changed;
        setBackground(AssistantTheme.CODE_BACKGROUND);
        setOpaque(true);
        setPreferredSize(new Dimension(520, 620));
        setBorder(AssistantTheme.titled("2D Sketch"));
        setFocusable(true);
        installMouseHandling();
    }

    public void setView(SketchView view) {
        this.view = view;
        setBorder(AssistantTheme.titled("2D Sketch - " + view.label() + " (" + view.axes() + ")"));
        repaint();
    }

    public void setTool(SketchTool tool) {
        this.tool = tool;
        setCursor(Cursor.getPredefinedCursor(tool == SketchTool.SELECT
                ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
    }

    public void fitAll() {
        if (document.shapes.isEmpty()) {
            centerU = 0;
            centerV = 0;
            scale = 0.85;
            repaint();
            return;
        }
        double minU = Double.MAX_VALUE, minV = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (SketchShape shape : document.shapes) {
            minU = Math.min(minU, projectU(shape.minX(), shape.minY(), shape.minZ()));
            maxU = Math.max(maxU, projectU(shape.maxX(), shape.maxY(), shape.maxZ()));
            minV = Math.min(minV, projectV(shape.minX(), shape.minY(), shape.minZ()));
            maxV = Math.max(maxV, projectV(shape.maxX(), shape.maxY(), shape.maxZ()));
        }
        centerU = (minU + maxU) / 2.0;
        centerV = (minV + maxV) / 2.0;
        double width = Math.max(document.grid * 4.0, maxU - minU);
        double height = Math.max(document.grid * 4.0, maxV - minV);
        scale = Math.min(Math.max(1, getWidth() - 70) / width,
                Math.max(1, getHeight() - 70) / height);
        scale = Math.max(0.08, Math.min(4.0, scale * 0.86));
        repaint();
    }

    private void installMouseHandling() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                lastMouse = event.getPoint();
                if (event.getButton() == MouseEvent.BUTTON2
                        || (event.getButton() == MouseEvent.BUTTON1 && event.isAltDown())) {
                    panning = true;
                    return;
                }
                if (event.getButton() != MouseEvent.BUTTON1) return;
                Point2D world = screenToWorld(event.getX(), event.getY());
                startU = world.getX();
                startV = world.getY();
                if (tool == SketchTool.LINE || tool == SketchTool.RECTANGLE) {
                    beforeEdit.run();
                    creating = true;
                    repaint();
                    return;
                }
                Hit hit = hitTest(event.getX(), event.getY());
                if (hit.kind == HitKind.NONE) {
                    if (!event.isControlDown()) selection.clear();
                    repaint();
                    return;
                }
                if (event.isShiftDown()) {
                    if (!selection.add(hit.shape.id)) selection.remove(hit.shape.id);
                } else if (!selection.contains(hit.shape.id)) {
                    selection.clear();
                    selection.add(hit.shape.id);
                }
                dragHit = hit;
                originals.clear();
                for (SketchShape shape : document.shapes) {
                    if (selection.contains(shape.id)) originals.put(shape.id, shape.copy());
                }
                beforeEdit.run();
                repaint();
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (lastMouse == null) lastMouse = event.getPoint();
                if (panning) {
                    centerU -= (event.getX() - lastMouse.x) / scale;
                    centerV += (event.getY() - lastMouse.y) / scale;
                    lastMouse = event.getPoint();
                    repaint();
                    return;
                }
                if (creating || dragHit.kind != HitKind.NONE) {
                    if (creating) repaint();
                    else updateDrag(event);
                }
            }

            @Override public void mouseReleased(MouseEvent event) {
                if (panning) {
                    panning = false;
                    return;
                }
                if (creating) {
                    Point2D end = screenToWorld(event.getX(), event.getY());
                    double endU = snap(end.getX());
                    double endV = snap(end.getY());
                    if (Math.abs(endU - snap(startU)) >= document.grid
                            || Math.abs(endV - snap(startV)) >= document.grid) {
                        SketchShape created = tool == SketchTool.LINE
                                ? document.addLine(view, snap(startU), snap(startV), endU, endV)
                                : document.addBox(view, snap(startU), snap(startV), endU, endV);
                        selection.clear();
                        selection.add(created.id);
                        changed.run();
                    }
                    creating = false;
                    repaint();
                }
                dragHit = Hit.none();
                originals.clear();
                lastMouse = null;
            }

            @Override public void mouseExited(MouseEvent event) {
                if (!creating) setToolCursor();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(event -> {
            double before = scale;
            scale = Math.max(0.08, Math.min(4.0,
                    scale * Math.pow(1.12, -event.getPreciseWheelRotation())));
            Point2D atCursor = screenToWorld(event.getX(), event.getY(), before);
            Point2D after = screenToWorld(event.getX(), event.getY());
            centerU += atCursor.getX() - after.getX();
            centerV += atCursor.getY() - after.getY();
            repaint();
            event.consume();
        });
    }

    private void updateDrag(MouseEvent event) {
        Point2D world = screenToWorld(event.getX(), event.getY());
        double currentU = snap(world.getX());
        double currentV = snap(world.getY());
        double deltaU = currentU - snap(startU);
        double deltaV = currentV - snap(startV);
        SketchShape original = originals.get(dragHit.shape.id);
        SketchShape target = find(dragHit.shape.id);
        if (original == null || target == null) return;
        if (dragHit.kind == HitKind.MOVE) {
            for (Map.Entry<Long, SketchShape> entry : originals.entrySet()) {
                SketchShape copy = entry.getValue();
                SketchShape destination = find(entry.getKey());
                if (destination != null) translate(destination, copy, deltaU, deltaV);
            }
        } else if (original.kind == SketchShape.Kind.LINE) {
            copyShape(target, original);
            if (dragHit.kind == HitKind.ENDPOINT_1) setProjectedPoint(target, true, currentU, currentV);
            else setProjectedPoint(target, false, currentU, currentV);
        } else {
            copyShape(target, original);
            applyBoxHandle(target, currentU, currentV, dragHit.handleIndex);
        }
        changed.run();
        repaint();
    }

    private void translate(SketchShape destination, SketchShape original,
                           double deltaU, double deltaV) {
        copyShape(destination, original);
        if (destination.kind == SketchShape.Kind.LINE) {
            setProjectedPoint(destination, true,
                    projectU(destination.x1, destination.y1, destination.z1) + deltaU,
                    projectV(destination.x1, destination.y1, destination.z1) + deltaV);
            setProjectedPoint(destination, false,
                    projectU(destination.x2, destination.y2, destination.z2) + deltaU,
                    projectV(destination.x2, destination.y2, destination.z2) + deltaV);
        } else {
            for (double[] vertex : destination.vertexData()) {
                setProjectedCoordinates(vertex,
                        projectU(vertex[0], vertex[1], vertex[2]) + deltaU,
                        projectV(vertex[0], vertex[1], vertex[2]) + deltaV);
            }
            destination.refreshBounds();
        }
    }

    private void applyBoxHandle(SketchShape shape, double u, double v, int handle) {
        int[][] cornerPairs = projectedCornerIndices();
        if (handle >= 0 && handle < cornerPairs.length) {
            setProjectedCorner(shape, cornerPairs[handle], u, v);
            shape.refreshBounds();
            return;
        }
        int edge = handle - 4;
        int[][] edgeCorners = {{0, 1}, {1, 2}, {2, 3}, {3, 0}};
        if (edge >= 0 && edge < edgeCorners.length) {
            for (int corner : edgeCorners[edge]) {
                double[] vertex = shape.vertexData()[projectedCornerIndices()[corner][0]];
                double currentU = projectU(vertex[0], vertex[1], vertex[2]);
                double currentV = projectV(vertex[0], vertex[1], vertex[2]);
                setProjectedCorner(shape, cornerPairs[corner],
                        edge == 0 || edge == 2 ? currentU : u,
                        edge == 1 || edge == 3 ? currentV : v);
            }
            shape.refreshBounds();
        }
    }

    private void setProjectedCorner(SketchShape shape, int[] vertexIndices, double u, double v) {
        for (int index : vertexIndices) setProjectedCoordinates(shape.vertexData()[index], u, v);
    }

    private void setProjectedCoordinates(double[] vertex, double u, double v) {
        if (view == SketchView.TOP) {
            vertex[0] = u; vertex[1] = v;
        } else if (view == SketchView.FRONT) {
            vertex[0] = u; vertex[2] = v;
        } else {
            vertex[1] = u; vertex[2] = v;
        }
    }

    private void setProjectedPoint(SketchShape shape, boolean first, double u, double v) {
        if (view == SketchView.TOP) {
            if (first) { shape.x1 = u; shape.y1 = v; }
            else { shape.x2 = u; shape.y2 = v; }
        } else if (view == SketchView.FRONT) {
            if (first) { shape.x1 = u; shape.z1 = v; }
            else { shape.x2 = u; shape.z2 = v; }
        } else if (first) {
            shape.y1 = u; shape.z1 = v;
        } else {
            shape.y2 = u; shape.z2 = v;
        }
    }

    private Hit hitTest(int x, int y) {
        for (SketchShape shape : document.shapes) {
            if (!selection.contains(shape.id)) continue;
            Hit handle = handleHit(shape, x, y);
            if (handle.kind != HitKind.NONE) return handle;
        }
        for (int index = document.shapes.size() - 1; index >= 0; index--) {
            SketchShape shape = document.shapes.get(index);
            if (shape.kind == SketchShape.Kind.LINE) {
                Point2D a = project(shape.x1, shape.y1, shape.z1);
                Point2D b = project(shape.x2, shape.y2, shape.z2);
                if (Line2D.ptSegDist(a.getX(), a.getY(), b.getX(), b.getY(), x, y) <= 8)
                    return new Hit(HitKind.MOVE, shape, -1);
            } else {
                Path2D path = projectedPath(shape);
                if (path.contains(x, y) || distanceToPath(path, x, y) <= 7)
                    return new Hit(HitKind.MOVE, shape, -1);
            }
        }
        return Hit.none();
    }

    private Hit handleHit(SketchShape shape, int x, int y) {
        if (shape.kind == SketchShape.Kind.LINE) {
            Point2D first = project(shape.x1, shape.y1, shape.z1);
            Point2D second = project(shape.x2, shape.y2, shape.z2);
            if (first.distance(x, y) <= 9) return new Hit(HitKind.ENDPOINT_1, shape, 0);
            if (second.distance(x, y) <= 9) return new Hit(HitKind.ENDPOINT_2, shape, 1);
            return Hit.none();
        }
        Point2D[] corners = corners(shape);
        for (int index = 0; index < corners.length; index++) {
            if (corners[index].distance(x, y) <= 9) return new Hit(HitKind.CORNER, shape, index);
        }
        Point2D[][] edges = {
                {corners[0], corners[1]}, {corners[1], corners[2]},
                {corners[2], corners[3]}, {corners[3], corners[0]}
        };
        for (int index = 0; index < edges.length; index++) {
            if (Line2D.ptSegDist(edges[index][0].getX(), edges[index][0].getY(),
                    edges[index][1].getX(), edges[index][1].getY(), x, y) <= 7)
                return new Hit(HitKind.EDGE, shape, index + 4);
        }
        return Hit.none();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawGrid(g);
        for (SketchShape shape : document.shapes) drawShape(g, shape);
        if (creating) drawCreationPreview(g);
        g.setColor(AssistantTheme.MUTED);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString("Ctrl+click selects  |  drag corners/edges to edit  |  wheel zooms", 12, getHeight() - 12);
        g.dispose();
    }

    private void drawGrid(Graphics2D g) {
        double left = screenToWorld(0, 0).getX();
        double right = screenToWorld(getWidth(), 0).getX();
        double top = screenToWorld(0, 0).getY();
        double bottom = screenToWorld(0, getHeight()).getY();
        double step = document.grid;
        while (step * scale < 12) step *= 2;
        while (step * scale > 130) step /= 2;
        for (double u = Math.floor(left / step) * step; u <= right; u += step) {
            int x = (int) Math.round(worldToScreen(u, 0).getX());
            g.setColor(Math.abs(Math.round(u / step)) % 4 == 0 ? GRID_MAJOR : GRID);
            g.drawLine(x, 0, x, getHeight());
        }
        for (double v = Math.floor(bottom / step) * step; v <= top; v += step) {
            int y = (int) Math.round(worldToScreen(0, v).getY());
            g.setColor(Math.abs(Math.round(v / step)) % 4 == 0 ? GRID_MAJOR : GRID);
            g.drawLine(0, y, getWidth(), y);
        }
        g.setColor(AXIS);
        int axisU = (int) Math.round(worldToScreen(0, 0).getX());
        int axisV = (int) Math.round(worldToScreen(0, 0).getY());
        g.drawLine(axisU, 0, axisU, getHeight());
        g.drawLine(0, axisV, getWidth(), axisV);
        g.setColor(AssistantTheme.MUTED);
        g.drawString("0", axisU + 4, axisV - 4);
    }

    private void drawShape(Graphics2D g, SketchShape shape) {
        boolean selected = selection.contains(shape.id);
        if (shape.kind == SketchShape.Kind.LINE) {
            Point2D first = project(shape.x1, shape.y1, shape.z1);
            Point2D second = project(shape.x2, shape.y2, shape.z2);
            g.setColor(selected ? SELECTION : LINE);
            g.setStroke(new BasicStroke(selected ? 3f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(first, second));
            if (selected) {
                drawHandle(g, first, true);
                drawHandle(g, second, true);
            }
            return;
        }
        g.setColor(selected ? BOX_SELECTED : BOX);
        Path2D path = projectedPath(shape);
        g.fill(path);
        g.setColor(selected ? SELECTION : new Color(83, 174, 218));
        g.setStroke(new BasicStroke(selected ? 2.2f : 1.2f));
        g.draw(path);
        if (selected) for (Point2D corner : corners(shape)) drawHandle(g, corner, true);
    }

    private void drawCreationPreview(Graphics2D g) {
        Point2D start = worldToScreen(snap(startU), snap(startV));
        Point mouse = getMousePositionSafe();
        Point2D current = screenToWorld(mouse.getX(), mouse.getY());
        Point2D end = worldToScreen(snap(current.getX()), snap(current.getY()));
        g.setColor(new Color(246, 190, 82, 160));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[] { 6f, 5f }, 0));
        if (tool == SketchTool.LINE) g.draw(new Line2D.Double(start, end));
        else g.draw(new Rectangle2D.Double(Math.min(start.getX(), end.getX()),
                Math.min(start.getY(), end.getY()), Math.abs(end.getX() - start.getX()),
                Math.abs(end.getY() - start.getY())));
    }

    private Point getMousePositionSafe() {
        Point point = getMousePosition();
        return point == null ? (lastMouse == null ? new Point() : lastMouse) : point;
    }

    private void drawHandle(Graphics2D g, Point2D point, boolean filled) {
        int x = (int) Math.round(point.getX()) - HANDLE_SIZE / 2;
        int y = (int) Math.round(point.getY()) - HANDLE_SIZE / 2;
        g.setColor(filled ? SELECTION : AssistantTheme.TEXT);
        g.fillRect(x, y, HANDLE_SIZE, HANDLE_SIZE);
        g.setColor(AssistantTheme.CODE_BACKGROUND);
        g.drawRect(x, y, HANDLE_SIZE, HANDLE_SIZE);
    }

    private Point2D[] corners(SketchShape shape) {
        Point2D[] corners = new Point2D[4];
        for (int index = 0; index < corners.length; index++) corners[index] = projectCorner(shape, index);
        return corners;
    }

    private Point2D projectCorner(SketchShape shape, int index) {
        double[] vertex = shape.vertexData()[projectedCornerIndices()[index][0]];
        return project(vertex[0], vertex[1], vertex[2]);
    }

    private int[][] projectedCornerIndices() {
        return view == SketchView.TOP
                ? new int[][] {{0, 4}, {1, 5}, {2, 6}, {3, 7}}
                : view == SketchView.FRONT
                ? new int[][] {{0, 3}, {1, 2}, {5, 6}, {4, 7}}
                : new int[][] {{0, 1}, {3, 2}, {7, 6}, {4, 5}};
    }

    private Path2D projectedPath(SketchShape shape) {
        Point2D[] points = corners(shape);
        Path2D path = new Path2D.Double();
        path.moveTo(points[0].getX(), points[0].getY());
        for (int index = 1; index < points.length; index++) path.lineTo(points[index].getX(), points[index].getY());
        path.closePath();
        return path;
    }

    private double distanceToPath(Path2D path, double x, double y) {
        java.awt.geom.PathIterator iterator = path.getPathIterator(null);
        double[] coordinates = new double[6];
        Point2D first = null, previous = null;
        double distance = Double.MAX_VALUE;
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coordinates);
            if (type == java.awt.geom.PathIterator.SEG_MOVETO) {
                first = previous = new Point2D.Double(coordinates[0], coordinates[1]);
            } else if (type == java.awt.geom.PathIterator.SEG_LINETO) {
                Point2D current = new Point2D.Double(coordinates[0], coordinates[1]);
                distance = Math.min(distance, Line2D.ptSegDist(previous.getX(), previous.getY(),
                        current.getX(), current.getY(), x, y));
                previous = current;
            } else if (type == java.awt.geom.PathIterator.SEG_CLOSE && first != null) {
                distance = Math.min(distance, Line2D.ptSegDist(previous.getX(), previous.getY(),
                        first.getX(), first.getY(), x, y));
            }
            iterator.next();
        }
        return distance;
    }

    private SketchShape find(long id) {
        for (SketchShape shape : document.shapes) if (shape.id == id) return shape;
        return null;
    }

    private void copyShape(SketchShape target, SketchShape source) {
        target.kind = source.kind;
        target.name = source.name;
        target.minX = source.minX; target.minY = source.minY; target.minZ = source.minZ;
        target.maxX = source.maxX; target.maxY = source.maxY; target.maxZ = source.maxZ;
        target.x1 = source.x1; target.y1 = source.y1; target.z1 = source.z1;
        target.x2 = source.x2; target.y2 = source.y2; target.z2 = source.z2;
        target.vertices = source.vertices == null ? null : java.util.Arrays.stream(source.vertices)
                .map(double[]::clone).toArray(double[][]::new);
    }

    private double projectU(double x, double y, double z) {
        return view == SketchView.TOP ? x : view == SketchView.FRONT ? x : y;
    }

    private double projectV(double x, double y, double z) {
        return view == SketchView.TOP ? y : z;
    }

    private double projectMinU(SketchShape shape) {
        return view == SketchView.TOP ? shape.minX() : view == SketchView.FRONT ? shape.minX() : shape.minY();
    }
    private double projectMaxU(SketchShape shape) {
        return view == SketchView.TOP ? shape.maxX() : view == SketchView.FRONT ? shape.maxX() : shape.maxY();
    }
    private double projectMinV(SketchShape shape) { return view == SketchView.TOP ? shape.minY() : shape.minZ(); }
    private double projectMaxV(SketchShape shape) { return view == SketchView.TOP ? shape.maxY() : shape.maxZ(); }

    private Point2D project(double x, double y, double z) { return worldToScreen(projectU(x, y, z), projectV(x, y, z)); }

    private Point2D worldToScreen(double u, double v) {
        return new Point2D.Double(getWidth() / 2.0 + (u - centerU) * scale,
                getHeight() / 2.0 - (v - centerV) * scale);
    }

    private Point2D screenToWorld(double x, double y) { return screenToWorld(x, y, scale); }

    private Point2D screenToWorld(double x, double y, double usedScale) {
        return new Point2D.Double(centerU + (x - getWidth() / 2.0) / usedScale,
                centerV - (y - getHeight() / 2.0) / usedScale);
    }

    private double snap(double value) { return Math.rint(value / document.grid) * document.grid; }

    private void setToolCursor() {
        setCursor(Cursor.getPredefinedCursor(tool == SketchTool.SELECT
                ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
    }

    private enum HitKind { NONE, MOVE, CORNER, EDGE, ENDPOINT_1, ENDPOINT_2 }
    private record Hit(HitKind kind, SketchShape shape, int handleIndex) {
        static Hit none() { return new Hit(HitKind.NONE, null, -1); }
    }
}

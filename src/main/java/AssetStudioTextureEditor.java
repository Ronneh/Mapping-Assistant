import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Face texture assignment and UnrealEd-style UV alignment tools. */
public final class AssetStudioTextureEditor extends JPanel {
    private AssetStudioProject project;
    private final AssetStudioViewport viewport;
    private final Runnable changeListener;
    private final JLabel selectedFace = new JLabel("No face selected");
    private final JLabel textureInfo = new JLabel("No texture imported");
    private final JCheckBox quadMode = new JCheckBox("Treat the matching triangle as one quad", true);
    private final JCheckBox relativeScale = new JCheckBox("Relative", true);
    private final JComboBox<TextureSlot> textureSlots = new JComboBox<>();
    private final JTextField customPan = new JTextField("16");
    private final JTextField customRotation = new JTextField("45");
    private final JTextField scaleU = new JTextField("1.0");
    private final JTextField scaleV = new JTextField("1.0");
    private final TextureCanvas canvas = new TextureCanvas();
    private AssetStudioViewport.FaceSelection selection;
    private int activeTextureIndex;
    private boolean refreshingSlots;

    public AssetStudioTextureEditor(AssetStudioProject project, AssetStudioViewport viewport,
                                    Runnable changeListener) {
        super(new BorderLayout(6, 6));
        this.project = project;
        this.viewport = viewport;
        this.changeListener = changeListener;
        setOpaque(false);
        viewport.setFaceSelectionListener(this::selectFace);
        add(createHeader(), BorderLayout.NORTH);
        add(new JScrollPane(canvas), BorderLayout.CENTER);
        add(createControls(), BorderLayout.SOUTH);
        refresh();
    }

    public void setProject(AssetStudioProject project) {
        this.project = project;
        selection = null;
        refresh();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.setOpaque(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.setOpaque(false);
        top.add(button("Import PNG/JPEG", event -> importTexture()));
        top.add(new JLabel("Texture:"));
        textureSlots.addActionListener(event -> {
            if (!refreshingSlots && textureSlots.getSelectedItem() instanceof TextureSlot slot) {
                activeTextureIndex = slot.index;
                refresh();
            }
        });
        textureSlots.setPrototypeDisplayValue(new TextureSlot(0, "AssetStudioTexture"));
        top.add(textureSlots);
        top.add(button("Apply to selected face", event -> applyTexture()));
        top.add(quadMode);
        quadMode.setOpaque(false);
        header.add(top, BorderLayout.NORTH);
        JPanel info = new JPanel(new GridLayout(2, 1));
        info.setOpaque(false);
        info.add(selectedFace);
        info.add(textureInfo);
        header.add(info, BorderLayout.SOUTH);
        return header;
    }

    private JPanel createControls() {
        JPanel all = new JPanel(new GridLayout(0, 1, 3, 3));
        all.setOpaque(false);
        all.add(section("Pan U / V", createPanControls()));
        all.add(section("Rotation", createRotationControls()));
        all.add(section("Scaling", createScalingControls()));
        all.add(section("Alignment", createAlignmentControls()));
        return all;
    }

    private JPanel createPanControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        panel.setOpaque(false);
        panel.add(new JLabel("U:"));
        for (int amount : new int[] {1, 4, 16, 64}) {
            panel.add(button("-" + amount, event -> pan(-amount, 0)));
            panel.add(button("+" + amount, event -> pan(amount, 0)));
        }
        panel.add(new JLabel("V:"));
        for (int amount : new int[] {1, 4, 16, 64}) {
            panel.add(button("-" + amount, event -> pan(0, -amount)));
            panel.add(button("+" + amount, event -> pan(0, amount)));
        }
        customPan.setColumns(4);
        panel.add(new JLabel("Custom"));
        panel.add(customPan);
        panel.add(button("U+", event -> safe(() -> pan(customPanValue(), 0), "UV pan")));
        panel.add(button("U-", event -> safe(() -> pan(-customPanValue(), 0), "UV pan")));
        panel.add(button("V+", event -> safe(() -> pan(0, customPanValue()), "UV pan")));
        panel.add(button("V-", event -> safe(() -> pan(0, -customPanValue()), "UV pan")));
        return panel;
    }

    private JPanel createRotationControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        panel.setOpaque(false);
        for (double degrees : new double[] {11.25, 22.5, 45, 90, 180}) {
            panel.add(button("+" + degrees + "°", event -> rotate(degrees)));
            panel.add(button("-" + degrees + "°", event -> rotate(-degrees)));
        }
        customRotation.setColumns(4);
        panel.add(new JLabel("Custom"));
        panel.add(customRotation);
        panel.add(button("Apply", event -> safe(() -> rotate(customRotationValue()), "UV rotation")));
        panel.add(button("Flip U", event -> flip(true)));
        panel.add(button("Flip V", event -> flip(false)));
        return panel;
    }

    private JPanel createScalingControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        panel.setOpaque(false);
        JComboBox<String> presets = new JComboBox<>(new String[] {"0.25", "0.5", "1.0", "2.0", "Custom"});
        presets.addActionListener(event -> {
            String value = (String) presets.getSelectedItem();
            if (!"Custom".equals(value)) {
                scaleU.setText(value);
                scaleV.setText(value);
            }
        });
        panel.add(new JLabel("Preset"));
        panel.add(presets);
        scaleU.setColumns(4); scaleV.setColumns(4);
        panel.add(new JLabel("U/Hor.")); panel.add(scaleU);
        panel.add(new JLabel("V/Vert.")); panel.add(scaleV);
        relativeScale.setOpaque(false);
        panel.add(relativeScale);
        panel.add(button("Apply", event -> scale()));
        return panel;
    }

    private JPanel createAlignmentControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        panel.setOpaque(false);
        panel.add(button("Align to Floor", event -> align(Alignment.FLOOR)));
        panel.add(button("Wall Direction", event -> align(Alignment.WALL)));
        panel.add(button("Wall Pan", event -> align(Alignment.WALL_PAN)));
        panel.add(button("Auto", event -> align(Alignment.AUTO)));
        panel.add(button("Wall for Cylinder", event -> align(Alignment.CYLINDER)));
        panel.add(button("One Tile", event -> align(Alignment.ONE_TILE)));
        panel.add(button("U", event -> align(Alignment.U)));
        panel.add(button("V", event -> align(Alignment.V)));
        panel.add(button("Unalign", event -> align(Alignment.UNALIGN)));
        return panel;
    }

    private JPanel section(String title, JPanel content) {
        JPanel section = new JPanel(new BorderLayout());
        section.setOpaque(false);
        section.setBorder(BorderFactory.createCompoundBorder(
                AssistantTheme.titled(title), BorderFactory.createEmptyBorder(2, 3, 2, 3)));
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JButton button(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(listener);
        return button;
    }

    private void selectFace(AssetStudioViewport.FaceSelection selected) {
        selection = selected;
        if (project.textures != null && selected.triangle().textureIndex >= 0
                && selected.triangle().textureIndex < project.textures.size())
            activeTextureIndex = selected.triangle().textureIndex;
        refresh();
    }

    private void refresh() {
        try {
            project.ensureTextureSlots();
        } catch (IOException exception) {
            textureInfo.setText("Invalid embedded texture: " + exception.getMessage());
        }
        refreshingSlots = true;
        textureSlots.removeAllItems();
        if (project.textures != null) {
            for (int index = 0; index < project.textures.size(); index++)
                textureSlots.addItem(new TextureSlot(index, project.textures.get(index).name));
        }
        if (textureSlots.getItemCount() > 0) {
            activeTextureIndex = Math.max(0, Math.min(activeTextureIndex, textureSlots.getItemCount() - 1));
            textureSlots.setSelectedIndex(activeTextureIndex);
        }
        refreshingSlots = false;
        if (selection == null) selectedFace.setText("No face selected - click a triangle in the 3D preview");
        else selectedFace.setText("Face selected: " + selection.object().name
                + " | texture slot " + selection.triangle().textureIndex
                + (quadMode.isSelected() ? " | quad mode" : " | triangle mode"));
        try {
            BufferedImage image = project == null ? null : project.loadTexture(activeTextureIndex);
            textureInfo.setText(image == null ? "No texture imported - the export uses the generated default texture"
                    : "Texture: " + project.textures.get(activeTextureIndex).originalFileName + " ("
                    + image.getWidth() + "x" + image.getHeight() + ")");
            canvas.setImage(image);
        } catch (IOException exception) {
            textureInfo.setText("Invalid embedded texture: " + exception.getMessage());
            canvas.setImage(null);
        }
        canvas.repaint();
    }

    private void importTexture() {
        JFileChooser chooser = new DarkFileChooser(new File(System.getProperty("user.home")));
        chooser.setDialogTitle("Import UED-compatible texture");
        chooser.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg)",
                "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            BufferedImage image = ImageIO.read(chooser.getSelectedFile());
            activeTextureIndex = project.addTexture(image, chooser.getSelectedFile().getName());
            refresh();
            changed();
        } catch (IOException exception) {
            DarkDialogs.message(this, exception.getMessage(), "Texture import", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyTexture() {
        if (!requireFace()) return;
        if (project.textures == null || project.textures.isEmpty()) {
            DarkDialogs.message(this, "Import a texture before assigning it to a face.",
                    "Texture assignment", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        for (AssetStudioProject.Triangle triangle : selectedTriangles()) triangle.textureIndex = activeTextureIndex;
        changed();
    }

    private void pan(int u, int v) {
        if (!requireFace()) return;
        forEachCorner((triangle, corner) -> {
            triangle.u[corner] = wrap(triangle.u[corner] + u);
            triangle.v[corner] = wrap(triangle.v[corner] + v);
        });
        changed();
    }

    private void rotate(double degrees) {
        if (!requireFace()) return;
        double[] center = uvCenter();
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        forEachCorner((triangle, corner) -> {
            double u = triangle.u[corner] - center[0];
            double v = triangle.v[corner] - center[1];
            triangle.u[corner] = wrap((int) Math.round(center[0] + u * cos - v * sin));
            triangle.v[corner] = wrap((int) Math.round(center[1] + u * sin + v * cos));
        });
        changed();
    }

    private void flip(boolean horizontal) {
        if (!requireFace()) return;
        forEachCorner((triangle, corner) -> {
            if (horizontal) triangle.u[corner] = 255 - triangle.u[corner];
            else triangle.v[corner] = 255 - triangle.v[corner];
        });
        changed();
    }

    private void scale() {
        if (!requireFace()) return;
        try {
            double uScale = positive(scaleU, "U/Hor. scale");
            double vScale = positive(scaleV, "V/Vert. scale");
            if (!relativeScale.isSelected()) {
                double[] extent = uvExtents();
                if (extent[0] > 0.000001) uScale = uScale * 255 / extent[0];
                if (extent[1] > 0.000001) vScale = vScale * 255 / extent[1];
            }
            double[] center = uvCenter();
            final double appliedUScale = uScale;
            final double appliedVScale = vScale;
            forEachCorner((triangle, corner) -> {
                triangle.u[corner] = wrap((int) Math.round(center[0]
                        + (triangle.u[corner] - center[0]) * appliedUScale));
                triangle.v[corner] = wrap((int) Math.round(center[1]
                        + (triangle.v[corner] - center[1]) * appliedVScale));
            });
            changed();
        } catch (IllegalArgumentException exception) {
            DarkDialogs.message(this, exception.getMessage(), "UV scaling", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private void align(Alignment alignment) {
        if (!requireFace()) return;
        if (alignment == Alignment.UNALIGN) {
            for (AssetStudioProject.Triangle triangle : selectedTriangles()) {
                triangle.u = new int[] {0, 255, 255};
                triangle.v = new int[] {0, 0, 255};
            }
        } else if (alignment == Alignment.U || alignment == Alignment.V) {
            alignSingleAxis(alignment == Alignment.U);
        } else {
            projectFaceCoordinates(alignment);
        }
        changed();
    }

    private void alignSingleAxis(boolean uAxis) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (AssetStudioProject.Triangle triangle : selectedTriangles()) {
            for (int index : triangle.vertexIndices) {
                AssetStudioProject.Vertex world = worldVertex(index);
                double value = uAxis ? world.x : world.y;
                min = Math.min(min, value); max = Math.max(max, value);
            }
        }
        double range = max - min;
        final double minimum = min;
        forEachCorner((triangle, corner) -> {
            AssetStudioProject.Vertex world = worldVertex(triangle.vertexIndices[corner]);
            double value = uAxis ? world.x : world.y;
            int mapped = range < 0.000001 ? 0 : (int) Math.round((value - minimum) * 255 / range);
            if (uAxis) triangle.u[corner] = mapped;
            else triangle.v[corner] = mapped;
        });
    }

    private void projectFaceCoordinates(Alignment alignment) {
        List<AssetStudioProject.Vertex> points = new ArrayList<>();
        for (AssetStudioProject.Triangle triangle : selectedTriangles())
            for (int index : triangle.vertexIndices) points.add(worldVertex(index));
        double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
        int uAxis = 0, vAxis = 1;
        if (alignment == Alignment.FLOOR) { uAxis = 0; vAxis = 2; }
        else if (alignment == Alignment.WALL || alignment == Alignment.WALL_PAN) { uAxis = 0; vAxis = 1; }
        else if (alignment == Alignment.CYLINDER) { uAxis = 3; vAxis = 1; }
        else if (alignment == Alignment.AUTO || alignment == Alignment.ONE_TILE) {
            double[] ranges = axisRanges(points);
            int first = 0;
            if (ranges[1] > ranges[first]) first = 1;
            if (ranges[2] > ranges[first]) first = 2;
            int second = first == 0 ? 1 : 0;
            for (int axis = 0; axis < 3; axis++)
                if (axis != first && ranges[axis] > ranges[second]) second = axis;
            uAxis = first; vAxis = second;
        }
        for (AssetStudioProject.Vertex point : points) {
            double u = coordinate(point, uAxis), v = coordinate(point, vAxis);
            if (alignment == Alignment.CYLINDER) u = Math.atan2(point.z, point.x);
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            minV = Math.min(minV, v); maxV = Math.max(maxV, v);
        }
        double rangeU = maxU - minU, rangeV = maxV - minV;
        final int selectedUAxis = uAxis;
        final int selectedVAxis = vAxis;
        final double minimumU = minU;
        final double minimumV = minV;
        forEachCorner((triangle, corner) -> {
            AssetStudioProject.Vertex point = worldVertex(triangle.vertexIndices[corner]);
            double u = alignment == Alignment.CYLINDER ? Math.atan2(point.z, point.x)
                    : coordinate(point, selectedUAxis);
            double v = coordinate(point, selectedVAxis);
            triangle.u[corner] = rangeU < 0.000001 ? 0 : (int) Math.round((u - minimumU) * 255 / rangeU);
            triangle.v[corner] = rangeV < 0.000001 ? 0 : (int) Math.round((v - minimumV) * 255 / rangeV);
        });
    }

    private double[] axisRanges(List<AssetStudioProject.Vertex> points) {
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (AssetStudioProject.Vertex point : points) for (int axis = 0; axis < 3; axis++) {
            double value = coordinate(point, axis);
            min[axis] = Math.min(min[axis], value); max[axis] = Math.max(max[axis], value);
        }
        return new double[] {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
    }

    private static double coordinate(AssetStudioProject.Vertex point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    private AssetStudioProject.Vertex worldVertex(int index) {
        return AssetStudioProject.transform(selection.object(), selection.object().vertices.get(index));
    }

    private List<AssetStudioProject.Triangle> selectedTriangles() {
        List<AssetStudioProject.Triangle> triangles = new ArrayList<>();
        if (selection == null) return triangles;
        triangles.add(selection.triangle());
        if (!quadMode.isSelected()) return triangles;
        for (AssetStudioProject.Triangle triangle : selection.object().triangles) {
            if (triangle != selection.triangle() && sharedVertices(triangle, selection.triangle()) >= 2) {
                triangles.add(triangle);
                break;
            }
        }
        return triangles;
    }

    private static int sharedVertices(AssetStudioProject.Triangle first,
                                      AssetStudioProject.Triangle second) {
        int shared = 0;
        for (int a : first.vertexIndices) for (int b : second.vertexIndices) if (a == b) shared++;
        return shared;
    }

    private void forEachCorner(CornerOperation operation) {
        for (AssetStudioProject.Triangle triangle : selectedTriangles()) {
            if (triangle.u == null || triangle.u.length != 3) triangle.u = new int[] {0, 255, 255};
            if (triangle.v == null || triangle.v.length != 3) triangle.v = new int[] {0, 0, 255};
            for (int corner = 0; corner < 3; corner++) operation.apply(triangle, corner);
        }
    }

    private double[] uvCenter() {
        double minU = 255, maxU = 0, minV = 255, maxV = 0;
        for (AssetStudioProject.Triangle triangle : selectedTriangles()) for (int corner = 0; corner < 3; corner++) {
            minU = Math.min(minU, triangle.u[corner]); maxU = Math.max(maxU, triangle.u[corner]);
            minV = Math.min(minV, triangle.v[corner]); maxV = Math.max(maxV, triangle.v[corner]);
        }
        return new double[] {(minU + maxU) / 2, (minV + maxV) / 2};
    }

    private double[] uvExtents() {
        double minU = 255, maxU = 0, minV = 255, maxV = 0;
        for (AssetStudioProject.Triangle triangle : selectedTriangles()) for (int corner = 0; corner < 3; corner++) {
            minU = Math.min(minU, triangle.u[corner]); maxU = Math.max(maxU, triangle.u[corner]);
            minV = Math.min(minV, triangle.v[corner]); maxV = Math.max(maxV, triangle.v[corner]);
        }
        return new double[] {maxU - minU, maxV - minV};
    }

    private int customPanValue() { return integer(customPan, "Custom pan"); }
    private double customRotationValue() { return number(customRotation, "Custom rotation"); }

    private double positive(JTextField field, String label) {
        double value = number(field, label);
        if (value <= 0) throw new IllegalArgumentException(label + " must be greater than zero.");
        return value;
    }

    private double number(JTextField field, String label) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a finite number.");
        }
    }

    private int integer(JTextField field, String label) {
        try { return Integer.parseInt(field.getText().trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(label + " must be an integer."); }
    }

    private boolean requireFace() {
        if (selection != null) return true;
        DarkDialogs.message(this, "Select a triangle in the 3D preview first.",
                "Texture alignment", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    private void changed() {
        project.syncLegacyFromObjects();
        changeListener.run();
        viewport.repaint();
        canvas.repaint();
    }

    private void safe(Runnable operation, String title) {
        try {
            operation.run();
        } catch (IllegalArgumentException exception) {
            DarkDialogs.message(this, exception.getMessage(), title, javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private static int wrap(int value) {
        int result = value % 256;
        return result < 0 ? result + 256 : result;
    }

    private enum Alignment { FLOOR, WALL, WALL_PAN, AUTO, CYLINDER, ONE_TILE, U, V, UNALIGN }
    private interface CornerOperation { void apply(AssetStudioProject.Triangle triangle, int corner); }
    private record TextureSlot(int index, String name) {
        @Override public String toString() { return index + ": " + name; }
    }

    private final class TextureCanvas extends JPanel {
        private BufferedImage image;

        TextureCanvas() {
            setPreferredSize(new Dimension(320, 230));
            setBackground(new Color(35, 39, 47));
            setBorder(BorderFactory.createLineBorder(AssistantTheme.BORDER));
        }

        void setImage(BufferedImage image) { this.image = image; }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int margin = 12;
            int width = getWidth() - margin * 2;
            int height = getHeight() - margin * 2;
            if (image != null) {
                double factor = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
                int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * factor));
                int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * factor));
                int x = (getWidth() - drawWidth) / 2;
                int y = (getHeight() - drawHeight) / 2;
                g.drawImage(image, x, y, drawWidth, drawHeight, null);
                if (selection != null) drawUvOverlay(g, x, y, drawWidth, drawHeight);
            } else {
                g.setColor(AssistantTheme.MUTED);
                g.drawString("Import a power-of-two texture to preview UVs", margin, getHeight() / 2);
            }
            g.dispose();
        }

        private void drawUvOverlay(Graphics2D g, int x, int y, int width, int height) {
            g.setColor(new Color(255, 235, 90));
            g.setStroke(new java.awt.BasicStroke(2f));
            for (AssetStudioProject.Triangle triangle : selectedTriangles()) {
                if (triangle.u == null || triangle.v == null) continue;
                for (int corner = 0; corner < 3; corner++) {
                    int next = (corner + 1) % 3;
                    int firstX = x + triangle.u[corner] * width / 255;
                    int firstY = y + triangle.v[corner] * height / 255;
                    int nextX = x + triangle.u[next] * width / 255;
                    int nextY = y + triangle.v[next] * height / 255;
                    g.drawLine(firstX, firstY, nextX, nextY);
                    g.fillOval(firstX - 3, firstY - 3, 7, 7);
                }
            }
        }
    }
}

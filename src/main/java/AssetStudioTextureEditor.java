import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import javax.swing.TransferHandler;
import javax.swing.KeyStroke;
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
    private final JPanel textureBrowser = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final List<AssetStudioViewport.FaceSelection> selections = new ArrayList<>();
    private final Map<AssetStudioProject.Triangle, int[][]> scaleBaselines = new HashMap<>();
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
        installTextureDrop(this);
        installTextureDrop(canvas);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ctrl V"), "pasteTexture");
        getActionMap().put("pasteTexture", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { pasteTexture(); }
        });
        add(createHeader(), BorderLayout.NORTH);
        add(new JScrollPane(canvas), BorderLayout.CENTER);
        add(createControls(), BorderLayout.SOUTH);
        refresh();
    }

    public void setProject(AssetStudioProject project) {
        this.project = project;
        selections.clear();
        scaleBaselines.clear();
        refresh();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.setOpaque(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.setOpaque(false);
        top.add(button("Import, Paste or Drag&Drop a texture", event -> importTexture()));
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
        textureBrowser.setOpaque(false);
        header.add(new JScrollPane(textureBrowser), BorderLayout.CENTER);
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
        JPanel panel = new JPanel(new GridLayout(3, 1, 2, 1));
        panel.setOpaque(false);
        JPanel uRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        uRow.setOpaque(false);
        uRow.add(new JLabel("U:"));
        for (int amount : new int[] {1, 4, 16, 64}) {
            uRow.add(button("-" + amount, event -> pan(-amount, 0)));
            uRow.add(button("+" + amount, event -> pan(amount, 0)));
        }
        JPanel vRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        vRow.setOpaque(false);
        vRow.add(new JLabel("V:"));
        for (int amount : new int[] {1, 4, 16, 64}) {
            vRow.add(button("-" + amount, event -> pan(0, -amount)));
            vRow.add(button("+" + amount, event -> pan(0, amount)));
        }
        JPanel customRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        customRow.setOpaque(false);
        customPan.setColumns(4);
        customRow.add(new JLabel("Custom:"));
        customRow.add(customPan);
        customRow.add(button("U+", event -> safe(() -> pan(customPanValue(), 0), "UV pan")));
        customRow.add(button("U-", event -> safe(() -> pan(-customPanValue(), 0), "UV pan")));
        customRow.add(button("V+", event -> safe(() -> pan(0, customPanValue()), "UV pan")));
        customRow.add(button("V-", event -> safe(() -> pan(0, -customPanValue()), "UV pan")));
        panel.add(uRow); panel.add(vRow); panel.add(customRow);
        return panel;
    }

    private JPanel createRotationControls() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 2, 1));
        panel.setOpaque(false);
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        presetRow.setOpaque(false);
        for (double degrees : new double[] {11.25, 22.5, 45, 90, 180}) {
            presetRow.add(button("+" + degrees, event -> rotate(degrees)));
            presetRow.add(button("-" + degrees, event -> rotate(-degrees)));
        }
        JPanel customRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        customRow.setOpaque(false);
        customRotation.setColumns(4);
        customRow.add(new JLabel("Custom:"));
        customRow.add(customRotation);
        customRow.add(button("Apply", event -> safe(() -> rotate(customRotationValue()), "UV rotation")));
        JPanel flipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        flipRow.setOpaque(false);
        flipRow.add(button("Flip U", event -> flip(true)));
        flipRow.add(button("Flip V", event -> flip(false)));
        panel.add(presetRow); panel.add(customRow); panel.add(flipRow);
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

    private void selectFace(List<AssetStudioViewport.FaceSelection> selected) {
        selections.clear();
        if (selected != null) selections.addAll(selected);
        scaleBaselines.clear();
        if (!selections.isEmpty()) {
            AssetStudioProject.Triangle triangle = selections.get(0).triangle();
            if (project.textures != null && triangle.textureIndex >= 0
                    && triangle.textureIndex < project.textures.size()) activeTextureIndex = triangle.textureIndex;
        }
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
        if (selections.isEmpty()) selectedFace.setText("No face selected - click a face; hold Ctrl to select more");
        else selectedFace.setText(selections.size() + " face(s) selected"
                + (quadMode.isSelected() ? " | coplanar quad pairing enabled" : " | triangle mode"));
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
        refreshTextureBrowser();
        canvas.repaint();
    }

    private void refreshTextureBrowser() {
        textureBrowser.removeAll();
        if (project == null || project.textures == null) return;
        for (int index = 0; index < project.textures.size(); index++) {
            final int textureIndex = index;
            JPanel tile = new JPanel(new BorderLayout(2, 2));
            tile.setOpaque(false);
            JLabel preview = new JLabel();
            preview.setHorizontalAlignment(JLabel.CENTER);
            preview.setPreferredSize(new Dimension(72, 54));
            try {
                BufferedImage image = project.loadTexture(index);
                if (image != null) preview.setIcon(new javax.swing.ImageIcon(
                        image.getScaledInstance(64, 48, Image.SCALE_FAST)));
            } catch (IOException ignored) { }
            preview.setBorder(BorderFactory.createLineBorder(index == activeTextureIndex
                    ? AssistantTheme.ACCENT : AssistantTheme.BORDER, index == activeTextureIndex ? 2 : 1));
            preview.setToolTipText(project.textures.get(index).name + " - click to select");
            preview.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                    activeTextureIndex = textureIndex;
                    refreshingSlots = true;
                    textureSlots.setSelectedIndex(textureIndex);
                    refreshingSlots = false;
                    refresh();
                }
            });
            tile.add(preview, BorderLayout.CENTER);
            JLabel name = new JLabel(project.textures.get(index).name, JLabel.CENTER);
            name.setForeground(AssistantTheme.MUTED);
            tile.add(name, BorderLayout.SOUTH);
            textureBrowser.add(tile);
        }
        textureBrowser.revalidate();
        textureBrowser.repaint();
    }

    private void installTextureDrop(JPanel target) {
        target.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(DataFlavor.imageFlavor);
            }

            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        Object value = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        if (value instanceof List<?> files && !files.isEmpty() && files.get(0) instanceof File file) {
                            importImage(ImageIO.read(file), file.getName());
                            return true;
                        }
                    }
                    if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        Image image = (Image) support.getTransferable().getTransferData(DataFlavor.imageFlavor);
                        BufferedImage buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = buffered.createGraphics();
                        graphics.drawImage(image, 0, 0, null);
                        graphics.dispose();
                        importImage(buffered, "pasted-texture");
                        return true;
                    }
                } catch (Exception exception) {
                    DarkDialogs.message(AssetStudioTextureEditor.this, exception.getMessage(),
                            "Texture import", javax.swing.JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        });
    }

    private void pasteTexture() {
        try {
            if (!Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                DarkDialogs.message(this, "The clipboard does not contain an image.", "Texture import", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Image image = (Image) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.imageFlavor);
            BufferedImage buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = buffered.createGraphics();
            graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
            importImage(buffered, "pasted-texture");
        } catch (Exception exception) {
            DarkDialogs.message(this, exception.getMessage(), "Texture import", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importImage(BufferedImage image, String name) throws IOException {
        if (image == null) throw new IOException("The selected source does not contain a readable image.");
        activeTextureIndex = project.addTexture(image, name);
        refresh();
        changed();
    }

    private void importTexture() {
        JFileChooser chooser = new DarkFileChooser(new File(System.getProperty("user.home")));
        chooser.setDialogTitle("Import, paste or drag-and-drop a texture");
        chooser.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg)",
                "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            importImage(ImageIO.read(chooser.getSelectedFile()), chooser.getSelectedFile().getName());
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
        scaleBaselines.clear();
        forEachCorner((triangle, corner) -> {
            triangle.u[corner] = wrap(triangle.u[corner] + u);
            triangle.v[corner] = wrap(triangle.v[corner] + v);
        });
        changed();
    }

    private void rotate(double degrees) {
        if (!requireFace()) return;
        scaleBaselines.clear();
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
        scaleBaselines.clear();
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
            double[] center = uvCenter();
            final double appliedUScale = uScale;
            final double appliedVScale = vScale;
            if (!relativeScale.isSelected()) captureScaleBaselines();
            for (AssetStudioProject.Triangle triangle : selectedTriangles()) {
                int[][] baseline = scaleBaselines.get(triangle);
                for (int corner = 0; corner < 3; corner++) {
                    int baseU = baseline == null ? triangle.u[corner] : baseline[0][corner];
                    int baseV = baseline == null ? triangle.v[corner] : baseline[1][corner];
                    triangle.u[corner] = wrap((int) Math.round(center[0] + (baseU - center[0]) * appliedUScale));
                    triangle.v[corner] = wrap((int) Math.round(center[1] + (baseV - center[1]) * appliedVScale));
                }
            }
            if (relativeScale.isSelected()) scaleBaselines.clear();
            changed();
        } catch (IllegalArgumentException exception) {
            DarkDialogs.message(this, exception.getMessage(), "UV scaling", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private void align(Alignment alignment) {
        if (!requireFace()) return;
        scaleBaselines.clear();
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
        AssetStudioViewport.FaceSelection first = selections.get(0);
        return AssetStudioProject.transform(first.object(), first.object().vertices.get(index));
    }

    private List<AssetStudioProject.Triangle> selectedTriangles() {
        List<AssetStudioProject.Triangle> triangles = new ArrayList<>();
        if (selections.isEmpty()) return triangles;
        for (AssetStudioViewport.FaceSelection selected : selections) {
            if (!triangles.contains(selected.triangle())) triangles.add(selected.triangle());
        }
        if (!quadMode.isSelected()) return triangles;
        for (AssetStudioViewport.FaceSelection selected : new ArrayList<>(selections)) {
            for (AssetStudioProject.Triangle triangle : selected.object().triangles) {
                if (triangle != selected.triangle() && sharedVertices(triangle, selected.triangle()) >= 2
                        && samePlane(selected.object(), triangle, selected.triangle())
                        && !triangles.contains(triangle)) {
                    triangles.add(triangle);
                }
            }
        }
        return triangles;
    }

    private boolean samePlane(AssetStudioProject.MeshObject object,
                              AssetStudioProject.Triangle first,
                              AssetStudioProject.Triangle second) {
        if (first.vertexIndices.length != 3 || second.vertexIndices.length != 3) return false;
        double[] a = normal(object, first), b = normal(object, second);
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return Math.abs(Math.abs(dot) - 1.0) < 0.02;
    }

    private double[] normal(AssetStudioProject.MeshObject object, AssetStudioProject.Triangle triangle) {
        AssetStudioProject.Vertex a = object.vertices.get(triangle.vertexIndices[0]);
        AssetStudioProject.Vertex b = object.vertices.get(triangle.vertexIndices[1]);
        AssetStudioProject.Vertex c = object.vertices.get(triangle.vertexIndices[2]);
        double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
        double vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return length < 0.000001 ? new double[] {0, 0, 0} : new double[] {nx / length, ny / length, nz / length};
    }

    private void captureScaleBaselines() {
        for (AssetStudioProject.Triangle triangle : selectedTriangles()) {
            if (scaleBaselines.containsKey(triangle)) continue;
            scaleBaselines.put(triangle, new int[][] {triangle.u.clone(), triangle.v.clone()});
        }
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
        if (!selections.isEmpty()) return true;
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
                if (!selections.isEmpty()) drawUvOverlay(g, x, y, drawWidth, drawHeight);
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

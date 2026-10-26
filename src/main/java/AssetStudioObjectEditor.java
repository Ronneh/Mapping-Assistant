import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.DefaultListModel;

/** Object-level scene editor used instead of requiring users to enter every vertex. */
public final class AssetStudioObjectEditor extends JPanel {
    private AssetStudioProject project;
    private final AssetStudioViewport viewport;
    private final Consumer<AssetStudioProject> replaceProject;
    private final Runnable changeListener;
    private final DefaultListModel<AssetStudioProject.MeshObject> objectModel = new DefaultListModel<>();
    private final JList<AssetStudioProject.MeshObject> objectList = new JList<>(objectModel);
    private final JTextField objectName = new JTextField();
    private final JTextField positionX = new JTextField("0");
    private final JTextField positionY = new JTextField("0");
    private final JTextField positionZ = new JTextField("0");
    private final JTextField rotationX = new JTextField("0");
    private final JTextField rotationY = new JTextField("0");
    private final JTextField rotationZ = new JTextField("0");
    private final JTextField scale = new JTextField("1");

    public AssetStudioObjectEditor(AssetStudioProject project, AssetStudioViewport viewport,
                                   Consumer<AssetStudioProject> replaceProject, Runnable changeListener) {
        super(new BorderLayout(6, 6));
        this.project = project;
        this.viewport = viewport;
        this.replaceProject = replaceProject;
        this.changeListener = changeListener;
        setOpaque(false);
        add(createActions(), BorderLayout.NORTH);
        objectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        objectList.setVisibleRowCount(8);
        objectList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectObject(objectList.getSelectedValue());
        });
        add(new JScrollPane(objectList), BorderLayout.CENTER);
        add(createTransformPanel(), BorderLayout.SOUTH);
        viewport.setSelectionListener(this::selectObject);
        viewport.setChangeListener(() -> {
            loadSelectedFields();
            changed();
        });
        refresh();
    }

    public void setProject(AssetStudioProject project) {
        this.project = project;
        refresh();
    }

    private JPanel createActions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        actions.add(button("New animal", event -> replaceProject.accept(AssetStudioProject.createLowPolyAnimal())));
        actions.add(button("New cube", event -> replaceProject.accept(AssetStudioProject.createDemoCube())));
        actions.add(button("Empty scene", event -> {
            AssetStudioProject empty = new AssetStudioProject();
            empty.meshName = "AssetStudioScene";
            replaceProject.accept(empty);
        }));
        actions.add(button("Add box", event -> addObject(AssetStudioProject.createBoxObject(
                nextName("Box"), 20, 20, 20, 0, 10, 0))));
        actions.add(button("Add pyramid", event -> addObject(AssetStudioProject.createPyramidObject(
                nextName("Pyramid"), 18, 18, 24, 0, 10, 0))));
        actions.add(button("Duplicate", event -> duplicateSelected()));
        actions.add(button("Delete", event -> deleteSelected()));
        return actions;
    }

    private JPanel createTransformPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                AssistantTheme.titled("Selected object"), BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        addField(fields, 0, "Name", objectName);
        addField(fields, 1, "Position X/Y/Z", positionX, positionY, positionZ);
        addField(fields, 2, "Rotation X/Y/Z", rotationX, rotationY, rotationZ);
        addField(fields, 3, "Uniform scale", scale);
        panel.add(fields, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        controls.setOpaque(false);
        JRadioButton move = new JRadioButton("Move", true);
        JRadioButton rotate = new JRadioButton("Rotate");
        JRadioButton scaleMode = new JRadioButton("Scale");
        move.setOpaque(false); rotate.setOpaque(false); scaleMode.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        group.add(move); group.add(rotate); group.add(scaleMode);
        move.addActionListener(event -> viewport.setInteractionMode(AssetStudioViewport.InteractionMode.MOVE));
        rotate.addActionListener(event -> viewport.setInteractionMode(AssetStudioViewport.InteractionMode.ROTATE));
        scaleMode.addActionListener(event -> viewport.setInteractionMode(AssetStudioViewport.InteractionMode.SCALE));
        controls.add(move); controls.add(rotate); controls.add(scaleMode);
        controls.add(button("Apply", event -> applyFields()));
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private void addField(JPanel panel, int row, String label, JTextField... fields) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0; labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(3, 0, 3, 5);
        panel.add(new JLabel(label + ":"), labelConstraints);
        JPanel values = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        values.setOpaque(false);
        for (JTextField field : fields) {
            field.setColumns(5);
            values.add(field);
        }
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1; fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1; fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(3, 0, 3, 0);
        panel.add(values, fieldConstraints);
    }

    private JButton button(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(listener);
        return button;
    }

    private void refresh() {
        objectModel.clear();
        if (project != null) {
            project.ensureSceneObjects();
            for (AssetStudioProject.MeshObject object : project.objects) objectModel.addElement(object);
        }
        if (!objectModel.isEmpty()) objectList.setSelectedIndex(0);
        viewport.setProject(project);
        loadSelectedFields();
    }

    private void selectObject(AssetStudioProject.MeshObject object) {
        if (object == null) return;
        objectList.setSelectedValue(object, true);
        viewport.setSelectedObject(object);
        loadSelectedFields();
    }

    private AssetStudioProject.MeshObject selected() {
        return objectList.getSelectedValue();
    }

    private void loadSelectedFields() {
        AssetStudioProject.MeshObject object = selected();
        boolean enabled = object != null;
        objectName.setEnabled(enabled);
        positionX.setEnabled(enabled); positionY.setEnabled(enabled); positionZ.setEnabled(enabled);
        rotationX.setEnabled(enabled); rotationY.setEnabled(enabled); rotationZ.setEnabled(enabled);
        scale.setEnabled(enabled);
        if (!enabled) return;
        objectName.setText(object.name);
        positionX.setText(Double.toString(object.x));
        positionY.setText(Double.toString(object.y));
        positionZ.setText(Double.toString(object.z));
        rotationX.setText(Double.toString(object.rotationX));
        rotationY.setText(Double.toString(object.rotationY));
        rotationZ.setText(Double.toString(object.rotationZ));
        scale.setText(Double.toString(object.scaleX));
    }

    private void applyFields() {
        AssetStudioProject.MeshObject object = selected();
        if (object == null) return;
        try {
            object.name = objectName.getText().trim().isEmpty() ? "Object" : objectName.getText().trim();
            object.x = number(positionX, "Position X"); object.y = number(positionY, "Position Y");
            object.z = number(positionZ, "Position Z"); object.rotationX = number(rotationX, "Rotation X");
            object.rotationY = number(rotationY, "Rotation Y"); object.rotationZ = number(rotationZ, "Rotation Z");
            double uniformScale = number(scale, "Scale");
            if (uniformScale <= 0) throw new IllegalArgumentException("Scale must be greater than zero.");
            object.scaleX = uniformScale; object.scaleY = uniformScale; object.scaleZ = uniformScale;
            objectList.repaint();
            changed();
        } catch (IllegalArgumentException exception) {
            DarkDialogs.message(this, exception.getMessage(), "Asset Studio", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
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

    private void addObject(AssetStudioProject.MeshObject object) {
        project.ensureSceneObjects();
        project.objects.add(object);
        objectModel.addElement(object);
        objectList.setSelectedValue(object, true);
        changed();
    }

    private void duplicateSelected() {
        AssetStudioProject.MeshObject source = selected();
        if (source == null) return;
        AssetStudioProject.MeshObject copy = new AssetStudioProject.MeshObject();
        copy.name = nextName(source.name + "Copy");
        copy.primitiveType = source.primitiveType;
        copy.x = source.x + 8; copy.y = source.y; copy.z = source.z + 8;
        copy.rotationX = source.rotationX; copy.rotationY = source.rotationY; copy.rotationZ = source.rotationZ;
        copy.scaleX = source.scaleX; copy.scaleY = source.scaleY; copy.scaleZ = source.scaleZ;
        for (AssetStudioProject.Vertex vertex : source.vertices)
            copy.vertices.add(new AssetStudioProject.Vertex(vertex.x, vertex.y, vertex.z));
        for (AssetStudioProject.Triangle triangle : source.triangles) {
            AssetStudioProject.Triangle triangleCopy = new AssetStudioProject.Triangle();
            triangleCopy.vertexIndices = triangle.vertexIndices.clone();
            triangleCopy.u = triangle.u == null ? null : triangle.u.clone();
            triangleCopy.v = triangle.v == null ? null : triangle.v.clone();
            triangleCopy.textureIndex = triangle.textureIndex;
            copy.triangles.add(triangleCopy);
        }
        addObject(copy);
    }

    private void deleteSelected() {
        AssetStudioProject.MeshObject object = selected();
        if (object == null) return;
        project.objects.remove(object);
        objectModel.removeElement(object);
        if (!objectModel.isEmpty()) objectList.setSelectedIndex(Math.min(0, objectModel.size() - 1));
        viewport.setSelectedObject(null);
        changed();
    }

    private String nextName(String base) {
        int suffix = 1;
        String candidate = base;
        boolean used;
        do {
            used = false;
            for (AssetStudioProject.MeshObject object : project.objects) {
                if (candidate.equals(object.name)) {
                    used = true;
                    candidate = base + (++suffix);
                    break;
                }
            }
        } while (used);
        return candidate;
    }

    private void changed() {
        project.syncLegacyFromObjects();
        changeListener.run();
        viewport.repaint();
        loadSelectedFields();
    }
}

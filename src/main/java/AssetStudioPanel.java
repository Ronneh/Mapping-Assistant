import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.imageio.ImageIO;

/** Editable mesh and texture workflow for UT99 package generation. */
public final class AssetStudioPanel extends JPanel {
    private static final Preferences ASSET_STUDIO_PREFS =
            Preferences.userNodeForPackage(AssetStudioPanel.class).node("asset-studio");
    private AssetStudioProject project = AssetStudioProject.createLowPolyFigure();
    private Path currentFile;
    private final JTextField packageName = new JTextField();
    private final JTextField actorClassName = new JTextField();
    private final JTextField meshName = new JTextField();
    private final JTextField textureName = new JTextField();
    private final JTextField utRoot = new JTextField();
    private final JTextField uccPath = new JTextField();
    private final JTextArea log = new JTextArea();
    private final JLabel status = new JLabel();
    private final AssetStudioViewport viewport;
    private final JTable verticesTable = new JTable();
    private final JTable trianglesTable = new JTable();
    private final JTextField vertexX = new JTextField("0");
    private final JTextField vertexY = new JTextField("0");
    private final JTextField vertexZ = new JTextField("0");
    private final JTextField triangleA = new JTextField("0");
    private final JTextField triangleB = new JTextField("1");
    private final JTextField triangleC = new JTextField("2");
    private final JLabel texturePreview = new JLabel("No custom texture imported");
    private final AssetStudioObjectEditor objectEditor;
    private final AssetStudioTextureEditor textureEditor;
    private final Deque<AssetStudioProject> undoHistory = new ArrayDeque<>();
    private final Deque<AssetStudioProject> redoHistory = new ArrayDeque<>();
    private AssetStudioProject historySnapshot;
    private boolean restoringHistory;
    private VertexTableModel verticesModel;
    private TriangleTableModel trianglesModel;

    public AssetStudioPanel() {
        super(new BorderLayout(10, 10));
        setBackground(AssistantTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(12, 16, 14, 16));
        viewport = new AssetStudioViewport(project);
        objectEditor = new AssetStudioObjectEditor(project, viewport, this::replaceProject, this::sceneChanged);
        textureEditor = new AssetStudioTextureEditor(project, viewport, this::sceneChanged);
        add(createHeader(), BorderLayout.NORTH);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewport, createInspector());
        main.setResizeWeight(0.68);
        AssistantTheme.styleSplitPane(main);
        add(main, BorderLayout.CENTER);
        loadFields();
        historySnapshot = project.copy();
        log.setText("Create sources writes a real UT99 Models/Classes package below the selected root.\n"
                + "Compile with UCC verifies the generated .u file and keeps compiler output.\n");
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Asset Studio");
        AssistantTheme.stylePageTitle(title);
        header.add(title, BorderLayout.WEST);
        JLabel description = new JLabel("Build low-poly object scenes and export them to UT99");
        description.setForeground(AssistantTheme.MUTED);
        header.add(description, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(button("New figure", event -> newDemo()));
        actions.add(button("Open", event -> openProject()));
        actions.add(button("Save", event -> saveProject(false)));
        actions.add(button("Save as...", event -> saveProject(true)));
        header.add(actions, BorderLayout.EAST);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke("ctrl Z"), "assetUndo");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke("ctrl shift Z"), "assetRedo");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke("ctrl Y"), "assetRedo");
        getActionMap().put("assetUndo", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { undo(); }
        });
        getActionMap().put("assetRedo", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { redo(); }
        });
        return header;
    }

    private JPanel createInspector() {
        JPanel inspector = new JPanel(new BorderLayout(8, 8));
        inspector.setBackground(AssistantTheme.PANEL);
        inspector.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AssistantTheme.BORDER),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        addField(fields, 0, "Package", packageName, null);
        addField(fields, 1, "Actor class", actorClassName, null);
        addField(fields, 2, "Mesh", meshName, null);
        addField(fields, 3, "Texture", textureName, null);
        inspector.add(fields, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        AssistantTheme.styleTabbedPane(tabs);
        tabs.addTab("Objects", objectEditor);
        tabs.addTab("Texture & UV", textureEditor);
        tabs.addTab("Texture & Export", createExportEditor());
        inspector.add(tabs, BorderLayout.CENTER);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setRows(11);
        inspector.add(new JScrollPane(log), BorderLayout.SOUTH);
        return inspector;
    }

    private JPanel createMeshEditor() {
        JPanel editor = new JPanel(new BorderLayout(6, 6));
        editor.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        actions.add(button("New cube", event -> newDemo()));
        actions.add(button("New mesh", event -> clearMesh()));
        actions.add(button("Add vertex", event -> addVertex()));
        actions.add(button("Remove vertex", event -> removeVertex()));
        actions.add(button("Add triangle", event -> addTriangle()));
        actions.add(button("Remove triangle", event -> removeTriangle()));
        editor.add(actions, BorderLayout.NORTH);

        verticesModel = new VertexTableModel();
        trianglesModel = new TriangleTableModel();
        verticesTable.setModel(verticesModel);
        trianglesTable.setModel(trianglesModel);
        verticesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        trianglesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        verticesTable.getSelectionModel().addListSelectionListener(event -> loadSelectedVertex());
        verticesTable.setPreferredScrollableViewportSize(new Dimension(260, 120));
        trianglesTable.setPreferredScrollableViewportSize(new Dimension(260, 120));
        JSplitPane tables = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(verticesTable), new JScrollPane(trianglesTable));
        tables.setResizeWeight(0.5);
        AssistantTheme.styleSplitPane(tables);
        editor.add(tables, BorderLayout.CENTER);

        JPanel editing = new JPanel(new GridBagLayout());
        editing.setOpaque(false);
        addField(editing, 0, "Vertex X", vertexX, null);
        addField(editing, 1, "Vertex Y", vertexY, null);
        addField(editing, 2, "Vertex Z", vertexZ, null);
        GridBagConstraints apply = new GridBagConstraints();
        apply.gridx = 2; apply.gridy = 0; apply.gridheight = 3;
        apply.insets = new Insets(4, 6, 4, 0);
        editing.add(button("Apply vertex", event -> applyVertex()), apply);
        JPanel triangleFields = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        triangleFields.setOpaque(false);
        triangleFields.add(new JLabel("Triangle indices:"));
        triangleFields.add(triangleA); triangleFields.add(triangleB); triangleFields.add(triangleC);
        triangleA.setColumns(3); triangleB.setColumns(3); triangleC.setColumns(3);
        GridBagConstraints triangleConstraints = new GridBagConstraints();
        triangleConstraints.gridx = 0; triangleConstraints.gridy = 3; triangleConstraints.gridwidth = 3;
        triangleConstraints.weightx = 1; triangleConstraints.fill = GridBagConstraints.HORIZONTAL;
        triangleConstraints.insets = new Insets(4, 0, 0, 0);
        editing.add(triangleFields, triangleConstraints);
        editor.add(editing, BorderLayout.SOUTH);
        return editor;
    }

    private JPanel createExportEditor() {
        JPanel export = new JPanel(new BorderLayout(6, 8));
        export.setOpaque(false);
        JPanel paths = new JPanel(new GridBagLayout());
        paths.setOpaque(false);
        addField(paths, 0, "UT99 root", utRoot,
                event -> chooseDirectory(utRoot, "Choose UT99 installation root"));
        addField(paths, 1, "UCC executable", uccPath,
                event -> chooseFile(uccPath, "Choose ucc.exe"));
        export.add(paths, BorderLayout.NORTH);

        JPanel texture = new JPanel(new BorderLayout(6, 6));
        texture.setOpaque(false);
        texture.setBorder(AssistantTheme.titled("Texture"));
        texturePreview.setHorizontalAlignment(JLabel.CENTER);
        texturePreview.setPreferredSize(new Dimension(220, 130));
        texture.add(texturePreview, BorderLayout.CENTER);
        JLabel textureHint = new JLabel("Select or import textures in Texture & UV. The active texture is reused for export.");
        textureHint.setForeground(AssistantTheme.MUTED);
        texture.add(textureHint, BorderLayout.SOUTH);
        export.add(texture, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        actions.add(button("Create sources", event -> exportSources()));
        actions.add(button("Compile with UCC", event -> compileWithUcc()));
        JLabel hint = new JLabel("Package: <UT99 root>\\<Package>");
        hint.setForeground(AssistantTheme.MUTED);
        actions.add(hint);
        export.add(actions, BorderLayout.SOUTH);
        return export;
    }

    private void addField(JPanel panel, int row, String label, JTextField field,
                          java.awt.event.ActionListener browse) {
        field.setColumns(17);
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0; labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(4, 0, 4, 6);
        panel.add(new JLabel(label + ":"), labelConstraints);
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1; fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1; fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(4, 0, 4, browse == null ? 0 : 4);
        panel.add(field, fieldConstraints);
        if (browse != null) {
            GridBagConstraints buttonConstraints = new GridBagConstraints();
            buttonConstraints.gridx = 2; buttonConstraints.gridy = row;
            buttonConstraints.insets = new Insets(4, 0, 4, 0);
            panel.add(button("...", browse), buttonConstraints);
        }
    }

    private JButton button(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(listener);
        return button;
    }

    private void newDemo() {
        String previousRoot = utRoot.getText();
        String previousUcc = uccPath.getText();
        AssetStudioProject next = AssetStudioProject.createLowPolyFigure();
        next.unrealTournamentRoot = previousRoot;
        next.uccPath = previousUcc;
        replaceProject(next);
        log("New low-poly starter figure scene created.");
    }

    private void replaceProject(AssetStudioProject next) {
        project = next;
        currentFile = null;
        loadFields();
        resetHistory();
    }

    private void sceneChanged() {
        if (!restoringHistory && historySnapshot != null) {
            AssetStudioProject current = project.copy();
            if (!current.snapshotSignature().equals(historySnapshot.snapshotSignature())) {
                undoHistory.push(historySnapshot);
                historySnapshot = current;
                redoHistory.clear();
            }
        }
        updateMeshStatus();
        viewport.repaint();
    }

    private void resetHistory() {
        undoHistory.clear();
        redoHistory.clear();
        historySnapshot = project.copy();
    }

    private void undo() {
        if (undoHistory.isEmpty()) return;
        redoHistory.push(project.copy());
        project = undoHistory.pop();
        restoringHistory = true;
        loadFields();
        restoringHistory = false;
        historySnapshot = project.copy();
        log("Undo");
    }

    private void redo() {
        if (redoHistory.isEmpty()) return;
        undoHistory.push(project.copy());
        project = redoHistory.pop();
        restoringHistory = true;
        loadFields();
        restoringHistory = false;
        historySnapshot = project.copy();
        log("Redo");
    }

    private void clearMesh() {
        project.vertices.clear();
        project.triangles.clear();
        refreshMeshTables();
        status.setText("Empty mesh");
        log("Created an empty mesh. Add vertices and triangles below.");
        sceneChanged();
    }

    private void loadFields() {
        if (project.unrealTournamentRoot == null || project.unrealTournamentRoot.isBlank())
            project.unrealTournamentRoot = ASSET_STUDIO_PREFS.get("ut99-root", "");
        if (project.uccPath == null || project.uccPath.isBlank())
            project.uccPath = ASSET_STUDIO_PREFS.get("ucc-path", "");
        viewport.setProject(project);
        objectEditor.setProject(project);
        textureEditor.setProject(project);
        packageName.setText(project.packageName);
        actorClassName.setText(project.actorClassName);
        meshName.setText(project.meshName);
        textureName.setText(project.textureName);
        utRoot.setText(project.unrealTournamentRoot);
        uccPath.setText(project.uccPath);
        status.setText(project.vertices.size() + " vertices, " + project.triangles.size() + " triangles");
        refreshMeshTables();
        updateTexturePreview();
        viewport.repaint();
    }

    private void refreshMeshTables() {
        if (verticesModel != null) verticesModel.fireTableDataChanged();
        if (trianglesModel != null) trianglesModel.fireTableDataChanged();
        if (verticesTable.getRowCount() > 0 && verticesTable.getSelectedRow() < 0)
            verticesTable.setRowSelectionInterval(0, 0);
        viewport.repaint();
    }

    private void loadSelectedVertex() {
        int row = verticesTable.getSelectedRow();
        if (row < 0 || row >= project.vertices.size()) return;
        AssetStudioProject.Vertex vertex = project.vertices.get(row);
        vertexX.setText(Double.toString(vertex.x));
        vertexY.setText(Double.toString(vertex.y));
        vertexZ.setText(Double.toString(vertex.z));
    }

    private void addVertex() {
        try {
            AssetStudioProject.Vertex vertex = new AssetStudioProject.Vertex(
                    parseDouble(vertexX, "Vertex X"), parseDouble(vertexY, "Vertex Y"),
                    parseDouble(vertexZ, "Vertex Z"));
            project.vertices.add(vertex);
            refreshMeshTables();
            verticesTable.setRowSelectionInterval(project.vertices.size() - 1, project.vertices.size() - 1);
            updateMeshStatus();
            sceneChanged();
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void applyVertex() {
        int row = verticesTable.getSelectedRow();
        if (row < 0 || row >= project.vertices.size()) {
            showError("Select a vertex first.");
            return;
        }
        try {
            AssetStudioProject.Vertex vertex = project.vertices.get(row);
            vertex.x = parseDouble(vertexX, "Vertex X");
            vertex.y = parseDouble(vertexY, "Vertex Y");
            vertex.z = parseDouble(vertexZ, "Vertex Z");
            verticesModel.fireTableRowsUpdated(row, row);
            viewport.repaint();
            updateMeshStatus();
            sceneChanged();
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void removeVertex() {
        int row = verticesTable.getSelectedRow();
        if (row < 0 || row >= project.vertices.size()) {
            showError("Select a vertex first.");
            return;
        }
        project.vertices.remove(row);
        project.triangles.removeIf(triangle -> containsVertex(triangle, row));
        for (AssetStudioProject.Triangle triangle : project.triangles) {
            for (int i = 0; i < triangle.vertexIndices.length; i++)
                if (triangle.vertexIndices[i] > row) triangle.vertexIndices[i]--;
        }
        refreshMeshTables();
        updateMeshStatus();
        sceneChanged();
    }

    private static boolean containsVertex(AssetStudioProject.Triangle triangle, int vertex) {
        for (int index : triangle.vertexIndices) if (index == vertex) return true;
        return false;
    }

    private void addTriangle() {
        try {
            int a = parseInt(triangleA, "Triangle A");
            int b = parseInt(triangleB, "Triangle B");
            int c = parseInt(triangleC, "Triangle C");
            if (a < 0 || b < 0 || c < 0 || a >= project.vertices.size()
                    || b >= project.vertices.size() || c >= project.vertices.size())
                throw new IllegalArgumentException("Triangle indices must reference existing vertices.");
            project.triangles.add(newTriangle(a, b, c));
            refreshMeshTables();
            trianglesTable.setRowSelectionInterval(project.triangles.size() - 1, project.triangles.size() - 1);
            updateMeshStatus();
            sceneChanged();
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void removeTriangle() {
        int row = trianglesTable.getSelectedRow();
        if (row < 0 || row >= project.triangles.size()) {
            showError("Select a triangle first.");
            return;
        }
        project.triangles.remove(row);
        refreshMeshTables();
        updateMeshStatus();
        sceneChanged();
    }

    private static AssetStudioProject.Triangle newTriangle(int a, int b, int c) {
        AssetStudioProject.Triangle triangle = new AssetStudioProject.Triangle();
        triangle.vertexIndices = new int[] {a, b, c};
        triangle.u = new int[] {0, 255, 255};
        triangle.v = new int[] {0, 0, 255};
        return triangle;
    }

    private double parseDouble(JTextField field, String label) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a finite number.");
        }
    }

    private int parseInt(JTextField field, String label) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be an integer.");
        }
    }

    private void updateMeshStatus() {
        status.setForeground(AssistantTheme.MUTED);
        status.setText(project.vertices.size() + " vertices, " + project.triangles.size() + " triangles");
    }

    private void importTexture() {
        JFileChooser chooser = new DarkFileChooser(new File(System.getProperty("user.home")));
        chooser.setDialogTitle("Import texture");
        chooser.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            BufferedImage image = ImageIO.read(chooser.getSelectedFile());
            project.setTexture(image, chooser.getSelectedFile().getName());
            textureEditor.setProject(project);
            updateTexturePreview();
            log("Imported texture: " + chooser.getSelectedFile().getName()
                    + " (embedded in the .maasset project)");
        } catch (IOException exception) {
            showError("Could not import texture: " + exception.getMessage());
        }
    }

    private void updateTexturePreview() {
        try {
            BufferedImage image = project.loadTexture();
            if (image == null) {
                texturePreview.setIcon(null);
                texturePreview.setText("Default generated PCX texture");
                return;
            }
            int width = Math.max(1, texturePreview.getWidth() - 12);
            int height = Math.max(1, texturePreview.getHeight() - 12);
            int scaledWidth = Math.min(width, image.getWidth());
            int scaledHeight = Math.min(height, image.getHeight());
            double scale = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
            scaledWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            scaledHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            texturePreview.setIcon(new javax.swing.ImageIcon(
                    image.getScaledInstance(scaledWidth, scaledHeight, java.awt.Image.SCALE_SMOOTH)));
            texturePreview.setText(project.textureOriginalFileName + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        } catch (IOException exception) {
            texturePreview.setIcon(null);
            texturePreview.setText("Invalid embedded texture");
        }
    }

    private void syncProject() {
        project.packageName = packageName.getText().trim();
        project.actorClassName = actorClassName.getText().trim();
        project.meshName = meshName.getText().trim();
        project.textureName = textureName.getText().trim();
        project.unrealTournamentRoot = utRoot.getText().trim();
        project.uccPath = uccPath.getText().trim();
        ASSET_STUDIO_PREFS.put("ut99-root", project.unrealTournamentRoot);
        ASSET_STUDIO_PREFS.put("ucc-path", project.uccPath);
    }

    private void saveProject(boolean chooseFile) {
        syncProject();
        Path target = currentFile;
        if (chooseFile || target == null) {
            JFileChooser chooser = new DarkFileChooser(new File(System.getProperty("user.home")));
            chooser.setDialogTitle("Save Asset Studio project");
            chooser.setFileFilter(new FileNameExtensionFilter("Asset Studio projects (*.maasset)", "maasset"));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            target = chooser.getSelectedFile().toPath();
            if (!target.getFileName().toString().toLowerCase().endsWith(".maasset"))
                target = target.resolveSibling(target.getFileName() + ".maasset");
        }
        try {
            project.save(target);
            currentFile = target;
            log("Saved project: " + target);
            status.setText("Saved " + target.getFileName());
        } catch (IOException exception) {
            showError("Could not save project: " + exception.getMessage());
        }
    }

    private void openProject() {
        JFileChooser chooser = new DarkFileChooser(new File(System.getProperty("user.home")));
        chooser.setDialogTitle("Open Asset Studio project");
        chooser.setFileFilter(new FileNameExtensionFilter("Asset Studio projects (*.maasset)", "maasset"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            project = AssetStudioProject.load(chooser.getSelectedFile().toPath());
            currentFile = chooser.getSelectedFile().toPath();
            loadFields();
            resetHistory();
            log("Opened project: " + currentFile);
        } catch (IOException exception) {
            showError("Could not open project: " + exception.getMessage());
        }
    }

    private void exportSources() {
        syncProject();
        if (!validateProject()) return;
        try {
            Ut99AssetExporter.ExportResult result = Ut99AssetExporter.export(project, Path.of(project.unrealTournamentRoot));
            log("Sources created in " + result.packageRoot());
            log("Data: " + result.dataFile().getFileName() + " | Animation: " + result.animationFile().getFileName());
            log("UCC note: the package may need EditPackages=" + project.packageName + " in UnrealTournament.ini.");
            status.setForeground(new Color(94, 205, 130));
            status.setText("UT99 source package ready");
        } catch (Exception exception) {
            showError("Could not create UT99 sources: " + exception.getMessage());
        }
    }

    private void compileWithUcc() {
        syncProject();
        if (!validateProject()) return;
        if (project.uccPath == null || project.uccPath.isBlank()) {
            showError("Choose ucc.exe first.");
            return;
        }
        Path root = Path.of(project.unrealTournamentRoot);
        Path ucc = Path.of(project.uccPath);
        try {
            if (!Ut99AssetExporter.isPackageListed(root, project.packageName)) {
                int answer = DarkDialogs.confirm(this,
                        "UCC make uses EditPackages entries. Add EditPackages=" + project.packageName
                                + " to " + Ut99AssetExporter.unrealTournamentIni(root) + "?",
                        "Configure UT99 package", javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.QUESTION_MESSAGE);
                if (answer != javax.swing.JOptionPane.YES_OPTION) {
                    showError("The package was not added to EditPackages, so UCC make was not started.");
                    return;
                }
                Ut99AssetExporter.addPackageToEditPackages(root, project.packageName);
                log("Added EditPackages=" + project.packageName + " to UnrealTournament.ini.");
            }
        } catch (IOException exception) {
            showError("Could not update UnrealTournament.ini: " + exception.getMessage());
            return;
        }
        status.setForeground(AssistantTheme.ACCENT);
        status.setText("Exporting and compiling...");
        new SwingWorker<Ut99AssetExporter.CompileResult, Void>() {
            @Override protected Ut99AssetExporter.CompileResult doInBackground()
                    throws Exception {
                return Ut99AssetExporter.compile(project, root, ucc);
            }

            @Override protected void done() {
                try {
                    Ut99AssetExporter.CompileResult result = get();
                    log(result.output());
                    if (result.success()) {
                        status.setForeground(new Color(94, 205, 130));
                        status.setText("Verified package: " + result.packageFile());
                        log("UCC succeeded and the .u file exists. Load it in UED from the package browser.");
                    } else {
                        showError("UCC did not produce a verified .u package. Exit code: " + result.exitCode()
                                + ". See the log and AssetStudio-build-notes.txt.");
                    }
                } catch (Exception exception) {
                    showError("Could not run UCC: " + exception.getMessage());
                }
            }
        }.execute();
    }

    private boolean validateProject() {
        java.util.List<String> errors = project.validate();
        if (project.unrealTournamentRoot == null || project.unrealTournamentRoot.isBlank())
            errors.add("Choose the UT99 installation root first.");
        if (!errors.isEmpty()) {
            showError(String.join(System.lineSeparator(), errors));
            return false;
        }
        return true;
    }

    private void chooseDirectory(JTextField target, String title) {
        JFileChooser chooser = new DarkFileChooser(new File(System.getProperty("user.home")));
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String root = chooser.getSelectedFile().getAbsolutePath();
            target.setText(root);
            if (target == utRoot && (uccPath.getText().isBlank()
                    || uccPath.getText().equals(ASSET_STUDIO_PREFS.get("ucc-path", ""))))
                uccPath.setText(Path.of(root, "System", "ucc.exe").toString());
            ASSET_STUDIO_PREFS.put("ut99-root", root);
            ASSET_STUDIO_PREFS.put("ucc-path", uccPath.getText().trim());
        }
    }

    private void chooseFile(JTextField target, String title) {
        JFileChooser chooser = new DarkFileChooser(new File(System.getProperty("user.home")));
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter("UCC executable (ucc.exe)", "exe"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            target.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void log(String message) {
        if (message == null || message.isBlank()) return;
        log.append(message.endsWith("\n") ? message : message + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void showError(String message) {
        status.setForeground(new Color(225, 105, 105));
        status.setText("Export failed");
        log(message);
        DarkDialogs.message(this, message, "Asset Studio", javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    private final class VertexTableModel extends AbstractTableModel {
        private final String[] columns = {"#", "X", "Y", "Z"};

        @Override public int getRowCount() { return project.vertices.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            AssetStudioProject.Vertex vertex = project.vertices.get(row);
            return switch (column) {
                case 0 -> row;
                case 1 -> vertex.x;
                case 2 -> vertex.y;
                case 3 -> vertex.z;
                default -> "";
            };
        }
    }

    private final class TriangleTableModel extends AbstractTableModel {
        private final String[] columns = {"#", "A", "B", "C"};

        @Override public int getRowCount() { return project.triangles.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            AssetStudioProject.Triangle triangle = project.triangles.get(row);
            return switch (column) {
                case 0 -> row;
                case 1 -> triangle.vertexIndices[0];
                case 2 -> triangle.vertexIndices[1];
                case 3 -> triangle.vertexIndices[2];
                default -> "";
            };
        }
    }
}

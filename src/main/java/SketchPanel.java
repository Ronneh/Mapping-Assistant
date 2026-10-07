import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SpinnerListModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Two-dimensional sketch editor with a lightweight 3D layout preview. */
public final class SketchPanel extends JPanel {
    private static final Integer[] GRID_VALUES = { 1, 2, 4, 8, 16, 32, 64, 128, 256 };
    private final SketchDocument document = new SketchDocument();
    private final Set<Long> selection = new LinkedHashSet<>();
    private final Deque<SketchDocument> undo = new ArrayDeque<>();
    private final Deque<SketchDocument> redo = new ArrayDeque<>();
    private final EnumMap<SketchView, JButton> viewButtons = new EnumMap<>(SketchView.class);
    private final EnumMap<SketchTool, JButton> toolButtons = new EnumMap<>(SketchTool.class);
    private final JSpinner gridSpinner = new JSpinner(new SpinnerListModel(GRID_VALUES));
    private final JSpinner depthSpinner = new JSpinner(new SpinnerNumberModel(128, 16, 2048, 16));
    private final JLabel status = new JLabel("No objects");
    private final SketchViewport2D viewport2D;
    private final SketchViewport3D viewport3D;
    private File currentFile;

    public SketchPanel() {
        super(new BorderLayout(10, 10));
        setBackground(AssistantTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(12, 16, 14, 16));
        viewport2D = new SketchViewport2D(document, selection, this::captureBeforeEdit, this::changed);
        viewport3D = new SketchViewport3D(document, selection, this::changed);
        add(createHeader(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewport2D, viewport3D);
        split.setResizeWeight(0.52);
        AssistantTheme.styleSplitPane(split);
        JPanel sketchArea = new JPanel(new BorderLayout(0, 7));
        sketchArea.setOpaque(false);
        sketchArea.add(createSketchControls(), BorderLayout.NORTH);
        sketchArea.add(split, BorderLayout.CENTER);
        sketchArea.add(createSketchFooter(), BorderLayout.SOUTH);
        add(sketchArea, BorderLayout.CENTER);
        installInputSupport();
        gridSpinner.addChangeListener(event -> {
            document.grid = (Integer) gridSpinner.getValue();
            document.defaultDepth = Math.max(document.grid, document.defaultDepth);
            depthSpinner.setValue(document.defaultDepth);
            changed();
        });
        depthSpinner.addChangeListener(event -> {
            document.defaultDepth = (Integer) depthSpinner.getValue();
            changed();
        });
        gridSpinner.setValue(32);
        selectView(SketchView.TOP);
        selectTool(SketchTool.SELECT);
        updateStatus();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Sketch");
        AssistantTheme.stylePageTitle(title);
        header.add(title, BorderLayout.WEST);
        JLabel description = new JLabel("Sketch your map ideas and see them in 3D.");
        description.setForeground(AssistantTheme.MUTED);
        header.add(description, BorderLayout.CENTER);
        JPanel actions = actions(FlowLayout.RIGHT);
        actions.add(button("New", event -> newDocument()));
        actions.add(button("Open", event -> open()));
        actions.add(button("Save", event -> save(false)));
        actions.add(button("Save as...", event -> save(true)));
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel createLegacySketchControls() {
        JPanel controls = new JPanel(new BorderLayout(6, 4));
        controls.setBackground(AssistantTheme.PANEL);
        controls.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AssistantTheme.BORDER),
                BorderFactory.createEmptyBorder(5, 6, 5, 6)));

        JPanel drawing = actions(FlowLayout.LEFT);
        drawing.add(viewButton("T", SketchView.TOP, "Top view (X / Y)"));
        drawing.add(viewButton("F", SketchView.FRONT, "Front view (X / Z)"));
        drawing.add(viewButton("S", SketchView.SIDE, "Side view (Y / Z)"));
        drawing.add(separator());
        drawing.add(toolButton("↕↔", SketchTool.SELECT, "Select and move"));
        drawing.add(toolButton("╱", SketchTool.LINE, "Draw line"));
        drawing.add(toolButton("□", SketchTool.RECTANGLE, "Draw rectangle"));
        drawing.add(separator());
        drawing.add(new JLabel("Grid:"));
        gridSpinner.setPreferredSize(new Dimension(72, 27));
        drawing.add(gridSpinner);
        drawing.add(new JLabel("Depth:"));
        depthSpinner.setPreferredSize(new Dimension(78, 27));
        drawing.add(depthSpinner);
        controls.add(drawing, BorderLayout.NORTH);

        JPanel editing = actions(FlowLayout.LEFT);
        editing.add(button("Fit all", event -> fitAll()));
        editing.add(button("Undo", event -> undo()));
        editing.add(button("Redo", event -> redo()));
        editing.add(button("Delete", event -> deleteSelection()));
        editing.add(button("Copy T3D", event -> copyT3d()));
        editing.add(button("Export T3D", event -> exportT3d()));
        status.setForeground(AssistantTheme.MUTED);
        status.setHorizontalAlignment(JLabel.LEFT);
        editing.add(status);
        controls.add(editing, BorderLayout.SOUTH);
        return controls;
    }

    private JPanel createSketchControls() {
        JPanel controls = actions(FlowLayout.LEFT);
        controls.setOpaque(true);
        controls.setBackground(AssistantTheme.PANEL);
        controls.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AssistantTheme.BORDER),
                BorderFactory.createEmptyBorder(5, 6, 5, 6)));
        controls.add(viewButton("T", SketchView.TOP, "Top view (X / Y)"));
        controls.add(viewButton("F", SketchView.FRONT, "Front view (X / Z)"));
        controls.add(viewButton("S", SketchView.SIDE, "Side view (Y / Z)"));
        controls.add(separator());
        controls.add(iconToolButton(SketchTool.SELECT, "Select and move"));
        controls.add(iconToolButton(SketchTool.LINE, "Draw line"));
        controls.add(iconToolButton(SketchTool.RECTANGLE, "Draw rectangle"));
        controls.add(separator());
        controls.add(new JLabel("Grid:"));
        gridSpinner.setPreferredSize(new Dimension(72, 27));
        controls.add(gridSpinner);
        controls.add(new JLabel("Depth:"));
        depthSpinner.setPreferredSize(new Dimension(78, 27));
        controls.add(depthSpinner);
        controls.add(button("Fit all", event -> fitAll()));
        controls.add(button("Undo", event -> undo()));
        controls.add(button("Redo", event -> redo()));
        controls.add(button("Delete", event -> deleteSelection()));
        return controls;
    }

    private JPanel createSketchFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        JPanel actions = actions(FlowLayout.LEFT);
        actions.add(button("Copy T3D", event -> copyT3d()));
        actions.add(button("Export T3D", event -> exportT3d()));
        footer.add(actions, BorderLayout.WEST);
        status.setForeground(AssistantTheme.MUTED);
        status.setHorizontalAlignment(JLabel.RIGHT);
        footer.add(status, BorderLayout.CENTER);
        return footer;
    }

    private JButton iconToolButton(SketchTool tool, String tooltip) {
        JButton button = button("", event -> selectTool(tool));
        button.setToolTipText(tooltip);
        button.setIcon(toolIcon(tool));
        button.setMinimumSize(new Dimension(58, 29));
        button.setPreferredSize(new Dimension(58, 29));
        button.setMaximumSize(new Dimension(58, 29));
        return button;
    }

    private javax.swing.Icon toolIcon(SketchTool tool) {
        return new javax.swing.Icon() {
            @Override public int getIconWidth() { return 26; }
            @Override public int getIconHeight() { return 22; }

            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setColor(component.isEnabled() ? AssistantTheme.TEXT : AssistantTheme.MUTED);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int left = x + 2, top = y + 1, right = x + 24, bottom = y + 20;
                if (tool == SketchTool.SELECT) {
                    g.drawLine(13 + x, top, 13 + x, bottom);
                    g.drawLine(left, 11 + y, right, 11 + y);
                    g.drawLine(13 + x, top, 9 + x, 5 + y);
                    g.drawLine(13 + x, top, 17 + x, 5 + y);
                    g.drawLine(13 + x, bottom, 9 + x, 17 + y);
                    g.drawLine(13 + x, bottom, 17 + x, 17 + y);
                    g.drawLine(left, 11 + y, 6 + x, 8 + y);
                    g.drawLine(left, 11 + y, 6 + x, 14 + y);
                    g.drawLine(right, 11 + y, 21 + x, 8 + y);
                    g.drawLine(right, 11 + y, 21 + x, 14 + y);
                } else if (tool == SketchTool.LINE) {
                    g.drawLine(left + 1, bottom - 1, right - 1, top + 1);
                } else {
                    g.drawRect(left + 2, top + 2, right - left - 4, bottom - top - 4);
                }
                g.dispose();
            }
        };
    }

    private JButton viewButton(String text, SketchView view, String tooltip) {
        JButton button = button(text, event -> selectView(view));
        button.setToolTipText(tooltip);
        button.setMinimumSize(new Dimension(46, 29));
        button.setPreferredSize(new Dimension(46, 29));
        button.setMaximumSize(new Dimension(46, 29));
        viewButtons.put(view, button);
        return button;
    }

    private JButton toolButton(String text, SketchTool tool, String tooltip) {
        String visible = switch (tool) {
            case SELECT -> "\u2195\u2194";
            case LINE -> "\u2571";
            case RECTANGLE -> "\u25A1";
        };
        JButton button = button(visible, event -> selectTool(tool));
        button.setToolTipText(tooltip);
        button.setMinimumSize(new Dimension(58, 29));
        button.setPreferredSize(new Dimension(58, 29));
        button.setMaximumSize(new Dimension(58, 29));
        button.setFont(button.getFont().deriveFont(17f));
        toolButtons.put(tool, button);
        return button;
    }

    private javax.swing.JSeparator separator() {
        javax.swing.JSeparator separator = new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 24));
        return separator;
    }

    private void selectView(SketchView view) {
        viewport2D.setView(view);
        viewButtons.forEach((candidate, button) -> button.setBackground(
                candidate == view ? AssistantTheme.ACCENT_DARK : AssistantTheme.PANEL_ALT));
    }

    private void selectTool(SketchTool tool) {
        viewport2D.setTool(tool);
        toolButtons.forEach((candidate, button) -> button.setBackground(
                candidate == tool ? AssistantTheme.ACCENT_DARK : AssistantTheme.PANEL_ALT));
    }

    private JPanel actions(int alignment) {
        JPanel panel = new JPanel(new FlowLayout(alignment, 6, 0));
        panel.setOpaque(false);
        return panel;
    }

    private JButton button(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(listener);
        return button;
    }

    private void installInputSupport() {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Z"), "undoSketch");
        getActionMap().put("undoSketch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { undo(); }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Y"), "redoSketch");
        getActionMap().put("redoSketch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { redo(); }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("DELETE"), "deleteSketch");
        getActionMap().put("deleteSketch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { deleteSelection(); }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control S"), "saveSketch");
        getActionMap().put("saveSketch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { save(false); }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control O"), "openSketch");
        getActionMap().put("openSketch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { open(); }
        });
    }

    private void captureBeforeEdit() {
        undo.push(document.copy());
        while (undo.size() > 80) undo.removeLast();
        redo.clear();
    }

    private void changed() {
        viewport2D.repaint();
        viewport3D.repaint();
        updateStatus();
    }

    private void updateStatus() {
        String file = currentFile == null ? "unsaved" : currentFile.getName();
        status.setText(document.shapes.size() + " objects  |  " + selection.size()
                + " selected  |  " + file);
    }

    private void fitAll() {
        viewport2D.fitAll();
        viewport3D.fitAll();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(document.copy());
        document.copyFrom(undo.pop());
        selection.clear();
        syncControls();
        changed();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(document.copy());
        document.copyFrom(redo.pop());
        selection.clear();
        syncControls();
        changed();
    }

    private void deleteSelection() {
        if (selection.isEmpty()) return;
        captureBeforeEdit();
        document.removeIds(selection);
        selection.clear();
        changed();
    }

    private void newDocument() {
        if (!document.shapes.isEmpty() && DarkDialogs.confirm(this,
                "Clear the current Sketch? Unsaved work will be lost.", "New Sketch",
                javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE)
                != javax.swing.JOptionPane.YES_OPTION) return;
        captureBeforeEdit();
        document.shapes.clear();
        document.nextId = 1;
        selection.clear();
        currentFile = null;
        changed();
        fitAll();
    }

    private void save(boolean chooseFile) {
        File file = chooseFile || currentFile == null ? chooseFile("Save Sketch", true) : currentFile;
        if (file == null) return;
        if (!FileSaveSupport.confirmOverwrite(this, file)) return;
        try {
            document.save(file.toPath());
            currentFile = file;
            status.setForeground(new Color(94, 205, 130));
            status.setText("Saved " + file.getName());
        } catch (IOException exception) {
            showError("Could not save Sketch: " + exception.getMessage());
        }
    }

    private void open() {
        File file = chooseFile("Open Sketch", false);
        if (file == null) return;
        try {
            SketchDocument loaded = SketchDocument.load(file.toPath());
            captureBeforeEdit();
            document.copyFrom(loaded);
            currentFile = file;
            selection.clear();
            syncControls();
            changed();
            fitAll();
        } catch (IOException exception) {
            showError("Could not open Sketch: " + exception.getMessage());
        }
    }

    private File chooseFile(String title, boolean save) {
        JFileChooser chooser = new JFileChooser(FileSaveSupport.preferredDirectory(
                null, new File(System.getProperty("user.home"))));
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter("Sketch files (*.sketch.json)", "json"));
        int result = save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return null;
        File selected = chooser.getSelectedFile();
        if (save && !selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".sketch.json"))
            selected = new File(selected.getParentFile(), selected.getName() + ".sketch.json");
        return selected;
    }

    private void copyT3d() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(SketchT3dExporter.export(document)), null);
        status.setText("T3D for solid boxes copied to clipboard.");
    }

    private void exportT3d() {
        JFileChooser chooser = new JFileChooser(FileSaveSupport.preferredDirectory(
                null, new File(System.getProperty("user.home"))));
        chooser.setDialogTitle("Export T3D");
        chooser.setFileFilter(new FileNameExtensionFilter("Unreal T3D files (*.t3d)", "t3d"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".t3d"))
            file = new File(file.getParentFile(), file.getName() + ".t3d");
        if (!FileSaveSupport.confirmOverwrite(this, file)) return;
        try {
            java.nio.file.Files.writeString(file.toPath(), SketchT3dExporter.export(document));
            status.setText("Exported " + file.getName());
        } catch (IOException exception) {
            showError("Could not export T3D: " + exception.getMessage());
        }
    }

    private void syncControls() {
        document.grid = nearestGrid(document.grid);
        gridSpinner.setValue(document.grid);
        depthSpinner.setValue(document.defaultDepth);
    }

    private static int nearestGrid(int value) {
        int nearest = GRID_VALUES[0];
        for (int candidate : GRID_VALUES) {
            if (Math.abs(candidate - value) < Math.abs(nearest - value)) nearest = candidate;
        }
        return nearest;
    }

    private void showError(String message) {
        status.setForeground(new Color(225, 105, 105));
        status.setText(message);
        DarkDialogs.message(this, message, "Sketch", javax.swing.JOptionPane.ERROR_MESSAGE);
    }
}

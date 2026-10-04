import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Clipboard;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Finds brush vertices that are off a chosen Unreal grid and, after explicit
 * confirmation, snaps only those coordinates to their nearest grid point.
 */
public final class BrushOptimizer {

    private enum SnapMode {
        NEAREST("Nearest"),
        FLOOR("Floor"),
        CEILING("Ceiling");

        private final String label;

        SnapMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final double GRID_EPSILON = 1.0e-9;
    /** Unreal commonly serializes exact grid values with a tiny floating-point error. */
    private static final double GRID_NOISE_TOLERANCE = 0.01;
    private static final double MIDPOINT_TOLERANCE = 0.25;
    private static final double GEOMETRY_EPSILON = 1.0e-7;
    private static final double GEOMETRY_EPSILON_SQUARED = GEOMETRY_EPSILON * GEOMETRY_EPSILON;
    private static final double PLANARITY_TOLERANCE = 0.1;
    private static final int MAX_CURVE_GRID_MOVES = 4;
    private static final Pattern BEGIN_ACTOR = Pattern.compile(
            "^\\s*Begin\\s+Actor\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRUSH_CLASS = Pattern.compile(
            "\\bClass\\s*=\\s*Brush\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTOR_NAME = Pattern.compile(
            "\\bName\\s*=\\s*(?:\"([^\"]+)\"|(\\S+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_ACTOR = Pattern.compile("^\\s*End\\s+Actor\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERTEX_LINE = Pattern.compile("^\\s*Vertex\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private final JTextArea inputArea = createCodeArea();
    private final JTextArea outputArea = createCodeArea();
    private final JComboBox<Integer> gridStepBox =
            new JComboBox<>(new Integer[] { 1, 2, 4, 8, 16, 32, 64, 128, 256 });
    private final JComboBox<SnapMode> snapModeBox = new JComboBox<>(SnapMode.values());
    private final JComboBox<Integer> minMoveBox =
            new JComboBox<>(new Integer[] { 0, 1, 2, 4, 8, 16, 32, 64, 128, 256 });
    private final JComboBox<Integer> maxMoveBox =
            new JComboBox<>(new Integer[] { 2, 4, 8, 16, 32, 64, 128, 256 });
    private final JCheckBox cleanNoiseBox = new JCheckBox("Clean noise", true);
    private final JComboBox<Double> noiseToleranceBox =
            new JComboBox<>(new Double[] { 0.001, 0.01, 0.05, 0.1 });
    private final JComboBox<Integer> fontSizeBox =
            new JComboBox<>(new Integer[] { 8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 24, 28, 32, 36, 40 });
    private final JCheckBox preserveCurveLines = new JCheckBox("Protect curved geometry", false);
    private final JLabel statusLabel = new JLabel(" ");
    private final JTextPane logPane = new NoWrapTextPane();
    private final BrushPreviewPanel preview = new BrushPreviewPanel();
    private final DefaultListModel<BrushSelection> brushSelectionModel = new DefaultListModel<>();
    private final JList<BrushSelection> brushSelectionList = new JList<>(brushSelectionModel);
    private final Deque<String> undoHistory = new ArrayDeque<>();
    private final Deque<String> redoHistory = new ArrayDeque<>();
    private final JButton undoButton = button("Undo", this::undoOutput);
    private final JButton redoButton = button("Redo", this::redoOutput);

    private String analyzedMap = "";
    private List<BrushIssue> issues = List.of();
    private boolean optimizationRunning;
    private int optimizationGeneration;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AssistantTheme.install();
            new BrushOptimizer().show();
        });
    }

    private void show() {
        JFrame frame = new JFrame("UT99 Brush Optimizer by VRN|Ron");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(createContent());
        frame.setMinimumSize(new Dimension(1000, 700));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    /**
     * Creates the optimizer view so it can be hosted by Mapping Assistant.
     * The optimizer deliberately owns no window when used through this method.
     */
    public JPanel createContent() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(AssistantTheme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.setBackground(AssistantTheme.BACKGROUND);
        preserveCurveLines.setBackground(AssistantTheme.BACKGROUND);
        preserveCurveLines.setToolTipText(
                "Preserve circular and midpoint-based geometry; only floating-point noise is cleaned.");
        controls.add(preserveCurveLines);
        controls.add(new JLabel("Grid step:"));
        gridStepBox.setSelectedItem(2);
        controls.add(gridStepBox);
        controls.add(new JLabel("Snap:"));
        snapModeBox.setSelectedItem(SnapMode.NEAREST);
        snapModeBox.setToolTipText("Choose how off-grid coordinates are moved to the selected grid.");
        controls.add(snapModeBox);
        controls.add(new JLabel("Min. move:"));
        minMoveBox.setSelectedItem(32);
        minMoveBox.setToolTipText("Ignore snap adjustments smaller than this value; zero applies every non-noise correction.");
        minMoveBox.addActionListener(event -> {
            if ((Integer) maxMoveBox.getSelectedItem() < (Integer) minMoveBox.getSelectedItem()) {
                maxMoveBox.setSelectedItem(minMoveBox.getSelectedItem());
            }
        });
        controls.add(minMoveBox);
        controls.add(new JLabel("Max move:"));
        maxMoveBox.setSelectedItem(256);
        maxMoveBox.addActionListener(event -> {
            if ((Integer) minMoveBox.getSelectedItem() > (Integer) maxMoveBox.getSelectedItem()) {
                minMoveBox.setSelectedItem(maxMoveBox.getSelectedItem());
            }
        });
        controls.add(maxMoveBox);
        cleanNoiseBox.setBackground(AssistantTheme.BACKGROUND);
        cleanNoiseBox.setToolTipText("Apply only floating-point cleanup within the selected tolerance, regardless of Min. move.");
        controls.add(cleanNoiseBox);
        controls.add(new JLabel("Noise <="));
        noiseToleranceBox.setSelectedItem(0.01);
        noiseToleranceBox.setToolTipText("Maximum coordinate adjustment treated as floating-point serialization noise.");
        controls.add(noiseToleranceBox);
        preserveCurveLines.addActionListener(event -> updateCurveMode());
        updateCurveMode();
        controls.add(new JLabel("Font size:"));
        fontSizeBox.setSelectedItem(12);
        fontSizeBox.addActionListener(event -> setCodeFontSize((Integer) fontSizeBox.getSelectedItem()));
        controls.add(fontSizeBox);
        statusLabel.setForeground(new Color(0, 128, 0));
        controls.add(statusLabel);
        JPanel header = new JPanel(new BorderLayout(0, 5));
        header.setBackground(AssistantTheme.BACKGROUND);
        JLabel heading = new JLabel("Brush Optimizer");
        AssistantTheme.stylePageTitle(heading);
        header.add(heading, BorderLayout.NORTH);
        header.add(controls, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        inputArea.setToolTipText("Paste your map code here.");
        outputArea.setEditable(false);
        TextSearchSupport.install(inputArea, root, "Input Code");
        TextSearchSupport.install(outputArea, root, "Optimized Result");

        JSplitPane codeSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                titledScroll("Input Code:", inputArea), titledScroll("Optimized result:", outputArea));
        codeSplit.setResizeWeight(0.5);
        AssistantTheme.styleSplitPane(codeSplit);
        SplitPaneState.install(codeSplit, BrushOptimizer.class, "code");

        logPane.setEditable(false);
        logPane.setFont(new Font("Verdana", Font.PLAIN, 12));
        logPane.setBackground(AssistantTheme.CODE_BACKGROUND);
        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        logScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        logScroll.setBorder(AssistantTheme.titled("Log"));
        logScroll.setPreferredSize(new Dimension(950, 150));

        JPanel lowerWorkspace = new JPanel(new BorderLayout(7, 0));
        lowerWorkspace.setOpaque(false);
        lowerWorkspace.add(logScroll, BorderLayout.CENTER);
        lowerWorkspace.add(preview, BorderLayout.WEST);
        lowerWorkspace.add(createBrushSelectionPanel(), BorderLayout.EAST);

        JPanel lowerSection = new JPanel(new BorderLayout(0, 5));
        lowerSection.setBackground(AssistantTheme.BACKGROUND);
        JPanel actions = new JPanel(new EdgeAlignedFlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setBackground(AssistantTheme.BACKGROUND);
        actions.setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 0));
        actions.add(button("Paste", this::pasteInput));
        actions.add(outlinedButton("Analyze", new Color(224, 132, 40), this::analyzeOnly));
        actions.add(outlinedButton("Optimize", new Color(45, 170, 85), this::optimizeAllBrushes));
        actions.add(button("Copy result", this::copyOutput));
        actions.add(undoButton);
        actions.add(redoButton);
        actions.add(button("Reset", this::reset));
        updateUndoRedoButtons();
        lowerSection.add(actions, BorderLayout.NORTH);
        lowerSection.add(lowerWorkspace, BorderLayout.CENTER);

        JSplitPane workspaceSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeSplit, lowerSection);
        workspaceSplit.setResizeWeight(0.78);
        AssistantTheme.styleSplitPane(workspaceSplit);
        SplitPaneState.install(workspaceSplit, BrushOptimizer.class, "workspace");
        root.add(workspaceSplit, BorderLayout.CENTER);
        return root;
    }

    private JPanel createBrushSelectionPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBackground(AssistantTheme.BACKGROUND);
        panel.setPreferredSize(new Dimension(245, 150));
        panel.setBorder(AssistantTheme.titled("Brush selection"));

        brushSelectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        brushSelectionList.setVisibleRowCount(6);
        brushSelectionList.setCellRenderer((list, value, index, selected, focus) -> {
            JCheckBox checkBox = new JCheckBox(value.label(), value.selected);
            checkBox.setOpaque(true);
            checkBox.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            checkBox.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return checkBox;
        });
        brushSelectionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                int index = brushSelectionList.locationToIndex(event.getPoint());
                if (index < 0 || !brushSelectionList.getCellBounds(index, index).contains(event.getPoint())) return;
                BrushSelection selection = brushSelectionModel.getElementAt(index);
                selection.selected = !selection.selected;
                brushSelectionList.repaint(brushSelectionList.getCellBounds(index, index));
            }
        });
        panel.add(new JScrollPane(brushSelectionList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        buttons.setOpaque(false);
        buttons.add(button("All", ignored -> setAllBrushesSelected(true)));
        buttons.add(button("None", ignored -> setAllBrushesSelected(false)));
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void setAllBrushesSelected(boolean selected) {
        for (int index = 0; index < brushSelectionModel.size(); index++) {
            brushSelectionModel.getElementAt(index).selected = selected;
        }
        brushSelectionList.repaint();
    }

    private void updateBrushSelectionList(List<BrushIssue> found, boolean[] selected) {
        brushSelectionModel.clear();
        for (int index = 0; index < found.size(); index++) {
            BrushIssue issue = found.get(index);
            boolean isSelected = selected == null || index >= selected.length || selected[index];
            brushSelectionModel.addElement(new BrushSelection(issue, isSelected && !issue.incomplete));
        }
        brushSelectionList.repaint();
    }

    private boolean[] selectedBrushesFor(String input) {
        if (!input.equals(analyzedMap) || brushSelectionModel.size() != issues.size()) return null;
        boolean[] selected = new boolean[brushSelectionModel.size()];
        for (int index = 0; index < selected.length; index++) {
            selected[index] = brushSelectionModel.getElementAt(index).selected;
        }
        return selected;
    }

    private JButton button(String label, java.util.function.Consumer<ActionEvent> action) {
        JButton button = new JButton(label);
        button.addActionListener(action::accept);
        return button;
    }

    private JButton outlinedButton(String label, Color color,
                                   java.util.function.Consumer<ActionEvent> action) {
        JButton button = button(label, action);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color, 2),
                BorderFactory.createEmptyBorder(3, 9, 3, 9)));
        return button;
    }

    private JScrollPane titledScroll(String title, JTextArea area) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(AssistantTheme.titled(title));
        return scroll;
    }

    private void setCodeFontSize(int size) {
        inputArea.setFont(inputArea.getFont().deriveFont((float) size));
        outputArea.setFont(outputArea.getFont().deriveFont((float) size));
    }

    private JTextArea createCodeArea() {
        JTextArea area = new JTextArea();
        area.setFont(new Font("Verdana", Font.PLAIN, 12));
        area.setLineWrap(false);
        area.setBackground(AssistantTheme.CODE_BACKGROUND);
        return area;
    }

    private static final class NoWrapTextPane extends JTextPane {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }
    }

    private void updateCurveMode() {
        if (preserveCurveLines.isSelected()) {
            gridStepBox.setSelectedItem(2);
            minMoveBox.setSelectedItem(0);
        } else {
            gridStepBox.setSelectedItem(2);
            minMoveBox.setSelectedItem(32);
        }
        gridStepBox.setEnabled(!preserveCurveLines.isSelected());
        minMoveBox.setEnabled(!preserveCurveLines.isSelected());
    }

    private void analyzeOnly(ActionEvent ignored) { runOptimization(false); }

    private void optimizeAllBrushes(ActionEvent ignored) {
        runOptimization(true);
    }

    private void runOptimization(boolean writeOutput) {
        if (optimizationRunning) return;
        String input = inputArea.getText();
        int gridStep = (Integer) gridStepBox.getSelectedItem();
        SnapMode snapMode = (SnapMode) snapModeBox.getSelectedItem();
        int minMove = (Integer) minMoveBox.getSelectedItem();
        int maxMove = (Integer) maxMoveBox.getSelectedItem();
        boolean cleanNoise = cleanNoiseBox.isSelected();
        double noiseTolerance = (Double) noiseToleranceBox.getSelectedItem();
        boolean preserveCurves = preserveCurveLines.isSelected();
        boolean[] selectedBeforeRun = writeOutput ? selectedBrushesFor(input) : null;
        int generation = ++optimizationGeneration;
        optimizationRunning = true;
        statusLabel.setForeground(AssistantTheme.MUTED);
        statusLabel.setText(writeOutput ? "Optimizing..." : "Analyzing...");

        new SwingWorker<OptimizationRun, Void>() {
            @Override protected OptimizationRun doInBackground() {
                List<BrushIssue> found = findOffGridBrushes(input, gridStep, minMove, maxMove, snapMode,
                        cleanNoise, noiseTolerance);
                boolean[] optimizeActor = new boolean[found.size()];
                if (writeOutput && selectedBeforeRun != null && selectedBeforeRun.length == optimizeActor.length) {
                    System.arraycopy(selectedBeforeRun, 0, optimizeActor, 0, optimizeActor.length);
                } else {
                    java.util.Arrays.fill(optimizeActor, true);
                }
                String optimized = optimizeMap(
                        input, found, optimizeActor, gridStep, minMove, maxMove, snapMode,
                        cleanNoise, noiseTolerance, preserveCurves);
                return new OptimizationRun(input, found, optimizeActor, optimized);
            }

            @Override protected void done() {
                optimizationRunning = false;
                if (generation != optimizationGeneration) return;
                try {
                    OptimizationRun run = get();
                    analyzedMap = run.input();
                    issues = run.issues();
                    updateBrushSelectionList(issues, run.optimizeActor());
                    int changedLines = writeOptimizationLog(
                            analyzedMap, run.optimized(), !writeOutput, run.optimizeActor(), preserveCurves);
                    appendGeometryWarnings(run.optimized());
                    if (writeOutput) setOptimizedOutput(run.optimized());
                    boolean faulty = issues.stream().anyMatch(issue -> issue.hasProblems());
                    preview.showBrush(writeOutput ? run.optimized() : analyzedMap,
                            writeOutput || !faulty ? new Color(94, 205, 130) : new Color(225, 75, 75),
                            writeOutput ? "Optimized result" : faulty ? "Input: off-grid" : "Input: on-grid");
                    statusLabel.setForeground(new Color(0, 128, 0));
                    statusLabel.setText(changedLines == 0
                            ? "OK: No changes required"
                            : (writeOutput ? "OK: Updated " : "OK: Analysis found ")
                                    + changedLines + " Vertex line(s)");
                } catch (Exception exception) {
                    statusLabel.setForeground(new Color(180, 40, 40));
                    statusLabel.setText("Optimization failed: " + exception.getMessage());
                }
            }
        }.execute();
    }

    private void copyOutput(ActionEvent ignored) {
        String output = outputArea.getText();
        if (output.isEmpty()) {
            statusLabel.setForeground(new Color(180, 40, 40));
            statusLabel.setText("Nothing to copy");
            return;
        }
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(output), null);
        statusLabel.setForeground(new Color(0, 128, 0));
        statusLabel.setText("OK: Copied");
    }

    private void setOptimizedOutput(String output) {
        if (outputArea.getText().equals(output)) return;
        undoHistory.push(outputArea.getText());
        redoHistory.clear();
        outputArea.setText(output);
        updateUndoRedoButtons();
    }

    private void undoOutput(ActionEvent ignored) {
        if (undoHistory.isEmpty()) return;
        redoHistory.push(outputArea.getText());
        outputArea.setText(undoHistory.pop());
        updateUndoRedoButtons();
        statusLabel.setForeground(new Color(0, 128, 0));
        statusLabel.setText("OK: Undo");
    }

    private void redoOutput(ActionEvent ignored) {
        if (redoHistory.isEmpty()) return;
        undoHistory.push(outputArea.getText());
        outputArea.setText(redoHistory.pop());
        updateUndoRedoButtons();
        statusLabel.setForeground(new Color(0, 128, 0));
        statusLabel.setText("OK: Redo");
    }

    private void updateUndoRedoButtons() {
        undoButton.setEnabled(!undoHistory.isEmpty());
        redoButton.setEnabled(!redoHistory.isEmpty());
    }

    private void pasteInput(ActionEvent ignored) {
        try {
            inputArea.setText(ClipboardTextSupport.readText());
            inputArea.setCaretPosition(0);
            statusLabel.setForeground(new Color(0, 128, 0));
            statusLabel.setText("OK: Pasted input code");
        } catch (Exception exception) {
            statusLabel.setForeground(new Color(180, 40, 40));
            statusLabel.setText("Clipboard does not contain text");
        }
    }

    private void reset(ActionEvent ignored) {
        optimizationGeneration++;
        inputArea.setText("");
        outputArea.setText("");
        logPane.setText("");
        undoHistory.clear();
        redoHistory.clear();
        updateUndoRedoButtons();
        brushSelectionModel.clear();
        issues = List.of();
        analyzedMap = "";
        preview.showBrush("", AssistantTheme.MUTED, "Press Analyze");
        statusLabel.setText(" ");
    }

    private int writeOptimizationLog(String original, String optimized, boolean analysisOnly,
                                     boolean[] optimizeActor, boolean protectCurvedGeometry) {
        logPane.setText("");
        String[] originalLines = original.split("\\R", -1);
        String[] optimizedLines = optimized.split("\\R", -1);
        int changeCount = 0;

        if (issues.isEmpty()) {
            appendLog("No Brush actors were found in the input.\n", new Color(180, 40, 40));
            return 0;
        }

        List<BrushIssue> unchangedBrushes = new ArrayList<>();
        boolean analysisHeadingWritten = false;
        for (int issueIndex = 0; issueIndex < issues.size(); issueIndex++) {
            BrushIssue issue = issues.get(issueIndex);
            boolean selected = issueIndex >= optimizeActor.length || optimizeActor[issueIndex];
            boolean curvedGeometryProtected = protectCurvedGeometry && isCurvedBrush(originalLines, issue);
            List<Integer> changedLines = new ArrayList<>();
            for (int line = issue.startLine; line <= issue.endLine; line++) {
                if (VERTEX_LINE.matcher(originalLines[line]).find() && !originalLines[line].equals(optimizedLines[line])) {
                    changedLines.add(line);
                }
            }
            changeCount += changedLines.size();
            if (!analysisOnly && !selected) {
                appendLog("  Skipped: " + issue.name + "\n", new Color(110, 110, 110));
            } else if (issue.incomplete) {
                appendLog("  Warning: " + issue.name + " is incomplete; skipped.\n", new Color(180, 100, 0));
            } else if (issue.malformedVertices > 0) {
                appendLog("  Warning: " + issue.name + " contains " + issue.malformedVertices
                        + " malformed Vertex line(s); skipped.\n", new Color(180, 100, 0));
            } else if (changedLines.isEmpty()
                    && (issue.blockedCoordinates > 0 || issue.ignoredCoordinates > 0)) {
                appendLog(issue.name + ": no coordinates changed because the configured movement limits kept them unchanged.\n",
                        new Color(180, 100, 0));
                appendIssueDiagnostics(issue, new Color(180, 100, 0));
            } else if (changedLines.isEmpty() && curvedGeometryProtected) {
                appendLog(issue.name + ": curved geometry protected; genuine grid changes were skipped.\n",
                        new Color(180, 100, 0));
                appendIssueDiagnostics(issue, new Color(180, 100, 0));
            } else if (changedLines.isEmpty()) {
                unchangedBrushes.add(issue);
            } else {
                Color changeColor = analysisOnly ? new Color(180, 40, 40) : new Color(30, 70, 160);
                if (analysisOnly && !analysisHeadingWritten) {
                    appendLog("Brushes that can be optimized:\n", changeColor);
                    analysisHeadingWritten = true;
                }
                appendLog(issue.name + ": " + (analysisOnly ? "would update " : "updated ")
                        + changedLines.size() + " Vertex line(s).\n", changeColor);
                for (int line : changedLines) {
                    appendLog("  " + originalLines[line].trim() + "  ->  " + optimizedLines[line].trim() + "\n", changeColor);
                }
                appendIssueDiagnostics(issue, changeColor);
                if (curvedGeometryProtected) {
                    appendLog("  Curved geometry protected; only permitted cleanup changes were applied.\n",
                            new Color(180, 100, 0));
                }
            }
        }
        if (!unchangedBrushes.isEmpty()) {
            appendLog("\nAlready on-grid brushes; no changes required:\n", new Color(0, 128, 0));
            for (BrushIssue issue : unchangedBrushes) {
                appendLog("  OK: " + issue.name + "\n", new Color(0, 128, 0));
            }
        }
        return changeCount;
    }

    private void appendIssueDiagnostics(BrushIssue issue, Color color) {
        if (issue.cleanedNoiseCoordinates > 0) {
            appendLog("  Cleaned floating-point noise: " + issue.cleanedNoiseCoordinates
                    + " coordinate(s).\n", color);
        }
        if (issue.ignoredCoordinates > 0) {
            appendLog("  Kept off-grid below Min. move: " + issue.ignoredCoordinates
                    + " coordinate(s).\n", new Color(180, 100, 0));
        }
        if (issue.blockedCoordinates > 0) {
            appendLog("  Blocked above Max move: " + issue.blockedCoordinates
                    + " coordinate(s).\n", new Color(180, 100, 0));
        }
    }

    private void appendLog(String text, Color color) {
        StyledDocument document = logPane.getStyledDocument();
        javax.swing.text.SimpleAttributeSet style = new javax.swing.text.SimpleAttributeSet();
        StyleConstants.setForeground(style, color);
        try {
            document.insertString(document.getLength(), text, style);
        } catch (BadLocationException exception) {
            throw new IllegalStateException("Unable to append to the optimization log.", exception);
        }
    }

    private void appendGeometryWarnings(String code) {
        List<GeometryWarning> warnings = geometryWarnings(code);
        if (warnings.isEmpty()) return;
        appendLog("\nGeometry warnings: " + warnings.size() + "\n", new Color(180, 100, 0));
        for (GeometryWarning warning : warnings) {
            appendLog("  " + warning + "\n", new Color(180, 100, 0));
        }
    }

    private static List<GeometryWarning> geometryWarnings(String code) {
        String[] lines = code.split("\\R", -1);
        List<GeometryWarning> warnings = new ArrayList<>();
        String actorName = "unknown actor";
        List<Point3> polygon = null;
        int polygonStartLine = -1;
        boolean malformedVertex = false;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String parsedActorName = actorName(line);
            if (parsedActorName != null) actorName = parsedActorName;
            if (line.trim().equalsIgnoreCase("Begin Polygon")) {
                if (polygon != null) {
                    warnings.add(new GeometryWarning(actorName, polygonStartLine + 1,
                            "polygon was not closed before another polygon started"));
                }
                polygon = new ArrayList<>();
                polygonStartLine = index;
                malformedVertex = false;
                continue;
            }
            if (polygon == null) continue;
            if (VERTEX_LINE.matcher(line).find()) {
                Point3 point = readVertex(line);
                if (point == null) malformedVertex = true;
                else polygon.add(point);
            }
            if (line.trim().equalsIgnoreCase("End Polygon")) {
                warnings.addAll(validatePolygon(actorName, polygonStartLine + 1, polygon, malformedVertex));
                polygon = null;
                polygonStartLine = -1;
                malformedVertex = false;
            }
        }
        if (polygon != null) {
            warnings.add(new GeometryWarning(actorName, polygonStartLine + 1,
                    "polygon was not closed before the end of the input"));
            warnings.addAll(validatePolygon(actorName, polygonStartLine + 1, polygon, malformedVertex));
        }
        return warnings;
    }

    private static List<GeometryWarning> validatePolygon(String actorName, int lineNumber,
                                                         List<Point3> vertices, boolean malformedVertex) {
        List<GeometryWarning> warnings = new ArrayList<>();
        if (malformedVertex) {
            warnings.add(new GeometryWarning(actorName, lineNumber, "contains a malformed Vertex line"));
        }
        if (vertices.size() < 3) {
            warnings.add(new GeometryWarning(actorName, lineNumber,
                    "has fewer than three valid vertices"));
            return warnings;
        }

        for (int first = 0; first < vertices.size(); first++) {
            for (int second = first + 1; second < vertices.size(); second++) {
                if (distanceSquared(vertices.get(first), vertices.get(second)) <= GEOMETRY_EPSILON_SQUARED) {
                    warnings.add(new GeometryWarning(actorName, lineNumber,
                            "contains duplicate vertices"));
                    first = vertices.size();
                    break;
                }
            }
        }

        double[] normal = polygonNormal(vertices);
        double normalLength = length(normal);
        if (normalLength <= GEOMETRY_EPSILON) {
            warnings.add(new GeometryWarning(actorName, lineNumber, "has zero area or collinear vertices"));
            return warnings;
        }
        double maxDistance = 0.0;
        Point3 origin = vertices.get(0);
        for (Point3 vertex : vertices) {
            maxDistance = Math.max(maxDistance, Math.abs(dot(normal, subtract(vertex, origin))) / normalLength);
        }
        if (maxDistance > PLANARITY_TOLERANCE) {
            warnings.add(new GeometryWarning(actorName, lineNumber,
                    String.format(Locale.US, "is non-planar (maximum distance %.6f)", maxDistance)));
        }
        if (polygonSelfIntersects(vertices, normal)) {
            warnings.add(new GeometryWarning(actorName, lineNumber, "has self-intersecting edges"));
        }
        return warnings;
    }

    private static double[] polygonNormal(List<Point3> vertices) {
        Point3 origin = vertices.get(0);
        for (int first = 1; first < vertices.size() - 1; first++) {
            double[] normal = cross(subtract(vertices.get(first), origin),
                    subtract(vertices.get(first + 1), origin));
            if (length(normal) > GEOMETRY_EPSILON) return normal;
        }
        return new double[] { 0.0, 0.0, 0.0 };
    }

    private static boolean polygonSelfIntersects(List<Point3> vertices, double[] normal) {
        int droppedAxis = dominantAxis(normal);
        for (int first = 0; first < vertices.size(); first++) {
            int firstNext = (first + 1) % vertices.size();
            double[] firstA = project(vertices.get(first), droppedAxis);
            double[] firstB = project(vertices.get(firstNext), droppedAxis);
            for (int second = first + 1; second < vertices.size(); second++) {
                int secondNext = (second + 1) % vertices.size();
                if (first == second || firstNext == second || secondNext == first) continue;
                if (segmentsIntersect(firstA, firstB,
                        project(vertices.get(second), droppedAxis),
                        project(vertices.get(secondNext), droppedAxis))) return true;
            }
        }
        return false;
    }

    private static int dominantAxis(double[] vector) {
        double x = Math.abs(vector[0]);
        double y = Math.abs(vector[1]);
        double z = Math.abs(vector[2]);
        return x >= y && x >= z ? 0 : y >= z ? 1 : 2;
    }

    private static double[] project(Point3 point, int droppedAxis) {
        return switch (droppedAxis) {
            case 0 -> new double[] { point.y, point.z };
            case 1 -> new double[] { point.x, point.z };
            default -> new double[] { point.x, point.y };
        };
    }

    private static boolean segmentsIntersect(double[] firstA, double[] firstB,
                                             double[] secondA, double[] secondB) {
        double firstOrientation = orientation(firstA, firstB, secondA);
        double secondOrientation = orientation(firstA, firstB, secondB);
        double thirdOrientation = orientation(secondA, secondB, firstA);
        double fourthOrientation = orientation(secondA, secondB, firstB);
        if (((firstOrientation > GEOMETRY_EPSILON && secondOrientation < -GEOMETRY_EPSILON)
                || (firstOrientation < -GEOMETRY_EPSILON && secondOrientation > GEOMETRY_EPSILON))
                && ((thirdOrientation > GEOMETRY_EPSILON && fourthOrientation < -GEOMETRY_EPSILON)
                || (thirdOrientation < -GEOMETRY_EPSILON && fourthOrientation > GEOMETRY_EPSILON))) return true;
        return Math.abs(firstOrientation) <= GEOMETRY_EPSILON && onSegment(firstA, firstB, secondA)
                || Math.abs(secondOrientation) <= GEOMETRY_EPSILON && onSegment(firstA, firstB, secondB)
                || Math.abs(thirdOrientation) <= GEOMETRY_EPSILON && onSegment(secondA, secondB, firstA)
                || Math.abs(fourthOrientation) <= GEOMETRY_EPSILON && onSegment(secondA, secondB, firstB);
    }

    private static double orientation(double[] first, double[] second, double[] point) {
        return (second[0] - first[0]) * (point[1] - first[1])
                - (second[1] - first[1]) * (point[0] - first[0]);
    }

    private static boolean onSegment(double[] first, double[] second, double[] point) {
        return point[0] >= Math.min(first[0], second[0]) - GEOMETRY_EPSILON
                && point[0] <= Math.max(first[0], second[0]) + GEOMETRY_EPSILON
                && point[1] >= Math.min(first[1], second[1]) - GEOMETRY_EPSILON
                && point[1] <= Math.max(first[1], second[1]) + GEOMETRY_EPSILON;
    }

    private static double distanceSquared(Point3 first, Point3 second) {
        return squared(first.x - second.x) + squared(first.y - second.y) + squared(first.z - second.z);
    }

    private static double[] subtract(Point3 first, Point3 second) {
        return new double[] { first.x - second.x, first.y - second.y, first.z - second.z };
    }

    private static double[] cross(double[] first, double[] second) {
        return new double[] {
                first[1] * second[2] - first[2] * second[1],
                first[2] * second[0] - first[0] * second[2],
                first[0] * second[1] - first[1] * second[0]
        };
    }

    private static double dot(double[] first, double[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    private static double length(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static List<BrushIssue> findOffGridBrushes(String map, int gridStep, int minMove) {
        return findOffGridBrushes(map, gridStep, minMove, Integer.MAX_VALUE, SnapMode.NEAREST,
                true, GRID_NOISE_TOLERANCE);
    }

    private static List<BrushIssue> findOffGridBrushes(String map, int gridStep, int minMove,
                                                       int maxMove, SnapMode snapMode) {
        return findOffGridBrushes(map, gridStep, minMove, maxMove, snapMode,
                true, GRID_NOISE_TOLERANCE);
    }

    private static List<BrushIssue> findOffGridBrushes(String map, int gridStep, int minMove,
                                                       int maxMove, SnapMode snapMode,
                                                       boolean cleanNoise, double noiseTolerance) {
        String[] lines = map.split("\\R", -1);
        List<BrushIssue> found = new ArrayList<>();

        for (int index = 0; index < lines.length; index++) {
            String name = brushActorName(lines[index]);
            if (name == null) continue;

            int end = findActorEnd(lines, index + 1);
            if (end < 0) {
                found.add(new BrushIssue(name, index, lines.length - 1,
                        0, 0, 0, 0, 0, 0.0, true));
                break;
            }
            int offGridCoordinates = 0;
            int blockedCoordinates = 0;
            int ignoredCoordinates = 0;
            int cleanedNoiseCoordinates = 0;
            int malformedVertices = 0;
            double largestAdjustment = 0.0;
            for (int line = index; line <= end; line++) {
                GridCheck check = checkVertexLine(lines[line], gridStep, minMove, maxMove, snapMode,
                        cleanNoise, noiseTolerance);
                offGridCoordinates += check.offGridCoordinates;
                blockedCoordinates += check.blockedCoordinates;
                ignoredCoordinates += check.ignoredCoordinates;
                cleanedNoiseCoordinates += check.cleanedNoiseCoordinates;
                malformedVertices += check.malformedVertices;
                largestAdjustment = Math.max(largestAdjustment, check.largestAdjustment);
            }
            found.add(new BrushIssue(name, index, end, offGridCoordinates, blockedCoordinates,
                    ignoredCoordinates, cleanedNoiseCoordinates, malformedVertices, largestAdjustment, false));
            index = end;
        }
        return found;
    }

    private static String brushActorName(String line) {
        if (!BEGIN_ACTOR.matcher(line).matches() || !BRUSH_CLASS.matcher(line).find()) return null;
        Matcher name = ACTOR_NAME.matcher(line);
        if (!name.find()) return "unnamed brush";
        return name.group(1) != null ? name.group(1) : name.group(2);
    }

    private static String actorName(String line) {
        if (!BEGIN_ACTOR.matcher(line).matches()) return null;
        Matcher name = ACTOR_NAME.matcher(line);
        if (!name.find()) return null;
        return name.group(1) != null ? name.group(1) : name.group(2);
    }

    private static int findActorEnd(String[] lines, int start) {
        for (int index = start; index < lines.length; index++) {
            if (END_ACTOR.matcher(lines[index]).matches()) return index;
        }
        return -1;
    }

    private static GridCheck checkVertexLine(String line, int gridStep, int minMove,
                                             int maxMove, SnapMode snapMode) {
        return checkVertexLine(line, gridStep, minMove, maxMove, snapMode,
                true, GRID_NOISE_TOLERANCE);
    }

    private static GridCheck checkVertexLine(String line, int gridStep, int minMove,
                                             int maxMove, SnapMode snapMode,
                                             boolean cleanNoise, double noiseTolerance) {
        if (!VERTEX_LINE.matcher(line).find()) return GridCheck.CLEAN;
        Matcher matcher = NUMBER.matcher(line);
        int offGrid = 0;
        int blocked = 0;
        int ignored = 0;
        int cleanedNoise = 0;
        int coordinates = 0;
        double largestAdjustment = 0.0;
        for (int coordinate = 0; coordinate < 3 && matcher.find(); coordinate++) {
            coordinates++;
            double value = Double.parseDouble(matcher.group());
            SnapDecision decision = snapDecision(value, gridStep, minMove, maxMove, snapMode,
                    cleanNoise, noiseTolerance);
            if (decision.action != SnapAction.ALIGNED) {
                offGrid++;
                if (decision.action == SnapAction.BLOCKED_MAX_MOVE) blocked++;
                if (decision.action == SnapAction.IGNORED_MIN_MOVE) ignored++;
                if (decision.action == SnapAction.CLEANED_NOISE) cleanedNoise++;
                largestAdjustment = Math.max(largestAdjustment, decision.adjustment);
            }
        }
        return coordinates < 3
                ? new GridCheck(offGrid, blocked, ignored, cleanedNoise, largestAdjustment, 1)
                : new GridCheck(offGrid, blocked, ignored, cleanedNoise, largestAdjustment, 0);
    }

    private static String optimizeMap(String map, List<BrushIssue> issues, boolean[] optimizeActor,
                                      int gridStep, int minMove, int maxMove, SnapMode snapMode,
                                      boolean preserveStraightChains) {
        return optimizeMap(map, issues, optimizeActor, gridStep, minMove, maxMove, snapMode,
                true, GRID_NOISE_TOLERANCE, preserveStraightChains);
    }

    private static String optimizeMap(String map, List<BrushIssue> issues, boolean[] optimizeActor,
                                      int gridStep, int minMove, int maxMove, SnapMode snapMode,
                                      boolean cleanNoise, double noiseTolerance,
                                      boolean preserveStraightChains) {
        String separator = lineSeparator(map);
        String[] lines = map.split("\\R", -1);
        StringBuilder output = new StringBuilder();
        boolean[] protectCurvedGeometry = new boolean[issues.size()];
        if (preserveStraightChains) {
            for (int index = 0; index < issues.size(); index++) {
                protectCurvedGeometry[index] = isCurvedBrush(lines, issues.get(index));
            }
        }
        int issueIndex = 0;

        for (int line = 0; line < lines.length; line++) {
            while (issueIndex < issues.size() && line > issues.get(issueIndex).endLine) issueIndex++;
            boolean optimize = issueIndex < issues.size() && optimizeActor[issueIndex]
                    && !issues.get(issueIndex).incomplete && issues.get(issueIndex).malformedVertices == 0
                    && line >= issues.get(issueIndex).startLine && line <= issues.get(issueIndex).endLine;
            output.append(optimize && VERTEX_LINE.matcher(lines[line]).find()
                    ? snapVertexLine(lines[line], gridStep, minMove, maxMove, snapMode,
                            cleanNoise, noiseTolerance, protectCurvedGeometry[issueIndex]) : lines[line]);
            if (line < lines.length - 1) output.append(separator);
        }
        String optimized = output.toString();
        return preserveStraightChains
                ? preserveCollinearMidpoints(map, optimized, issues, optimizeActor, gridStep)
                : optimized;
    }

    /**
     * Detects a circular or cylindrical brush from its vertex cloud. The
     * optimizer protects such brushes because independent coordinate rounding
     * changes their radius even when every individual move is small.
     */
    private static boolean isCurvedBrush(String[] lines, BrushIssue issue) {
        List<Point3> points = new ArrayList<>();
        for (int line = issue.startLine; line <= issue.endLine && line < lines.length; line++) {
            Point3 point = readVertex(lines[line]);
            if (point != null) points.add(point);
        }
        if (points.size() < 8) return false;

        for (int normalAxis = 0; normalAxis < 3; normalAxis++) {
            List<double[]> projected = new ArrayList<>();
            for (Point3 point : points) {
                double first = coordinate(point, (normalAxis + 1) % 3);
                double second = coordinate(point, (normalAxis + 2) % 3);
                boolean duplicate = projected.stream().anyMatch(existing ->
                        Math.abs(existing[0] - first) <= 1.0e-4
                                && Math.abs(existing[1] - second) <= 1.0e-4);
                if (!duplicate) projected.add(new double[] { first, second });
            }
            if (projected.size() < 8) continue;

            double centerFirst = projected.stream().mapToDouble(value -> value[0]).average().orElse(0.0);
            double centerSecond = projected.stream().mapToDouble(value -> value[1]).average().orElse(0.0);
            List<Double> radii = new ArrayList<>();
            for (double[] point : projected) {
                radii.add(Math.hypot(point[0] - centerFirst, point[1] - centerSecond));
            }
            double maximumRadius = radii.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            if (maximumRadius <= 1.0) continue;

            List<Double> outerRadii = new ArrayList<>();
            boolean[] angularBins = new boolean[16];
            int occupiedBins = 0;
            for (int index = 0; index < projected.size(); index++) {
                if (radii.get(index) < maximumRadius * 0.85) continue;
                outerRadii.add(radii.get(index));
                double angle = Math.atan2(projected.get(index)[1] - centerSecond,
                        projected.get(index)[0] - centerFirst);
                int bin = (int) Math.floor((angle + Math.PI) * angularBins.length / (2.0 * Math.PI));
                bin = Math.max(0, Math.min(angularBins.length - 1, bin));
                if (!angularBins[bin]) {
                    angularBins[bin] = true;
                    occupiedBins++;
                }
            }
            if (outerRadii.size() < 8 || occupiedBins < 6) continue;
            double minimumOuterRadius = outerRadii.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double averageOuterRadius = outerRadii.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            if (averageOuterRadius > 0.0
                    && (averageOuterRadius - minimumOuterRadius) / averageOuterRadius <= 0.2) {
                return true;
            }
        }
        return false;
    }

    private static double coordinate(Point3 point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            case 2 -> point.z;
            default -> throw new IllegalArgumentException("Invalid coordinate axis: " + axis);
        };
    }

    /**
     * Grid snapping can break a curve chain such as A--M--B when M was the
     * midpoint of A and B in the original brush. Restore that exact midpoint
     * relationship while keeping all three points on the selected grid.
     */
    private static String preserveCollinearMidpoints(String originalMap, String snappedMap,
                                                      List<BrushIssue> issues, boolean[] optimizeActor, int gridStep) {
        String separator = lineSeparator(snappedMap);
        String[] originalLines = originalMap.split("\\R", -1);
        String[] snappedLines = snappedMap.split("\\R", -1);

        for (int issueIndex = 0; issueIndex < issues.size(); issueIndex++) {
            if (!optimizeActor[issueIndex]) continue;
            BrushIssue issue = issues.get(issueIndex);
            if (isCurvedBrush(originalLines, issue)) continue;
            Map<Point3, List<Integer>> occurrences = new HashMap<>();
            for (int line = issue.startLine; line <= issue.endLine; line++) {
                Point3 point = readVertex(originalLines[line]);
                if (point != null) occurrences.computeIfAbsent(point, ignored -> new ArrayList<>()).add(line);
            }

            List<Point3> points = new ArrayList<>(occurrences.keySet());
            List<MidpointRelation> relations = new ArrayList<>();
            for (int middleIndex = 0; middleIndex < points.size(); middleIndex++) {
                Point3 middle = points.get(middleIndex);
                for (int firstIndex = 0; firstIndex < points.size(); firstIndex++) {
                    if (firstIndex == middleIndex) continue;
                    for (int lastIndex = firstIndex + 1; lastIndex < points.size(); lastIndex++) {
                        if (lastIndex == middleIndex) continue;
                        Point3 first = points.get(firstIndex);
                        Point3 last = points.get(lastIndex);
                        if (middle.isMidpointOf(first, last, MIDPOINT_TOLERANCE)) {
                            relations.add(new MidpointRelation(first, middle, last));
                        }
                    }
                }
            }

            // A single accidental midpoint is common in regular geometry. Two
            // or more linked midpoint chains identify the curved-brush pattern.
            if (relations.size() < 2) continue;

            Map<Point3, Point3> corrected = new HashMap<>();
            for (Point3 original : points) corrected.put(original, readVertex(snappedLines[occurrences.get(original).get(0)]));
            for (MidpointRelation relation : relations) {
                Point3 first = corrected.get(relation.first);
                Point3 middle = corrected.get(relation.middle);
                Point3 last = corrected.get(relation.last);
                Point3[] aligned = alignMidpoint(first, middle, last, relation.first, relation.last, gridStep);
                corrected.put(relation.first, aligned[0]);
                corrected.put(relation.middle, aligned[1]);
                corrected.put(relation.last, aligned[2]);
            }
            for (Map.Entry<Point3, List<Integer>> entry : occurrences.entrySet()) {
                Point3 replacement = corrected.get(entry.getKey());
                for (int line : entry.getValue()) snappedLines[line] = replaceVertex(snappedLines[line], replacement);
            }
        }
        return String.join(separator, snappedLines);
    }

    private static String lineSeparator(String text) {
        if (text.contains("\r\n")) return "\r\n";
        if (text.indexOf('\r') >= 0) return "\r";
        return "\n";
    }

    private static Point3[] alignMidpoint(Point3 first, Point3 middle, Point3 last,
                                          Point3 originalFirst, Point3 originalLast, int gridStep) {
        double[] adjustedFirst = first.toArray();
        double[] adjustedMiddle = middle.toArray();
        double[] adjustedLast = last.toArray();
        double[] originalA = originalFirst.toArray();
        double[] originalB = originalLast.toArray();

        for (int axis = 0; axis < 3; axis++) {
            AxisAlignment alignment = chooseAlignedAxis(adjustedFirst[axis], adjustedMiddle[axis], adjustedLast[axis],
                    originalA[axis], originalB[axis], gridStep);
            adjustedFirst[axis] = alignment.first;
            adjustedMiddle[axis] = alignment.middle;
            adjustedLast[axis] = alignment.last;
        }
        return new Point3[] { Point3.of(adjustedFirst), Point3.of(adjustedMiddle), Point3.of(adjustedLast) };
    }

    /** Finds a nearby grid-aligned A--M--B chain, preferring coarse shared grid nodes. */
    private static AxisAlignment chooseAlignedAxis(double first, double middle, double last,
                                                    double originalFirst, double originalLast, int gridStep) {
        if (isGridAligned(first, gridStep) && isGridAligned(middle, gridStep)
                && isGridAligned(last, gridStep)
                && Math.abs(middle - (first + last) / 2.0) <= GRID_EPSILON) {
            return new AxisAlignment(first, middle, last);
        }

        AxisAlignment best = null;
        int bestGridQuality = Integer.MIN_VALUE;
        double bestMovement = Double.MAX_VALUE;
        for (int firstMove = -MAX_CURVE_GRID_MOVES; firstMove <= MAX_CURVE_GRID_MOVES; firstMove++) {
            double candidateFirst = first + firstMove * gridStep;
            for (int lastMove = -MAX_CURVE_GRID_MOVES; lastMove <= MAX_CURVE_GRID_MOVES; lastMove++) {
                double candidateLast = last + lastMove * gridStep;
                double candidateMiddle = (candidateFirst + candidateLast) / 2.0;
                if (!isGridAligned(candidateFirst, gridStep)
                        || !isGridAligned(candidateMiddle, gridStep)
                        || !isGridAligned(candidateLast, gridStep)) continue;
                if (Math.abs(candidateMiddle / gridStep - Math.rint(candidateMiddle / gridStep)) > GRID_EPSILON) continue;

                int quality = gridQuality(candidateFirst, gridStep) + gridQuality(candidateMiddle, gridStep)
                        + gridQuality(candidateLast, gridStep);
                double movement = squared(candidateFirst - originalFirst) + squared(candidateMiddle - middle)
                        + squared(candidateLast - originalLast);
                if (quality > bestGridQuality || (quality == bestGridQuality && movement < bestMovement)) {
                    best = new AxisAlignment(candidateFirst, candidateMiddle, candidateLast);
                    bestGridQuality = quality;
                    bestMovement = movement;
                }
            }
        }
        return best == null ? new AxisAlignment(first, middle, last) : best;
    }

    private static int gridQuality(double value, int baseStep) {
        if (Math.abs(value) < GRID_EPSILON) return 0;
        long units = Math.abs(Math.round(value / baseStep));
        int quality = 0;
        while (units > 0 && units % 2 == 0) {
            quality++;
            units /= 2;
        }
        return quality;
    }

    private static boolean isGridAligned(double value, int gridStep) {
        if (gridStep <= 0) return false;
        double units = value / gridStep;
        return Math.abs(units - Math.rint(units)) <= GRID_EPSILON;
    }

    private static double squared(double value) { return value * value; }

    private static String snapVertexLine(String line, int gridStep, int minMove, int maxMove) {
        return snapVertexLine(line, gridStep, minMove, maxMove, SnapMode.NEAREST);
    }

    private static String snapVertexLine(String line, int gridStep, int minMove, int maxMove,
                                         SnapMode snapMode) {
        return snapVertexLine(line, gridStep, minMove, maxMove, snapMode,
                true, GRID_NOISE_TOLERANCE);
    }

    private static String snapVertexLine(String line, int gridStep, int minMove, int maxMove,
                                         SnapMode snapMode, boolean cleanNoise,
                                         double noiseTolerance) {
        return snapVertexLine(line, gridStep, minMove, maxMove, snapMode,
                cleanNoise, noiseTolerance, false);
    }

    private static String snapVertexLine(String line, int gridStep, int minMove, int maxMove,
                                         SnapMode snapMode, boolean cleanNoise,
                                         double noiseTolerance, boolean protectCurvedGeometry) {
        if (!VERTEX_LINE.matcher(line).find()) return line;
        Matcher matcher = NUMBER.matcher(line);
        StringBuffer output = new StringBuffer();
        int coordinate = 0;
        while (coordinate < 3 && matcher.find()) {
            String token = matcher.group();
            double value = Double.parseDouble(token);
            SnapDecision decision = snapDecision(value, gridStep, minMove, maxMove, snapMode,
                    cleanNoise, noiseTolerance, protectCurvedGeometry);
            String replacement = decision.changed()
                    ? format(decision.target(), token.startsWith("-")) : token;
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            coordinate++;
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * Returns a snapped coordinate when the adjustment reaches the configured
     * minimum movement. Smaller adjustments stay untouched so the threshold
     * cannot distort curved geometry.
     */
    private static double snapTarget(double value, int gridStep, int minMove) {
        return snapTarget(value, gridStep, minMove, SnapMode.NEAREST);
    }

    private static double snapTarget(double value, int gridStep, int minMove, SnapMode snapMode) {
        return snapTarget(value, gridStep, minMove, Integer.MAX_VALUE, snapMode,
                true, GRID_NOISE_TOLERANCE);
    }

    private static double snapTarget(double value, int gridStep, int minMove, int maxMove,
                                     SnapMode snapMode, boolean cleanNoise, double noiseTolerance) {
        return snapDecision(value, gridStep, minMove, maxMove, snapMode,
                cleanNoise, noiseTolerance).target();
    }

    private static SnapDecision snapDecision(double value, int gridStep, int minMove, int maxMove,
                                             SnapMode snapMode, boolean cleanNoise,
                                             double noiseTolerance) {
        return snapDecision(value, gridStep, minMove, maxMove, snapMode,
                cleanNoise, noiseTolerance, false);
    }

    private static SnapDecision snapDecision(double value, int gridStep, int minMove, int maxMove,
                                             SnapMode snapMode, boolean cleanNoise,
                                             double noiseTolerance, boolean protectCurvedGeometry) {
        double candidate = snapCandidate(value, gridStep, snapMode);
        double adjustment = Math.abs(candidate - value);
        if (adjustment <= GRID_EPSILON) {
            return new SnapDecision(value, candidate, adjustment, SnapAction.ALIGNED);
        }
        if (cleanNoise && adjustment <= Math.max(0.0, noiseTolerance)) {
            return new SnapDecision(value, candidate, adjustment, SnapAction.CLEANED_NOISE);
        }
        if (minMove > 0 && adjustment < minMove) {
            return new SnapDecision(value, candidate, adjustment, SnapAction.IGNORED_MIN_MOVE);
        }
        if (adjustment > maxMove) {
            return new SnapDecision(value, candidate, adjustment, SnapAction.BLOCKED_MAX_MOVE);
        }
        if (protectCurvedGeometry) {
            return new SnapDecision(value, candidate, adjustment, SnapAction.PROTECTED_CURVE);
        }
        return new SnapDecision(value, candidate, adjustment, SnapAction.SNAPPED);
    }

    private static double snapCandidate(double value, int gridStep, SnapMode snapMode) {
        if (gridStep <= 0) throw new IllegalArgumentException("Grid step must be positive.");
        SnapMode mode = snapMode == null ? SnapMode.NEAREST : snapMode;
        double scaled = value / gridStep;
        double snappedUnits = switch (mode) {
            case NEAREST -> Math.rint(scaled);
            case FLOOR -> Math.floor(scaled);
            case CEILING -> Math.ceil(scaled);
        };
        return snappedUnits * gridStep;
    }

    private static Point3 readVertex(String line) {
        if (!VERTEX_LINE.matcher(line).find()) return null;
        Matcher matcher = NUMBER.matcher(line);
        double[] values = new double[3];
        for (int coordinate = 0; coordinate < values.length; coordinate++) {
            if (!matcher.find()) return null;
            values[coordinate] = Double.parseDouble(matcher.group());
        }
        return Point3.of(values);
    }

    private static String replaceVertex(String line, Point3 replacement) {
        return replacePoint(line, replacement, VERTEX_LINE);
    }

    private static String replacePoint(String line, Point3 replacement, Pattern linePattern) {
        if (!linePattern.matcher(line).find()) return line;
        Matcher matcher = NUMBER.matcher(line);
        if (!matcher.find()) return line;
        int numberStart = matcher.start();
        int numberEnd = matcher.end();
        for (int coordinate = 1; coordinate < 3; coordinate++) {
            if (!matcher.find()) return line;
            numberEnd = matcher.end();
        }
        return line.substring(0, numberStart)
                + format(replacement.x, replacement.x < 0.0) + ","
                + format(replacement.y, replacement.y < 0.0) + ","
                + format(replacement.z, replacement.z < 0.0)
                + line.substring(numberEnd);
    }

    private static String format(double value, boolean negativeInput) {
        if (value == 0.0 && negativeInput) return "-00000.000000";
        return String.format(Locale.US, "%+013.6f", value);
    }

    private enum SnapAction {
        ALIGNED,
        CLEANED_NOISE,
        SNAPPED,
        IGNORED_MIN_MOVE,
        BLOCKED_MAX_MOVE,
        PROTECTED_CURVE
    }

    private record SnapDecision(double value, double candidate, double adjustment, SnapAction action) {
        double target() {
            return action == SnapAction.ALIGNED || action == SnapAction.CLEANED_NOISE
                    || action == SnapAction.SNAPPED ? candidate : value;
        }

        boolean changed() {
            return action == SnapAction.CLEANED_NOISE || action == SnapAction.SNAPPED;
        }
    }

    private record GridCheck(int offGridCoordinates, int blockedCoordinates,
                             int ignoredCoordinates, int cleanedNoiseCoordinates,
                             double largestAdjustment, int malformedVertices) {
        static final GridCheck CLEAN = new GridCheck(0, 0, 0, 0, 0.0, 0);
    }

    private record BrushIssue(String name, int startLine, int endLine, int offGridCoordinates,
                              int blockedCoordinates, int ignoredCoordinates,
                              int cleanedNoiseCoordinates, int malformedVertices,
                              double largestAdjustment, boolean incomplete) {
        boolean hasProblems() {
            return incomplete || malformedVertices > 0 || offGridCoordinates > 0;
        }

        @Override
        public String toString() {
            if (incomplete) return name + " — incomplete brush";
            if (malformedVertices > 0) return name + " — malformed Vertex line(s): " + malformedVertices;
            if (offGridCoordinates == 0) return name + " — on-grid, checked for curve alignment";
            String blocked = blockedCoordinates == 0 ? "" : ", blocked by Max move: " + blockedCoordinates;
            String ignored = ignoredCoordinates == 0 ? "" : ", kept below Min. move: " + ignoredCoordinates;
            String noise = cleanedNoiseCoordinates == 0 ? "" : ", noise cleaned: " + cleanedNoiseCoordinates;
            return name + " — " + offGridCoordinates + " off-grid coordinate(s), largest move: "
                    + String.format(Locale.US, "%.6f", largestAdjustment) + blocked + ignored + noise;
        }
    }

    private static final class BrushSelection {
        private final BrushIssue issue;
        private boolean selected;

        private BrushSelection(BrushIssue issue, boolean selected) {
            this.issue = issue;
            this.selected = selected;
        }

        private String label() {
            return issue.toString();
        }
    }

    private record GeometryWarning(String actorName, int lineNumber, String message) {
        @Override
        public String toString() {
            return actorName + " (line " + lineNumber + "): " + message;
        }
    }

    private record MidpointRelation(Point3 first, Point3 middle, Point3 last) { }

    private record AxisAlignment(double first, double middle, double last) { }

    private record OptimizationRun(String input, List<BrushIssue> issues,
                                   boolean[] optimizeActor, String optimized) { }

    private record Point3(double x, double y, double z) {
        static Point3 of(double[] values) { return new Point3(values[0], values[1], values[2]); }
        double[] toArray() { return new double[] { x, y, z }; }
        boolean isMidpointOf(Point3 first, Point3 last, double tolerance) {
            return Math.abs(x - (first.x + last.x) / 2.0) <= tolerance
                    && Math.abs(y - (first.y + last.y) / 2.0) <= tolerance
                    && Math.abs(z - (first.z + last.z) / 2.0) <= tolerance;
        }
    }
}

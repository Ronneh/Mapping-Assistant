import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Lists actors for manual inspection in UnrealEd. There is no map transformation action. */
public final class QuickZoneOptimizerPanel extends JPanel {
    private final JTextArea inputArea = codeArea();
    private final JTextArea resultsArea = codeArea();
    private final JTextArea generalArea = codeArea();
    private final JCheckBox onlyBrushes = new JCheckBox("Show only Brushes", true);
    private final JCheckBox onlySemiSolid = new JCheckBox("Show only Semi-Solid Brushes", false);
    private final JButton analyzeButton = new JButton("Analyze");
    private final JButton copyButton = new JButton("Copy Results");
    private final JLabel status = new JLabel("Paste the complete exported map, then Analyze to find actor names.");
    private QuickZoneOptimizer.AnalysisResult cachedAnalysis;
    private int generation;
    private boolean busy;

    public QuickZoneOptimizerPanel() {
        super(new BorderLayout(8, 8));
        setBackground(AssistantTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel heading = new JPanel(new BorderLayout(0, 6));
        heading.setOpaque(false);
        JLabel title = new JLabel("Quick Zone Optimizer");
        AssistantTheme.stylePageTitle(title);
        heading.add(title, BorderLayout.NORTH);
        JLabel note = new JLabel("<html>Diagnostic only: LevelInfo zones can be legitimate rooms. "
                + "Inspect actors manually in UnrealEd. Run <b>Build All</b> before exporting and after manual edits.</html>");
        note.setForeground(AssistantTheme.MUTED);
        heading.add(note, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);

        resultsArea.setEditable(false);
        generalArea.setEditable(false);
        TextSearchSupport.install(inputArea, this, "Input Code");
        TextSearchSupport.install(resultsArea, this, "Suspicious Zones");
        TextSearchSupport.install(generalArea, this, "General Information");
        JScrollPane generalScroll = scroll("General Information", generalArea);
        generalScroll.setPreferredSize(new Dimension(450, 220));
        JSplitPane resultsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                scroll("Suspicious Zones", resultsArea), generalScroll);
        resultsSplit.setResizeWeight(0.7);
        resultsSplit.setContinuousLayout(true);
        AssistantTheme.styleSplitPane(resultsSplit);
        SplitPaneState.install(resultsSplit, QuickZoneOptimizerPanel.class, "quick-zones.results");
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                scroll("Input Code", inputArea), resultsSplit);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        AssistantTheme.styleSplitPane(split);
        SplitPaneState.install(split, QuickZoneOptimizerPanel.class, "quick-zones.code");
        add(split, BorderLayout.CENTER);

        JPanel filters = new JPanel(new EdgeAlignedFlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        for (JCheckBox check : List.of(onlyBrushes, onlySemiSolid)) {
            check.setOpaque(false);
            filters.add(check);
        }
        onlyBrushes.addActionListener(event -> refreshFilters());
        onlySemiSolid.addActionListener(event -> {
            if (onlySemiSolid.isSelected()) onlyBrushes.setSelected(true);
            refreshFilters();
        });
        JPanel buttons = new JPanel(new EdgeAlignedFlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton paste = new JButton("Paste");
        paste.addActionListener(event -> {
            try {
                inputArea.setText((String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor));
            } catch (Exception ex) { status.setText("Clipboard does not contain readable text."); }
        });
        analyzeButton.addActionListener(event -> analyze(false));
        copyButton.addActionListener(event -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(resultsArea.getText()), null);
                status.setText("Results copied. Find and inspect these actors manually in UnrealEd.");
            } catch (IllegalStateException ex) { status.setText("Clipboard is busy; try again."); }
        });
        JButton reset = new JButton("Reset");
        reset.addActionListener(event -> {
            inputArea.setText("");
            invalidateResults();
            status.setText("Paste the complete exported map, then Analyze to find actor names.");
        });
        for (JButton button : List.of(paste, analyzeButton, copyButton, reset)) buttons.add(button);
        status.setForeground(AssistantTheme.MUTED);
        JPanel actions = new JPanel(new BorderLayout(0, 5));
        actions.setOpaque(false);
        actions.add(filters, BorderLayout.NORTH);
        actions.add(buttons, BorderLayout.CENTER);
        actions.add(status, BorderLayout.SOUTH);
        add(actions, BorderLayout.SOUTH);
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { invalidateResults(); }
            public void removeUpdate(DocumentEvent event) { invalidateResults(); }
            public void changedUpdate(DocumentEvent event) { invalidateResults(); }
        });
        updateControls();
    }

    static QuickZoneOptimizer.AnalysisResult analyzeMap(String input) { return QuickZoneOptimizer.analyzeMap(input); }

    private void invalidateResults() {
        generation++;
        cachedAnalysis = null;
        resultsArea.setText("");
        generalArea.setText("");
        status.setText("Input changed. Analyze to refresh the actor names.");
        updateControls();
    }

    private void refreshFilters() {
        updateControls();
        if (cachedAnalysis != null) analyze(true);
    }

    private void updateControls() {
        analyzeButton.setEnabled(!busy);
        onlyBrushes.setEnabled(!busy && !onlySemiSolid.isSelected());
        onlySemiSolid.setEnabled(!busy);
        copyButton.setEnabled(!busy && !resultsArea.getText().isBlank());
    }

    private record Report(QuickZoneOptimizer.AnalysisResult analysis, String text, String generalInformation) {}

    private void analyze(boolean useCached) {
        String input = inputArea.getText();
        if (input.isBlank()) { status.setText("Paste map code first."); return; }
        int request = ++generation;
        var cached = useCached ? cachedAnalysis : null;
        boolean brushes = onlyBrushes.isSelected();
        boolean semiSolid = onlySemiSolid.isSelected();
        busy = true;
        updateControls();
        status.setText(useCached ? "Updating displayed actors..." : "Analyzing zone assignments...");
        new SwingWorker<Report, Void>() {
            @Override protected Report doInBackground() {
                var analysis = cached != null ? cached : analyzeMap(input);
                return new Report(analysis, QuickZoneOptimizer.formatResults(analysis, brushes, semiSolid),
                        QuickZoneOptimizer.formatGeneralInformation(analysis, brushes, semiSolid));
            }
            @Override protected void done() {
                busy = false;
                try {
                    if (request != generation) return;
                    Report report = get();
                    cachedAnalysis = report.analysis;
                    resultsArea.setText(report.text);
                    resultsArea.setCaretPosition(0);
                    generalArea.setText(report.generalInformation);
                    generalArea.setCaretPosition(0);
                    status.setText("Analysis complete. Map code unchanged; inspect the listed actors manually.");
                } catch (Exception ex) {
                    cachedAnalysis = null;
                    resultsArea.setText("");
                    generalArea.setText("");
                    status.setText("Could not analyze the map: " + ex.getMessage());
                } finally { updateControls(); }
            }
        }.execute();
    }

    private static JTextArea codeArea() {
        JTextArea area = new JTextArea();
        area.setFont(new Font("Verdana", Font.PLAIN, 12));
        area.setBackground(AssistantTheme.CODE_BACKGROUND);
        area.setLineWrap(false);
        return area;
    }

    private static JScrollPane scroll(String title, JTextArea area) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(AssistantTheme.titled(title));
        return scroll;
    }
}

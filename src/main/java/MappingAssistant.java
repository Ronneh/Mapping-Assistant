import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.JSeparator;
import javax.swing.JTextArea;

/** Single-window launcher and workspace for classic mapping utilities. */
public final class MappingAssistant {
    private static final String HOME = "home";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("EEEE, d MMMM uuuu", Locale.ENGLISH);

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JPanel navigation = new JPanel();
    private final Map<String, JButton> navigationButtons = new LinkedHashMap<>();
    private JScrollPane featureScroll;

    public static void main(String[] args) {
        System.setProperty("sun.awt.window.darkMode", "true");
        SwingUtilities.invokeLater(() -> {
            AssistantTheme.install();
            new MappingAssistant().show();
        });
    }

    private void show() {
        JFrame frame = new JFrame("Mapping Assistant");
        java.net.URL iconUrl = MappingAssistant.class.getResource("/app-icon.png");
        if (iconUrl != null) {
            frame.setIconImage(new ImageIcon(iconUrl).getImage());
        }
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(createContent());
        frame.pack();
        frame.setSize(1280, 820);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        WindowsTitleBar.enableDark(frame);
        frame.setVisible(true);
    }

    private JPanel createContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(AssistantTheme.BACKGROUND);
        navigation.setLayout(new BoxLayout(navigation, BoxLayout.X_AXIS));
        navigation.setBackground(AssistantTheme.HEADER);
        navigation.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));

        registerApp(HOME, "\u2302", "Home", "\u00a0", createHomePanel());
        registerApp("generator", "+", "Brush", "Generator", new BrushGeneratorPanel());
        registerApp("optimizer", "\u25a6", "Brush", "Optimizer", new BrushOptimizer().createContent());
        registerApp("quick-zone-optimizer", "\u2315", "Quick Zone", "Optimizer", new QuickZoneOptimizerPanel());
        registerApp("prefabs", "\u25c7", "Prefab", "Explorer", new PrefabExplorerPanel());
        registerApp("double", "\u21c9", "Double", "Map", new MapDoublerPanel());
        registerApp("resizer", "\u2922", "Resize", "Image", new ImageResizerPanel());
        registerApp("screenshots", "\u25a3", "Level", "Screenshot", new ScreenshotMakerPanel());
        registerApp("seamless", "\u223f", "Seamless", "Texture", new SeamlessTexturePanel());
        registerApp("scripting", "\u2328", "UScript", "Guide", new ScriptingPanel());
        registerApp("editor-help", "?", "Editor", "Guide", new EditorHelpPanel());
        navigation.add(Box.createHorizontalGlue());

        root.add(navigation, BorderLayout.NORTH);
        root.add(cards, BorderLayout.CENTER);
        showApp(HOME);
        removeButtonFocusPainting(root);
        return root;
    }

    private void removeButtonFocusPainting(Component component) {
        if (component instanceof AbstractButton button) button.setFocusPainted(false);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) removeButtonFocusPainting(child);
        }
    }

    private void registerApp(String id, String icon, String firstLine, String secondLine, JPanel content) {
        cards.add(content, id);
        JButton button = navButton(icon, firstLine, secondLine);
        button.setToolTipText(firstLine + (" ".equals(secondLine) ? "" : " " + secondLine));
        button.addActionListener(event -> showApp(id));
        navigationButtons.put(id, button);
        navigation.add(button);
        navigation.add(Box.createHorizontalStrut(6));
    }

    private void addPlaceholder(String icon) {
        JButton button = navButton(icon, "Future", "Tool");
        button.setEnabled(false);
        button.setToolTipText("Reserved for a future tool");
        navigation.add(button);
        navigation.add(Box.createHorizontalStrut(6));
    }

    private JButton navButton(String icon, String firstLine, String secondLine) {
        JButton button = new JButton("<html><div style='text-align:center;font-family:Dialog;font-size:16px'>"
                + icon + "</div><div style='text-align:center;font-size:9px'>"
                + firstLine + "<br>" + secondLine + "</div></html>");
        button.setPreferredSize(new Dimension(76, 62));
        button.setMaximumSize(new Dimension(76, 62));
        button.setMinimumSize(new Dimension(76, 62));
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(AssistantTheme.TEXT);
        button.setBackground(AssistantTheme.HEADER);
        button.setBorder(BorderFactory.createLineBorder(AssistantTheme.BORDER));
        return button;
    }

    private void showApp(String id) {
        cardLayout.show(cards, id);
        if (HOME.equals(id) && featureScroll != null) {
            SwingUtilities.invokeLater(() -> featureScroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
        }
        navigationButtons.forEach((appId, button) ->
                button.setBackground(appId.equals(id) ? AssistantTheme.ACCENT_DARK : AssistantTheme.HEADER));
    }

    private JPanel createHomePanel() {
        JPanel home = new JPanel(new BorderLayout(18, 18));
        home.setBackground(AssistantTheme.BACKGROUND);
        home.setBorder(BorderFactory.createEmptyBorder(24, 30, 24, 30));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JPanel welcome = new JPanel(new BorderLayout());
        welcome.setOpaque(false);
        JLabel heading = new JLabel("Mapping Assistant");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("A focused workspace for mapping tools.");
        subtitle.setForeground(AssistantTheme.MUTED);
        subtitle.setBorder(BorderFactory.createEmptyBorder(5, 1, 0, 0));
        welcome.add(heading, BorderLayout.NORTH);
        welcome.add(subtitle, BorderLayout.CENTER);
        top.add(welcome, BorderLayout.NORTH);
        JPanel dashboard = new JPanel(new BorderLayout(12, 0));
        dashboard.setOpaque(false);
        dashboard.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        dashboard.add(createFeatureGuide(), BorderLayout.CENTER);
        JPanel weatherPosition = new JPanel(new BorderLayout(0, 12));
        weatherPosition.setOpaque(false);
        weatherPosition.add(createWeatherAndClock(), BorderLayout.NORTH);
        weatherPosition.add(createMapNotes(), BorderLayout.CENTER);
        dashboard.add(weatherPosition, BorderLayout.EAST);
        top.add(dashboard, BorderLayout.CENTER);
        home.add(top, BorderLayout.CENTER);
        return home;
    }

    private JPanel createCompactClock() {
        JPanel clockPanel = new JPanel();
        clockPanel.setOpaque(false);
        clockPanel.setLayout(new BoxLayout(clockPanel, BoxLayout.Y_AXIS));
        clockPanel.setPreferredSize(new Dimension(150, 190));
        AnalogClock clock = new AnalogClock();
        clock.setAlignmentX(Component.CENTER_ALIGNMENT);
        clockPanel.add(clock);
        JLabel digital = new JLabel("", SwingConstants.CENTER);
        digital.setFont(new Font("Verdana", Font.BOLD, 22));
        digital.setAlignmentX(Component.CENTER_ALIGNMENT);
        clockPanel.add(Box.createVerticalStrut(4));
        clockPanel.add(digital);
        Timer timer = new Timer(1000, null);
        timer.setRepeats(false);
        timer.addActionListener(event -> {
            LocalDateTime now = LocalDateTime.now();
            clock.time = now;
            clock.repaint();
            digital.setText("<html><div style='text-align:center'>" + now.format(TIME)
                    + "</div><div style='font-family:sans-serif;font-size:9px;color:#9ca7b8'>"
                    + now.format(DATE) + "</div></html>");
            int untilNextSecond = Math.max(10, 1000 - now.getNano() / 1_000_000);
            timer.setInitialDelay(untilNextSecond);
            timer.restart();
        });
        timer.setInitialDelay(0);
        timer.start();
        return clockPanel;
    }

    private JPanel createWeatherAndClock() {
        JPanel combined = AssistantTheme.card(new BorderLayout(12, 0));
        combined.setPreferredSize(new Dimension(720, 220));
        combined.add(new WeatherPanel(), BorderLayout.CENTER);
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setForeground(AssistantTheme.BORDER);
        separator.setPreferredSize(new Dimension(1, 190));

        JPanel clockPosition = new JPanel(new BorderLayout(12, 0));
        clockPosition.setOpaque(false);
        clockPosition.add(separator, BorderLayout.WEST);
        clockPosition.add(createCompactClock(), BorderLayout.CENTER);
        combined.add(clockPosition, BorderLayout.EAST);
        return combined;
    }

    private JPanel createMapNotes() {
        JPanel notes = AssistantTheme.card(new BorderLayout());
        notes.setPreferredSize(new Dimension(460, 220));
        notes.add(new NotesPanel(), BorderLayout.CENTER);
        return notes;
    }

    private JPanel createFeatureGuide() {
        JPanel guide = AssistantTheme.card(new BorderLayout(0, 10));
        JLabel title = new JLabel("Available Tools");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        guide.add(title, BorderLayout.NORTH);

        JPanel features = new JPanel(new GridLayout(0, 2, 8, 8));
        features.setOpaque(false);
        features.add(featureInfo("Notes Explorer",
                "Organize notes and tasks in folders for each map."));
        features.add(featureInfo("Brush Generator",
                "Create grid-aligned polygon brushes for common CSG tasks."));
        features.add(featureInfo("Brush Optimizer",
                "Find and fix off-grid brush vertices safely."));
        features.add(featureInfo("Quick Zone Optimizer",
                "List actors in suspicious zones for manual inspection in UnrealEd."));
        features.add(featureInfo("Prefab Explorer",
                "Organize and preview prefabs instantly."));
        features.add(featureInfo("Double map",
                "Prepare duplicated map content for the opposite team."));
        features.add(featureInfo("Level Screenshot Maker",
                "Combine 4 map screenshots into one, add map name and export."));
        features.add(featureInfo("Image Resizer",
                "Resize images for import into Unreal Editor."));
        features.add(featureInfo("Seamless Texture",
                "Turn an image crop into a seamless texture."));
        features.add(featureInfo("UScript Guide",
                "Learn, write, check and compile UnrealScript Code."));
        features.add(featureInfo("Editor Guide",
                "Browse and search the complete Unreal Editor reference guide."));

        features.setPreferredSize(new Dimension(430, 500));
        featureScroll = new JScrollPane(features,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        featureScroll.setBorder(BorderFactory.createEmptyBorder());
        featureScroll.setOpaque(false);
        featureScroll.getViewport().setOpaque(false);
        featureScroll.setWheelScrollingEnabled(true);
        featureScroll.getVerticalScrollBar().setUnitIncrement(96);
        guide.add(featureScroll, BorderLayout.CENTER);
        return guide;
    }

    private JPanel featureInfo(String title, String description) {
        JPanel feature = new JPanel(new BorderLayout(0, 4));
        feature.setBackground(AssistantTheme.PANEL_ALT);
        feature.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AssistantTheme.BORDER),
                BorderFactory.createEmptyBorder(9, 10, 8, 10)));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        feature.add(heading, BorderLayout.NORTH);
        JTextArea text = new JTextArea(description);
        text.setEditable(false);
        text.setFocusable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setForeground(AssistantTheme.MUTED);
        text.setFont(text.getFont().deriveFont(12f));
        feature.add(text, BorderLayout.CENTER);
        return feature;
    }

    private static final class AnalogClock extends JPanel {
        private LocalDateTime time = LocalDateTime.now();
        AnalogClock() {
            setOpaque(false);
            setPreferredSize(new Dimension(92, 92));
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = Math.min(getWidth(), getHeight()) / 2 - 5;
            int cx = getWidth() / 2, cy = getHeight() / 2;
            g.setColor(AssistantTheme.PANEL_ALT);
            g.fillOval(cx-r, cy-r, r*2, r*2);
            g.setColor(AssistantTheme.TEXT);
            g.drawOval(cx-r, cy-r, r*2, r*2);
            for (int i=0; i<12; i++) {
                double a=Math.toRadians(i*30-90);
                g.drawLine(cx+(int)(Math.cos(a)*(r-5)), cy+(int)(Math.sin(a)*(r-5)),
                        cx+(int)(Math.cos(a)*(r-10)), cy+(int)(Math.sin(a)*(r-10)));
            }
            double seconds = time.getSecond();
            double minutes = time.getMinute() + seconds / 60.0;
            double hours = time.getHour() % 12 + minutes / 60.0;
            hand(g,cx,cy,r*.48,hours*30,4,AssistantTheme.TEXT);
            hand(g,cx,cy,r*.68,minutes*6,3,AssistantTheme.ACCENT);
            hand(g,cx,cy,r*.72,seconds*6,1,new Color(230,86,86));
            g.dispose();
        }
        private void hand(Graphics2D g,int x,int y,double length,double degrees,int width,Color color) {
            double a=Math.toRadians(degrees-90);
            g.setStroke(new java.awt.BasicStroke(width,1,1));
            g.setColor(color);
            g.drawLine(x,y,x+(int)(Math.cos(a)*length),y+(int)(Math.sin(a)*length));
        }
    }
}

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Compact, key-free Open-Meteo forecast card for the home screen. */
public final class WeatherPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WeatherSettings SETTINGS = new WeatherSettings();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final JButton unitButton = new JButton("°C");
    private final JLabel title = new JLabel();
    private final JLabel current = new JLabel("Loading weather...");
    private final JLabel condition = new JLabel(" ");
    private final JLabel status = new JLabel("Weather data: Open-Meteo");
    private final ForecastGraph graph = new ForecastGraph();
    private final JPanel days = new JPanel(new GridLayout(1, 7, 4, 0));
    private WeatherData data;
    private boolean fahrenheit = SETTINGS.getBoolean("weather.fahrenheit", defaultFahrenheit());
    private Location selectedLocation = storedLocation();
    private int selectedDay;
    private volatile int requestGeneration;
    private volatile int locationSearchGeneration;

    public WeatherPanel() {
        super(new BorderLayout(6, 3));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        setPreferredSize(new Dimension(520, 190));

        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.setOpaque(false);
        JPanel headerTop = new JPanel(new BorderLayout(8, 0));
        headerTop.setOpaque(false);
        JPanel location = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        location.setOpaque(false);
        title.setText(selectedLocation == null
                ? "Set up weather"
                : weatherTitle(selectedLocation.display));
        JButton edit = new JButton(new PencilIcon());
        edit.setMargin(new java.awt.Insets(2, 5, 2, 5));
        edit.setPreferredSize(new Dimension(30, 24));
        edit.setToolTipText("Change city");
        edit.addActionListener(event -> showLocationChooser());
        unitButton.setText(fahrenheit ? "°F" : "°C");
        unitButton.addActionListener(event -> {
            fahrenheit = !fahrenheit;
            SETTINGS.putBoolean("weather.fahrenheit", fahrenheit);
            unitButton.setText(fahrenheit ? "°F" : "°C");
            updateView();
        });
        location.add(title);
        location.add(edit);
        headerTop.add(location, BorderLayout.CENTER);
        headerTop.add(unitButton, BorderLayout.EAST);
        header.add(headerTop, BorderLayout.NORTH);
        condition.setForeground(AssistantTheme.MUTED);
        condition.setHorizontalAlignment(JLabel.RIGHT);
        header.add(condition, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(10, 0));
        center.setOpaque(false);
        JPanel summary = new JPanel(new GridLayout(1, 1));
        summary.setOpaque(false);
        current.setFont(current.getFont().deriveFont(Font.BOLD, 25f));
        summary.add(current);
        summary.setPreferredSize(new Dimension(105, 62));
        center.add(summary, BorderLayout.WEST);
        center.add(graph, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setOpaque(false);
        days.setOpaque(false);
        bottom.add(days, BorderLayout.CENTER);
        status.setForeground(AssistantTheme.MUTED);
        status.setFont(status.getFont().deriveFont(10f));
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        if (selectedLocation != null) refresh();
        else {
            current.setText(" ");
            status.setText("Click the pencil to choose your city.");
        }
    }

    private void showLocationChooser() {
        JTextField input = new JTextField(selectedLocation == null ? "" : selectedLocation.name, 28);
        DefaultComboBoxModel<Location> model = new DefaultComboBoxModel<>();
        JComboBox<Location> choices = new JComboBox<>(model);
        JLabel searchStatus = new JLabel("Enter at least two characters.");
        JPanel fields = new JPanel(new BorderLayout(0, 7));
        fields.setPreferredSize(new Dimension(420, 82));
        fields.add(input, BorderLayout.NORTH);
        fields.add(choices, BorderLayout.CENTER);
        fields.add(searchStatus, BorderLayout.SOUTH);
        javax.swing.Timer searchTimer = new javax.swing.Timer(350,
                event -> updateLocationMatches(input.getText(), model, searchStatus));
        searchTimer.setRepeats(false);
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() { searchTimer.restart(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { changed(); }
        });
        if (!input.getText().isBlank()) searchTimer.start();
        int result = DarkDialogs.confirm(this, fields,
                "Choose a location",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        searchTimer.stop();
        locationSearchGeneration++;
        if (result == JOptionPane.OK_OPTION && choices.getSelectedItem() instanceof Location location) {
            applyLocation(location);
        } else {
            status.setText("Location was not changed.");
        }
    }

    private void updateLocationMatches(String text, DefaultComboBoxModel<Location> model,
                                       JLabel searchStatus) {
        String query = text.trim();
        int generation = ++locationSearchGeneration;
        model.removeAllElements();
        if (query.length() < 2) {
            searchStatus.setText("Enter at least two characters.");
            return;
        }
        searchStatus.setText("Searching locations...");
        Thread worker = new Thread(() -> {
            try {
                List<Location> matches = searchLocations(query);
                SwingUtilities.invokeLater(() -> {
                    if (generation != locationSearchGeneration) return;
                    for (Location match : matches) model.addElement(match);
                    searchStatus.setText(matches.isEmpty()
                            ? "No matching location found."
                            : "Select the correct location below.");
                });
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == locationSearchGeneration)
                        searchStatus.setText("Location search is unavailable.");
                });
            }
        }, "weather-location-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyLocation(Location location) {
        selectedLocation = location;
        SETTINGS.put("weather.city", location.name);
        SETTINGS.put("weather.display", location.display);
        SETTINGS.putDouble("weather.latitude", location.latitude);
        SETTINGS.putDouble("weather.longitude", location.longitude);
        SETTINGS.put("weather.timezone", location.timezone);
        SETTINGS.putBoolean("weather.configured", true);
        title.setText(weatherTitle(location.display));
        revalidate();
        refresh();
    }

    private static List<Location> searchLocations(String city) throws Exception {
        String language = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        String query = URLEncoder.encode(city, StandardCharsets.UTF_8);
        JsonNode results = JSON.readTree(get(HTTP_CLIENT,
                "https://geocoding-api.open-meteo.com/v1/search?name=" + query
                        + "&count=8&language=" + language + "&format=json")).path("results");
        List<Location> locations = new ArrayList<>();
        if (results.isArray()) for (JsonNode result : results) {
            String name = result.path("name").asText();
            locations.add(new Location(name,
                    locationDisplay(name, result.path("admin1").asText(), result.path("country").asText()),
                    result.path("latitude").asDouble(), result.path("longitude").asDouble(),
                    result.path("timezone").asText(ZoneId.systemDefault().getId())));
        }
        return locations;
    }

    private static String locationDisplay(String city, String region, String country) {
        StringBuilder value = new StringBuilder(city);
        if (!region.isBlank() && !region.equalsIgnoreCase(city)) value.append(", ").append(region);
        if (!country.isBlank()) value.append(", ").append(country);
        return value.toString();
    }

    private static String weatherTitle(String city) {
        return "<html>Weather in <b>"
                + city.replace("&", "&amp;").replace("<", "&lt;") + "</b></html>";
    }

    private void refresh() {
        Location location = selectedLocation;
        if (location == null) return;
        status.setForeground(AssistantTheme.MUTED);
        status.setText("Loading forecast...");
        int generation = ++requestGeneration;
        Thread worker = new Thread(() -> load(location, generation), "weather-loader");
        worker.setDaemon(true);
        worker.start();
    }

    private void load(Location location, int generation) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude
                    + "&longitude=" + location.longitude
                    + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&forecast_days=7";
            String forecast = get(HTTP_CLIENT, url);
            cacheForecast(location, forecast);
            showForecast(parseForecast(location.name, forecast), generation, false);
        } catch (Exception exception) {
            try {
                showForecast(parseForecast(location.name, cachedForecast(location)), generation, true);
            } catch (Exception cacheException) {
                SwingUtilities.invokeLater(() -> {
                    if (generation != requestGeneration) return;
                    status.setForeground(new Color(225, 105, 105));
                    status.setText("Weather is currently unavailable. Check the connection.");
                    current.setText("—");
                    condition.setText(location.display);
                });
            }
        }
    }

    private void showForecast(WeatherData loaded, int generation, boolean cached) {
        SwingUtilities.invokeLater(() -> {
            if (generation != requestGeneration) return;
            data = loaded;
            selectedDay = 0;
            status.setForeground(AssistantTheme.MUTED);
            status.setText(cached
                    ? "Offline: showing the last saved forecast."
                    : "Weather data: Open-Meteo");
            updateView();
        });
    }

    private static WeatherData parseForecast(String name, String forecast) {
        return new WeatherData(name,
                numberValue(section(forecast, "current"), "temperature_2m"),
                (int) numberValue(section(forecast, "current"), "relative_humidity_2m"),
                numberValue(section(forecast, "current"), "wind_speed_10m"),
                (int) numberValue(section(forecast, "current"), "weather_code"),
                numberArray(section(forecast, "hourly"), "temperature_2m"),
                numberArray(section(forecast, "hourly"), "relative_humidity_2m"),
                numberArray(section(forecast, "hourly"), "wind_speed_10m"),
                intArray(section(forecast, "hourly"), "weather_code"),
                stringArray(section(forecast, "daily"), "time"),
                numberArray(section(forecast, "daily"), "temperature_2m_max"),
                numberArray(section(forecast, "daily"), "temperature_2m_min"),
                intArray(section(forecast, "daily"), "weather_code"));
    }

    private static String get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Weather service returned " + response.statusCode());
        return response.body();
    }

    private static void cacheForecast(Location location, String forecast) {
        try {
            Path file = cacheFile();
            Files.createDirectories(file.getParent());
            com.fasterxml.jackson.databind.node.ObjectNode cache = JSON.createObjectNode();
            cache.put("latitude", location.latitude);
            cache.put("longitude", location.longitude);
            cache.put("savedAt", System.currentTimeMillis());
            cache.put("forecast", forecast);
            JSON.writeValue(file.toFile(), cache);
        } catch (Exception ignored) {
            // Weather remains usable even when the optional offline cache cannot be written.
        }
    }

    private static String cachedForecast(Location location) throws Exception {
        JsonNode cache = JSON.readTree(cacheFile().toFile());
        if (Math.abs(cache.path("latitude").asDouble() - location.latitude) > 0.001
                || Math.abs(cache.path("longitude").asDouble() - location.longitude) > 0.001) {
            throw new IllegalArgumentException("Cached location does not match");
        }
        String forecast = cache.path("forecast").asText();
        if (forecast.isBlank()) throw new IllegalArgumentException("Empty weather cache");
        return forecast;
    }

    private static Path cacheFile() {
        return appDataDirectory().resolve("weather-cache.json");
    }

    private static Path appDataDirectory() {
        return AppStorage.root();
    }

    private static Location storedLocation() {
        if (!SETTINGS.getBoolean("weather.configured", false)) return null;
        String name = SETTINGS.get("weather.city", "").trim();
        if (name.isEmpty()) return null;
        return new Location(name, SETTINGS.get("weather.display", name),
                SETTINGS.getDouble("weather.latitude", Double.NaN),
                SETTINGS.getDouble("weather.longitude", Double.NaN),
                SETTINGS.get("weather.timezone", ZoneId.systemDefault().getId()));
    }

    private static boolean defaultFahrenheit() {
        String country = Locale.getDefault().getCountry();
        return country.equalsIgnoreCase("US") || country.equalsIgnoreCase("BS")
                || country.equalsIgnoreCase("BZ") || country.equalsIgnoreCase("KY")
                || country.equalsIgnoreCase("PW") || country.equalsIgnoreCase("FM")
                || country.equalsIgnoreCase("MH");
    }

    private void updateView() {
        if (data == null) return;
        int localHour;
        try {
            localHour = ZonedDateTime.now(ZoneId.of(selectedLocation.timezone)).getHour();
        } catch (Exception exception) {
            localHour = LocalTime.now().getHour();
        }
        boolean night = localHour >= 21 || localHour < 6;
        current.setIcon(new WeatherIcon(data.currentCode, night, 30, 25));
        current.setIconTextGap(7);
        current.setText(formatTemp(data.currentTemp));
        int statusIndex = Math.min(selectedDay * 24 + 12, data.hourly.length - 1);
        int statusCode = selectedDay == 0 ? data.currentCode : data.hourlyCodes[statusIndex];
        int humidity = selectedDay == 0 ? data.humidity : (int) Math.round(data.hourlyHumidity[statusIndex]);
        long wind = Math.round(selectedDay == 0 ? data.wind : data.hourlyWind[statusIndex]);
        double displayedWind = fahrenheit ? wind * 0.621371 : wind;
        condition.setText(description(statusCode) + " · "
                + "Humidity " + humidity
                + "% · Wind " + Math.round(displayedWind)
                + (fahrenheit ? " mph" : " km/h"));
        days.removeAll();
        for (int i = 0; i < Math.min(7, data.dates.length); i++) {
            final int day = i;
            LocalDate date = LocalDate.parse(data.dates[i]);
            JPanel button = new JPanel(new BorderLayout(0, 0));
            button.setBackground(AssistantTheme.PANEL_ALT);
            JLabel dayLabel = new JLabel(date.format(
                    DateTimeFormatter.ofPattern("EE", Locale.getDefault())), JLabel.CENTER);
            JLabel iconLabel = new JLabel("", JLabel.CENTER);
            int code = dayCode(i);
            iconLabel.setIcon(new WeatherIcon(code, false, 27, 21));
            JLabel temperatures = new JLabel("<html>" + formatTemp(data.max[i])
                    + " <font color='#9ca7b8'>" + formatTemp(data.min[i]) + "</font></html>", JLabel.CENTER);
            button.add(dayLabel, BorderLayout.NORTH);
            button.add(iconLabel, BorderLayout.CENTER);
            button.add(temperatures, BorderLayout.SOUTH);
            button.setBorder(BorderFactory.createLineBorder(
                    i == selectedDay ? AssistantTheme.ACCENT : AssistantTheme.BORDER));
            MouseAdapter selection = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    selectedDay = day;
                    updateView();
                }
            };
            button.addMouseListener(selection);
            dayLabel.addMouseListener(selection);
            iconLabel.addMouseListener(selection);
            temperatures.addMouseListener(selection);
            days.add(button);
        }
        days.revalidate();
        graph.repaint();
    }

    private int dayCode(int day) {
        int start = day * 24;
        int end = Math.min(start + 20, data.hourlyCodes.length - 1);
        boolean rain = false;
        boolean snow = false;
        for (int hour = start + 8; hour <= end; hour++) {
            int code = data.hourlyCodes[hour];
            if (code >= 95) return code;
            if (code >= 71 && code <= 77 || code >= 85 && code <= 86) snow = true;
            if (code >= 51 && code <= 67 || code >= 80 && code <= 82) rain = true;
        }
        if (snow) return 73;
        if (rain) return 63;
        int midday = start + 12;
        return midday < data.hourlyCodes.length ? data.hourlyCodes[midday] : data.dailyCodes[day];
    }

    private String formatTemp(double celsius) {
        double value = fahrenheit ? celsius * 9 / 5 + 32 : celsius;
        return Math.round(value) + "°";
    }

    private static String description(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code <= 48) return "Cloudy";
        if (code <= 67 || code >= 80 && code <= 82) return "Rain";
        if (code <= 77 || code >= 85 && code <= 86) return "Snow";
        return "Thunderstorm";
    }

    private static final class PencilIcon implements Icon {
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }

        @Override public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(AssistantTheme.TEXT);
            g.drawLine(x + 3, y + 11, x + 11, y + 3);
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(x + 2, y + 12, x + 5, y + 11);
            g.setColor(AssistantTheme.ACCENT);
            g.drawLine(x + 10, y + 2, x + 12, y + 4);
            g.dispose();
        }
    }

    /** Font-independent colored weather symbols rendered directly with Java2D. */
    private static final class WeatherIcon implements Icon {
        private final int code;
        private final boolean night;
        private final int width;
        private final int height;

        WeatherIcon(int code, boolean night, int width, int height) {
            this.code = code;
            this.night = night;
            this.width = width;
            this.height = height;
        }

        @Override public int getIconWidth() { return width; }
        @Override public int getIconHeight() { return height; }

        @Override public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = Math.min(width / 30.0, height / 24.0);
            g.translate(x, y);
            g.scale(scale, scale);

            if (night) {
                g.setColor(new Color(76, 145, 255));
                g.fillOval(5, 1, 18, 21);
                g.setColor(AssistantTheme.PANEL);
                g.fillOval(12, -2, 17, 19);
                g.dispose();
                return;
            }

            boolean partly = code == 1 || code == 2;
            boolean cloudy = code >= 3 && code <= 48;
            boolean rain = code >= 51 && code <= 67 || code >= 80 && code <= 82;
            boolean snow = code >= 71 && code <= 77 || code >= 85 && code <= 86;
            boolean storm = code >= 95;

            if (code == 0 || partly) drawSun(g, partly ? 5 : 9, partly ? 1 : 3, partly ? 12 : 14);
            if (partly || cloudy || rain || snow || storm) drawCloud(g, partly ? 9 : 4, partly ? 8 : 5,
                    storm ? new Color(91, 101, 116) : new Color(190, 200, 213));
            if (rain || storm) {
                g.setColor(new Color(66, 153, 245));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(9, 18, 7, 22);
                g.drawLine(16, 18, 14, 22);
                if (rain) g.drawLine(23, 18, 21, 22);
            }
            if (snow) {
                g.setColor(new Color(210, 239, 255));
                g.setFont(new Font("Verdana", Font.BOLD, 12));
                g.drawString("*", 8, 23);
                g.drawString("*", 18, 23);
            }
            if (storm) {
                g.setColor(new Color(255, 181, 35));
                java.awt.Polygon bolt = new java.awt.Polygon(
                        new int[] { 16, 12, 16, 13, 21, 17 },
                        new int[] { 15, 21, 21, 25, 19, 19 }, 6);
                g.fillPolygon(bolt);
            }
            g.dispose();
        }

        private static void drawSun(Graphics2D g, int x, int y, int size) {
            g.setColor(new Color(255, 177, 22));
            g.fillOval(x, y, size, size);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int centerX = x + size / 2, centerY = y + size / 2;
            int inner = size / 2 + 2, outer = inner + 2;
            for (int ray = 0; ray < 8; ray++) {
                double angle = ray * Math.PI / 4;
                g.drawLine(centerX + (int) (Math.cos(angle) * inner),
                        centerY + (int) (Math.sin(angle) * inner),
                        centerX + (int) (Math.cos(angle) * outer),
                        centerY + (int) (Math.sin(angle) * outer));
            }
        }

        private static void drawCloud(Graphics2D g, int x, int y, Color color) {
            g.setColor(color);
            g.fillOval(x, y + 5, 22, 9);
            g.fillOval(x + 3, y + 2, 10, 11);
            g.fillOval(x + 10, y, 12, 14);
            g.fillRect(x + 3, y + 8, 19, 7);
        }
    }

    private final class ForecastGraph extends JPanel {
        ForecastGraph() {
            setOpaque(false);
            setPreferredSize(new Dimension(315, 62));
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (data == null || data.hourly.length < 24) return;
            int start = Math.min(selectedDay * 24, data.hourly.length - 24);
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (int i = start; i < start + 24; i++) {
                min = Math.min(min, data.hourly[i]);
                max = Math.max(max, data.hourly[i]);
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(255, 196, 0, 45));
            java.awt.geom.Path2D area = new java.awt.geom.Path2D.Double();
            java.awt.geom.Path2D line = new java.awt.geom.Path2D.Double();
            for (int hour = 0; hour < 24; hour++) {
                int x = 8 + hour * (getWidth() - 16) / 23;
                int y = 18 + (int) ((max - data.hourly[start + hour]) / Math.max(1, max - min) * (getHeight() - 40));
                if (hour == 0) { line.moveTo(x, y); area.moveTo(x, getHeight() - 16); area.lineTo(x, y); }
                else { line.lineTo(x, y); area.lineTo(x, y); }
            }
            area.lineTo(getWidth() - 8, getHeight() - 16);
            area.closePath();
            g.fill(area);
            g.setColor(new Color(255, 196, 0));
            g.setStroke(new BasicStroke(2f));
            g.draw(line);
            g.setColor(AssistantTheme.MUTED);
            g.setFont(g.getFont().deriveFont(9f));
            for (int hour = 0; hour <= 24; hour += 6) {
                int x = 8 + hour * (getWidth() - 16) / 24;
                String time = String.format("%02d:00", hour);
                int textWidth = g.getFontMetrics().stringWidth(time);
                int textX = hour == 0 ? 0 : hour == 24 ? getWidth() - textWidth : x - textWidth / 2;
                g.drawString(time, textX, getHeight() - 2);
            }
            g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
            for (int hour = 0; hour <= 24; hour += 4) {
                int sampleHour = Math.min(hour, 23);
                int x = 8 + hour * (getWidth() - 16) / 24;
                int y = 18 + (int) ((max - data.hourly[start + sampleHour])
                        / Math.max(1, max - min) * (getHeight() - 40));
                String value = Long.toString(Math.round(fahrenheit
                        ? data.hourly[start + sampleHour] * 9 / 5 + 32
                        : data.hourly[start + sampleHour]));
                int valueWidth = g.getFontMetrics().stringWidth(value);
                int valueX = Math.max(0, Math.min(getWidth() - valueWidth, x - valueWidth / 2));
                g.drawString(value, valueX, Math.max(9, y - 4));
            }
            g.dispose();
        }
    }

    private static String section(String json, String key) {
        Matcher start = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{").matcher(json);
        if (!start.find()) throw new IllegalArgumentException("Missing " + key);
        int depth = 1;
        for (int i = start.end(); i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            if (json.charAt(i) == '}' && --depth == 0) return json.substring(start.end(), i);
        }
        throw new IllegalArgumentException("Invalid weather response");
    }

    private static String stringValue(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Unknown city");
        return matcher.group(1);
    }

    private static double numberValue(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Missing " + key);
        return Double.parseDouble(matcher.group(1));
    }

    private static double[] numberArray(String json, String key) {
        String[] values = array(json, key).split(",");
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Double.parseDouble(values[i].trim());
        return result;
    }

    private static int[] intArray(String json, String key) {
        double[] values = numberArray(json, key);
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (int) values[i];
        return result;
    }

    private static String[] stringArray(String json, String key) {
        String[] values = array(json, key).split(",");
        for (int i = 0; i < values.length; i++) values[i] = values[i].trim().replace("\"", "");
        return values;
    }

    private static String array(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^]]*)]").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Missing " + key);
        return matcher.group(1);
    }

    private static final class WeatherSettings {
        private final Path file = appDataDirectory().resolve("weather-settings.json");
        private final com.fasterxml.jackson.databind.node.ObjectNode values = load();

        private com.fasterxml.jackson.databind.node.ObjectNode load() {
            try {
                JsonNode root = JSON.readTree(file.toFile());
                if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode object) return object;
            } catch (Exception ignored) { }
            return JSON.createObjectNode();
        }

        synchronized String get(String key, String fallback) {
            return values.has(key) ? values.path(key).asText(fallback) : fallback;
        }

        synchronized boolean getBoolean(String key, boolean fallback) {
            return values.has(key) ? values.path(key).asBoolean(fallback) : fallback;
        }

        synchronized double getDouble(String key, double fallback) {
            return values.has(key) ? values.path(key).asDouble(fallback) : fallback;
        }

        synchronized void put(String key, String value) {
            values.put(key, value);
            save();
        }

        synchronized void putBoolean(String key, boolean value) {
            values.put(key, value);
            save();
        }

        synchronized void putDouble(String key, double value) {
            values.put(key, value);
            save();
        }

        private void save() {
            try {
                Files.createDirectories(file.getParent());
                JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), values);
            } catch (Exception ignored) {
                // The active session remains usable when settings cannot be persisted.
            }
        }
    }

    private record Location(String name, String display, double latitude, double longitude, String timezone) {
        @Override public String toString() { return display; }
    }

    private record WeatherData(String city, double currentTemp, int humidity, double wind, int currentCode,
                               double[] hourly, double[] hourlyHumidity, double[] hourlyWind,
                               int[] hourlyCodes, String[] dates,
                               double[] max, double[] min, int[] dailyCodes) { }
}

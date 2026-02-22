package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles gateway log retrieval from Ignition's system_logs.idb SQLite database.
 * URL: /data/camera-driver/logs/gateway
 */
public class GatewayLogHandler extends BaseHandler {

    private static final int DEFAULT_LOG_LINES = 100;
    private static final int MAX_LOG_LINES = 500;
    private static final String CAMERA_DRIVER_LOGGER_PREFIX = "com.onvif.driver";
    private static final DateTimeFormatter LOG_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public GatewayLogHandler(GatewayContext context,
                              ONVIFDeviceExtensionPoint deviceExtensionPoint,
                              GenericCameraExtensionPoint genericCameraExtensionPoint,
                              Go2RtcManager go2RtcManager,
                              AuthenticationManager authManager,
                              String moduleVersion) {
        super(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    /**
     * Handles gateway log requests by reading from Ignition's system_logs.idb SQLite database.
     * URL: http://gateway:8088/data/camera-driver/logs/gateway
     *
     * Query params:
     *   - lines: Number of entries (default 100, max 500)
     *   - moduleOnly: If "true", filter by com.onvif.driver logger prefix (default true)
     *   - after: Only return entries after this event ID (for polling)
     *   - level: Comma-separated log levels (ERROR,WARN,INFO)
     */
    public Object handleGatewayLogs(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Gateway logs request received");

        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try {
            JSONObject result = new JSONObject();

            // Parse parameters
            int maxLines = parseLogLines(requestContext.getRequest().getParameter("lines"));
            String moduleOnlyParam = requestContext.getRequest().getParameter("moduleOnly");
            boolean moduleOnly = moduleOnlyParam == null || !"false".equalsIgnoreCase(moduleOnlyParam);
            long afterEventId = parseAfterEventId(requestContext.getRequest().getParameter("after"));
            Set<String> levelFilter = parseLevelFilter(requestContext.getRequest().getParameter("level"));

            // Find system_logs.idb
            File logDb = findSystemLogsDb();
            if (logDb == null || !logDb.exists() || !logDb.canRead()) {
                result.put("success", false);
                result.put("error", "system_logs.idb not found");
                response.setContentType("application/json");
                response.setStatus(404);
                response.getWriter().write(result.toString());
                return null;
            }

            // Read entries from SQLite
            JSONArray entries = readGatewayLogEntries(logDb, maxLines, moduleOnly, afterEventId, levelFilter);

            result.put("success", true);
            result.put("entries", entries);
            result.put("hasMore", entries.length() >= maxLines);

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error retrieving gateway logs", e);
            response.sendError(500, "Internal server error");
        }

        return null;
    }

    private JSONArray readGatewayLogEntries(File logDb, int maxLines, boolean moduleOnly,
                                             long afterEventId, Set<String> levelFilter) {
        JSONArray entries = new JSONArray();
        String url = "jdbc:sqlite:" + logDb.getAbsolutePath();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT event_id, timestmp, formatted_message, logger_name, level_string ");
        sql.append("FROM logging_event WHERE 1=1 ");

        List<Object> params = new ArrayList<>();

        if (afterEventId > 0) {
            sql.append("AND event_id > ? ");
            params.add(afterEventId);
        }

        if (!levelFilter.isEmpty()) {
            sql.append("AND level_string IN (");
            sql.append(String.join(",", Collections.nCopies(levelFilter.size(), "?")));
            sql.append(") ");
            params.addAll(levelFilter);
        }

        if (moduleOnly) {
            sql.append("AND logger_name LIKE ? ");
            params.add(CAMERA_DRIVER_LOGGER_PREFIX + "%");
        }

        sql.append("ORDER BY event_id DESC LIMIT ?");
        params.add(maxLines);

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Long) {
                    stmt.setLong(i + 1, (Long) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                } else {
                    stmt.setString(i + 1, param.toString());
                }
            }

            try (ResultSet rs = stmt.executeQuery()) {
                // Collect in reverse order (DESC query gives newest first)
                List<JSONObject> tempList = new ArrayList<>();
                while (rs.next()) {
                    try {
                        JSONObject entry = new JSONObject();
                        long eventId = rs.getLong("event_id");
                        long timestmp = rs.getLong("timestmp");
                        String message = rs.getString("formatted_message");
                        String loggerName = rs.getString("logger_name");
                        String level = rs.getString("level_string");

                        String formattedTime = LOG_DATE_FORMAT.format(java.time.Instant.ofEpochMilli(timestmp));

                        // Shorten logger name for display (last segment)
                        String source = loggerName;
                        if (loggerName != null && loggerName.contains(".")) {
                            source = loggerName.substring(loggerName.lastIndexOf('.') + 1);
                        }

                        entry.put("id", eventId);
                        entry.put("timestamp", formattedTime);
                        entry.put("level", level);
                        entry.put("source", source);
                        entry.put("logger", loggerName);
                        entry.put("message", message);
                        tempList.add(entry);
                    } catch (Exception e) {
                        logger.debug("Error parsing log entry: {}", e.getMessage());
                    }
                }

                // Reverse to chronological order (oldest first)
                Collections.reverse(tempList);
                for (JSONObject entry : tempList) {
                    entries.put(entry);
                }
            }
        } catch (SQLException e) {
            logger.error("Error reading from system_logs.idb: {}", e.getMessage());
        }

        return entries;
    }

    private File findSystemLogsDb() {
        List<File> candidates = new ArrayList<>();

        // Primary: from GatewayContext (this.context is the GatewayContext field from BaseHandler)
        try {
            File logsDir = this.context.getSystemManager().getLogsDir();
            if (logsDir != null) {
                candidates.add(new File(logsDir, "system_logs.idb"));
            }
        } catch (Exception e) {
            logger.debug("Could not get logsDir from GatewayContext: {}", e.getMessage());
        }

        // Common Ignition installation paths
        candidates.add(new File("/usr/local/bin/ignition/logs/system_logs.idb"));
        candidates.add(new File("/var/lib/ignition/logs/system_logs.idb"));
        candidates.add(new File("C:/Program Files/Inductive Automation/Ignition/logs/system_logs.idb"));

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canRead()) {
                return candidate;
            }
        }
        return null;
    }

    private int parseLogLines(String linesParam) {
        if (linesParam == null || linesParam.isEmpty()) return DEFAULT_LOG_LINES;
        try {
            int lines = Integer.parseInt(linesParam);
            return Math.max(1, Math.min(lines, MAX_LOG_LINES));
        } catch (NumberFormatException e) {
            return DEFAULT_LOG_LINES;
        }
    }

    private long parseAfterEventId(String afterParam) {
        if (afterParam == null || afterParam.isEmpty()) return 0;
        try {
            return Long.parseLong(afterParam);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Set<String> parseLevelFilter(String levelParam) {
        Set<String> filter = new HashSet<>();
        if (levelParam != null && !levelParam.isEmpty()) {
            for (String level : levelParam.split(",")) {
                String trimmed = level.trim().toUpperCase();
                if (!trimmed.isEmpty()) filter.add(trimmed);
            }
        }
        return filter;
    }
}

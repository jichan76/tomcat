package com.example.attendance.servlet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@WebServlet("/attendanceStatusList")
public class AttendanceStatusListServlet extends HttpServlet {

    private static final Semaphore GUARD = new Semaphore(12, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    /** 클라/서버 표현 통일 */
    private static String normalizeStatus(String raw) {
        if (raw == null) return "all";
        String s = raw.trim();
        if (s.isEmpty()) return "all";
        switch (s) {
            // 영어 → 한글
            case "present":   return "출석";
            case "late":
            case "late_leave":return "지각_조퇴";
            case "absent":    return "결석";
            case "unchecked":
            case "none":
            case "미체크":     return "미체크";
            // 한글은 그대로
            case "출석":
            case "지각_조퇴":
            case "결석":
                return s;
            default:
                return "all";
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean ok = false;
        try {
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) { response.setStatus(503); response.getWriter().write("{\"result\":\"busy\"}"); return; }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");

            // 필수 파라미터
            String subjectIdStr = request.getParameter("subjectId");
            String weekStr      = request.getParameter("week");
            // status는 여러 이름 허용
            String statusRaw    = coalesce(
                    request.getParameter("status"),
                    request.getParameter("type"),
                    request.getParameter("filter")
            );
            String q            = request.getParameter("q");

            if (subjectIdStr == null || subjectIdStr.isEmpty()) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"subjectId 누락\"}");
                return;
            }
            if (weekStr == null || weekStr.isEmpty()) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"week 누락\"}");
                return;
            }

            int subjectId, week;
            try { subjectId = Integer.parseInt(subjectIdStr); }
            catch (NumberFormatException e) { response.setStatus(400); response.getWriter().write("{\"error\":\"subjectId 숫자 아님\"}"); return; }
            try { week = Integer.parseInt(weekStr); }
            catch (NumberFormatException e) { response.setStatus(400); response.getWriter().write("{\"error\":\"week 숫자 아님\"}"); return; }
            if (week < 1 || week > 16) { response.setStatus(400); response.getWriter().write("{\"error\":\"week는 1~16 범위여야 함\"}"); return; }

            final String normalized = normalizeStatus(statusRaw);
            final boolean hasQuery = (q != null && !q.trim().isEmpty());
            final String LIKE_L = hasQuery ? "%" + q.trim().toLowerCase() + "%" : null;
            final String LIKE_R = hasQuery ? "%" + q.trim() + "%" : null;

            // 최신(해당 주차) 1건
            final String BASE_SQL =
                    "WITH latest AS ( " +
                            "  SELECT ar.student_id, ar.status, ar.attendance_datetime, " +
                            "         ROW_NUMBER() OVER (PARTITION BY ar.student_id ORDER BY ar.attendance_datetime DESC) rn " +
                            "    FROM attendance_records ar " +
                            "   WHERE ar.subject_id = ? AND ar.week_number = ? " +
                            ") " +
                            "SELECT u.user_id AS student_id, u.name, l.status, l.attendance_datetime AS last_time " +
                            "  FROM enrollments e " +
                            "  JOIN users u ON u.user_id = e.student_id AND u.role = 'student' " +
                            "  LEFT JOIN (SELECT * FROM latest WHERE rn = 1) l ON l.student_id = e.student_id " +
                            " WHERE e.subject_id = ? ";

            StringBuilder sql = new StringBuilder(BASE_SQL);
            if (hasQuery) {
                sql.append(" AND (LOWER(u.name) LIKE ? OR u.user_id LIKE ?) ");
            }
            if ("출석".equals(normalized) || "지각_조퇴".equals(normalized) || "결석".equals(normalized)) {
                sql.append(" AND l.status = ? ");
            } else if ("미체크".equals(normalized)) {
                sql.append(" AND l.status IS NULL ");
            }
            sql.append(" ORDER BY u.name ASC ");

            // 카운트(칩 숫자)
            final String COUNT_SQL =
                    "WITH latest AS ( " +
                            "  SELECT ar.student_id, ar.status, " +
                            "         ROW_NUMBER() OVER (PARTITION BY ar.student_id ORDER BY ar.attendance_datetime DESC) rn " +
                            "    FROM attendance_records ar " +
                            "   WHERE ar.subject_id = ? AND ar.week_number = ? " +
                            ") " +
                            "SELECT " +
                            "  SUM(CASE WHEN l.status = '출석' THEN 1 ELSE 0 END) AS present_cnt, " +
                            "  SUM(CASE WHEN l.status = '지각_조퇴' THEN 1 ELSE 0 END) AS late_cnt, " +
                            "  SUM(CASE WHEN l.status = '결석' THEN 1 ELSE 0 END) AS absent_cnt, " +
                            "  SUM(CASE WHEN l.status IS NULL THEN 1 ELSE 0 END) AS unchecked_cnt, " +
                            "  COUNT(*) AS total_cnt " +
                            "FROM enrollments e " +
                            "JOIN users u ON u.user_id = e.student_id AND u.role = 'student' " +
                            "LEFT JOIN (SELECT * FROM latest WHERE rn = 1) l ON l.student_id = e.student_id " +
                            "WHERE e.subject_id = ? " +
                            (hasQuery ? " AND (LOWER(u.name) LIKE ? OR u.user_id LIKE ?) " : "");

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString());
                 PreparedStatement cps = conn.prepareStatement(COUNT_SQL)) {

                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                cps.setQueryTimeout(SQL_TIMEOUT_SEC);

                int idx = 1;
                ps.setInt(idx++, subjectId);
                ps.setInt(idx++, week);
                ps.setInt(idx++, subjectId);
                if (hasQuery) {
                    ps.setString(idx++, LIKE_L);
                    ps.setString(idx++, LIKE_R);
                }
                if ("출석".equals(normalized) || "지각_조퇴".equals(normalized) || "결석".equals(normalized)) {
                    ps.setString(idx++, normalized);
                }

                int cidx = 1;
                cps.setInt(cidx++, subjectId);
                cps.setInt(cidx++, week);
                cps.setInt(cidx++, subjectId);
                if (hasQuery) {
                    cps.setString(cidx++, LIKE_L);
                    cps.setString(cidx++, LIKE_R);
                }

                JsonArray students = new JsonArray();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("studentId", rs.getString("student_id"));
                        o.addProperty("name", rs.getString("name"));
                        String status = rs.getString("status");
                        o.addProperty("status", status == null ? "미체크" : status);
                        Timestamp ts = rs.getTimestamp("last_time");
                        if (ts != null) o.addProperty("lastTime", ts.toString());
                        o.addProperty("week", week);
                        students.add(o);
                    }
                }

                JsonObject counts = new JsonObject();
                try (ResultSet crs = cps.executeQuery()) {
                    if (crs.next()) {
                        counts.addProperty("출석",      crs.getInt("present_cnt"));
                        counts.addProperty("지각_조퇴", crs.getInt("late_cnt"));
                        counts.addProperty("결석",      crs.getInt("absent_cnt"));
                        counts.addProperty("미체크",    crs.getInt("unchecked_cnt"));
                        counts.addProperty("총원",      crs.getInt("total_cnt"));
                    }
                }

                JsonObject root = new JsonObject();
                root.addProperty("subjectId", subjectId);
                root.addProperty("week", week);
                root.addProperty("status", normalized);
                // 디버그 에코(클라 로그에서 즉시 확인)
                JsonObject echo = new JsonObject();
                echo.addProperty("receivedStatusParam", statusRaw == null ? "" : statusRaw);
                echo.addProperty("normalized", normalized);
                root.add("echo", echo);

                root.add("counts", counts);
                root.add("students", students);

                String json = new Gson().toJson(root);
                PrintWriter out = response.getWriter();
                out.print(json);
                out.flush();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); response.setStatus(503);
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter()
                    .write("{\"error\":\"서버 오류: " + (e.getMessage()==null? "" : e.getMessage().replace("\"","\\\"")) + "\"}");
        } finally {
            if (ok) GUARD.release();
        }
    }

    private static String coalesce(String... ss){
        for (String s: ss) if (s != null && !s.trim().isEmpty()) return s;
        return null;
    }
}

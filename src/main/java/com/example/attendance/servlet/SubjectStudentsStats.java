// app/src/main/java/com/example/attendance/servlet/SubjectStudentsStats.java
package com.example.attendance.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@WebServlet("/SubjectStudentsStats")
public class SubjectStudentsStats extends HttpServlet {

    // 동시성/타임아웃 파라미터(다른 서블릿과 동일 톤)
    private static final Semaphore GUARD = new Semaphore(16, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC    = 8;
    private static final int DB_CONNECT_TIMEOUT = 8000;
    private static final int DB_READ_TIMEOUT    = 8000;

    // DB 접속 (다른 서블릿과 동일)
    static {
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            DriverManager.setLoginTimeout(DB_CONNECT_TIMEOUT / 1000);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private Connection getConnection() throws SQLException {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_READ_TIMEOUT));
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        boolean ok = false;
        try {
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) {
                resp.setStatus(503);
                resp.getWriter().write("{\"result\":\"busy\"}");
                return;
            }

            req.setCharacterEncoding("UTF-8");
            resp.setContentType("application/json; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            resp.setHeader("Pragma", "no-cache");

            // --- 파라미터 파싱 ---
            String sid = req.getParameter("subjectId");
            if (sid == null || sid.trim().isEmpty()) {
                resp.setStatus(400);
                resp.getWriter().write("{\"result\":\"error\",\"message\":\"subjectId required\"}");
                return;
            }
            int subjectId;
            try {
                subjectId = Integer.parseInt(sid.trim());
            } catch (NumberFormatException nfe) {
                resp.setStatus(400);
                resp.getWriter().write("{\"result\":\"error\",\"message\":\"subjectId must be number\"}");
                return;
            }

            String qRaw  = req.getParameter("q");
            String q     = (qRaw == null) ? "" : qRaw.trim();
            String pg    = req.getParameter("page");
            String sz    = req.getParameter("size");

            int page = 1;
            int size = 50;
            try { if (pg != null) page = Math.max(1, Integer.parseInt(pg)); } catch (Exception ignore) {}
            try { if (sz != null) size = Math.max(1, Math.min(200, Integer.parseInt(sz))); } catch (Exception ignore) {}

            int startRow = (page - 1) * size + 1;
            int endRow   = startRow + size - 1;

            final boolean hasQuery = (q != null && !q.isEmpty());
            final String LIKE_L = hasQuery ? "%" + q.toLowerCase() + "%" : null;
            final String LIKE_R = hasQuery ? "%" + q + "%" : null;

            // --- COUNT SQL (검색 포함) ---
            final String COUNT_SQL =
                    "SELECT COUNT(*) " +
                            "  FROM enrollments e " +
                            "  JOIN users u ON u.user_id = e.student_id AND u.role = 'student' " +
                            " WHERE e.subject_id = ? " +
                            (hasQuery ? " AND (LOWER(u.name) LIKE ? OR u.user_id LIKE ?) " : "");

            // --- LIST SQL (ROW_NUMBER로 페이징) ---
            final String LIST_SQL =
                    "WITH W AS ( " +
                            "  SELECT ar.student_id, ar.week_number, ar.status, " +
                            "         ROW_NUMBER() OVER (PARTITION BY ar.student_id, ar.week_number ORDER BY ar.attendance_datetime DESC) rn " +
                            "    FROM attendance_records ar " +
                            "   WHERE ar.subject_id = ? AND ar.week_number BETWEEN 1 AND 16 " +
                            "), LST AS ( " +
                            "  SELECT student_id, week_number, status FROM W WHERE rn = 1 " +
                            "), AGG AS ( " +
                            "  SELECT u.user_id AS student_id, u.name, " +
                            "         SUM(CASE WHEN LST.status = '출석' THEN 1 ELSE 0 END) AS present_cnt, " +
                            "         SUM(CASE WHEN LST.status = '지각_조퇴' THEN 1 ELSE 0 END) AS late_cnt, " +
                            "         SUM(CASE WHEN LST.status = '결석' THEN 1 ELSE 0 END) AS absent_cnt " +
                            "    FROM enrollments e " +
                            "    JOIN users u ON u.user_id = e.student_id AND u.role = 'student' " +
                            "    LEFT JOIN LST ON LST.student_id = e.student_id " +
                            "   WHERE e.subject_id = ? " +
                            (hasQuery ? " AND (LOWER(u.name) LIKE ? OR u.user_id LIKE ?) " : "") +
                            "   GROUP BY u.user_id, u.name " +
                            ") " +
                            "SELECT * FROM ( " +
                            "  SELECT AGG.*, ROW_NUMBER() OVER (ORDER BY AGG.name ASC) AS rn FROM AGG " +
                            ") WHERE rn BETWEEN ? AND ?";

            JsonObject root = new JsonObject();
            root.addProperty("result", "success");
            root.addProperty("subjectId", subjectId);

            try (Connection conn = getConnection()) {

                // 1) total
                int total = 0;
                try (PreparedStatement cps = conn.prepareStatement(COUNT_SQL)) {
                    cps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    int cidx = 1;
                    cps.setInt(cidx++, subjectId);
                    if (hasQuery) {
                        cps.setString(cidx++, LIKE_L);
                        cps.setString(cidx++, LIKE_R);
                    }
                    try (ResultSet crs = cps.executeQuery()) {
                        if (crs.next()) total = crs.getInt(1);
                    }
                }

                root.addProperty("total", total);

                // 학생이 0이면 빈 배열 반환
                JsonArray arr = new JsonArray();
                if (total > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(LIST_SQL)) {
                        ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                        int idx = 1;
                        ps.setInt(idx++, subjectId);  // W
                        ps.setInt(idx++, subjectId);  // AGG
                        if (hasQuery) {
                            ps.setString(idx++, LIKE_L);
                            ps.setString(idx++, LIKE_R);
                        }
                        ps.setInt(idx++, startRow);
                        ps.setInt(idx++, endRow);

                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                JsonObject o = new JsonObject();
                                o.addProperty("studentId", rs.getString("student_id"));
                                o.addProperty("name", rs.getString("name"));
                                o.addProperty("present", rs.getInt("present_cnt"));
                                o.addProperty("late",    rs.getInt("late_cnt"));
                                o.addProperty("absent",  rs.getInt("absent_cnt"));
                                arr.add(o);
                            }
                        }
                    }
                }

                root.add("students", arr);

                String json = new Gson().toJson(root);
                PrintWriter out = resp.getWriter();
                out.print(json);
                out.flush();
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            resp.setStatus(503);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                resp.setStatus(500);
                JsonObject err = new JsonObject();
                err.addProperty("result", "error");
                err.addProperty("message", e.getMessage() == null ? "server" : e.getMessage());
                resp.getWriter().print(new Gson().toJson(err));
            } catch (IOException ignore) {}
        } finally {
            if (ok) GUARD.release();
        }
    }
}

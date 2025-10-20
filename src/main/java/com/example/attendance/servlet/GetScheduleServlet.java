package com.example.attendance.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@WebServlet("/GetScheduleServlet")
public class GetScheduleServlet extends HttpServlet {

    // ───────────────────── 동시 접근 제어 및 DB 연결 기본값 ─────────────────────
    private static final int MAX_CONCURRENT = 16;
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS = 8000;
    private static final Semaphore GUARD = new Semaphore(MAX_CONCURRENT, true);

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT", String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");
        Connection conn = DriverManager.getConnection(url, p);

        try (Statement st = conn.createStatement()) {
            try { st.execute("ALTER SESSION DISABLE PARALLEL DML"); } catch (SQLException ignore) {}
            try { st.execute("ALTER SESSION DISABLE PARALLEL QUERY"); } catch (SQLException ignore) {}
            try { st.execute("ALTER SESSION SET parallel_degree_policy = MANUAL"); } catch (SQLException ignore) {}
            try { st.execute("ALTER SESSION SET parallel_degree_limit = 1"); } catch (SQLException ignore) {}
        }
        return conn;
    }

    private void setJson(HttpServletResponse resp) {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
    }

    // ───────────────────── GET 요청 (수업 일정 조회) ─────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        setJson(resp);
        boolean acquired = false;
        boolean wrote = false;
        PrintWriter out = resp.getWriter();

        try {
            acquired = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                resp.setStatus(503);
                out.write("{\"result\":\"busy\"}");
                out.flush(); resp.flushBuffer();
                wrote = true;
                return;
            }

            req.setCharacterEncoding("UTF-8");

            // 1️⃣ subject_id 확인
            String subjectIdStr = trim(req.getParameter("subject_id"));
            if (subjectIdStr == null) {
                resp.setStatus(400);
                out.write("{\"result\":\"error\",\"message\":\"Missing subject_id\"}");
                out.flush(); resp.flushBuffer();
                wrote = true;
                return;
            }

            int subjectId = Integer.parseInt(subjectIdStr);

            // 2️⃣ DB 조회
            JsonObject root = new JsonObject();
            JsonArray weeks = new JsonArray();

            try (Connection conn = getConnection()) {
                String sql = "SELECT WEEK_NUMBER, " +
                        "TO_CHAR(NVL(MAKEUP_DATE, ORIGINAL_DATE), 'YYYY-MM-DD') AS DISPLAY_DATE, " +
                        "TO_CHAR(ORIGINAL_DATE, 'YYYY-MM-DD') AS ORIGINAL_DATE, " +
                        "TO_CHAR(MAKEUP_DATE, 'YYYY-MM-DD') AS MAKEUP_DATE " +
                        "FROM CLASS_SCHEDULE " +
                        "WHERE SUBJECT_ID = ? " +
                        "ORDER BY WEEK_NUMBER";

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject w = new JsonObject();
                            w.addProperty("week", rs.getInt("WEEK_NUMBER"));
                            w.addProperty("displayDate", rs.getString("DISPLAY_DATE"));
                            w.addProperty("originalDate", rs.getString("ORIGINAL_DATE"));
                            w.addProperty("makeupDate", rs.getString("MAKEUP_DATE"));
                            weeks.add(w);
                        }
                    }
                }
            }

            root.addProperty("result", "success");
            root.add("weeks", weeks);

            out.print(root.toString());
            out.flush(); resp.flushBuffer();
            wrote = true;

        } catch (Exception e) {
            resp.setStatus(500);
            JsonObject err = new JsonObject();
            err.addProperty("result", "error");
            err.addProperty("message", e.getMessage());
            out.print(err.toString());
            out.flush(); resp.flushBuffer();
            wrote = true;

        } finally {
            if (acquired) GUARD.release();
            if (!resp.isCommitted() && !wrote) {
                resp.setStatus(500);
                out.write("{\"result\":\"error\",\"message\":\"empty response\"}");
                out.flush(); resp.flushBuffer();
            }
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        setJson(resp);
        resp.setStatus(405);
        try (PrintWriter out = resp.getWriter()) {
            out.write("{\"result\":\"fail\",\"message\":\"GET만 지원합니다.\"}");
            out.flush();
        }
    }
}

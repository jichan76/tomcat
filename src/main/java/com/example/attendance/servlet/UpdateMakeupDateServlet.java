package com.example.attendance.servlet;

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

@WebServlet("/UpdateMakeupDateServlet")
public class UpdateMakeupDateServlet extends HttpServlet {

    // ───────────────────── 동시 접근 제어 및 DB 연결 기본값 ─────────────────────
    private static final int MAX_CONCURRENT = 16;
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;
    private static final Semaphore GUARD = new Semaphore(MAX_CONCURRENT, true);

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_READ_TIMEOUT_MS));
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

    private void setJson(HttpServletResponse resp){
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
    }

    // ───────────────────── POST 요청 (보강날짜 업데이트) ─────────────────────
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        setJson(resp);
        boolean acquired = false;
        boolean wrote    = false;
        PrintWriter out  = resp.getWriter();

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

            // 1️⃣ 요청 파라미터 수신
            String subjectIdStr = trim(req.getParameter("subject_id"));
            String weekStr      = trim(req.getParameter("week_number"));
            String makeupDate   = trim(req.getParameter("makeup_date")); // YYYY-MM-DD

            if (subjectIdStr == null || weekStr == null || makeupDate == null) {
                resp.setStatus(400);
                out.print("{\"result\":\"error\",\"message\":\"Missing parameters\"}");
                out.flush(); resp.flushBuffer();
                wrote = true;
                return;
            }

            int subjectId = Integer.parseInt(subjectIdStr);
            int week      = Integer.parseInt(weekStr);

            // 2️⃣ DB 연결
            try (Connection conn = getConnection()) {
                final String SQL =
                        "UPDATE CLASS_SCHEDULE " +
                                "   SET MAKEUP_DATE = TO_DATE(?, 'YYYY-MM-DD') " +
                                " WHERE SUBJECT_ID = ? AND WEEK_NUMBER = ?";

                int updated;
                try (PreparedStatement ps = conn.prepareStatement(SQL)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setString(1, makeupDate);
                    ps.setInt(2, subjectId);
                    ps.setInt(3, week);
                    updated = ps.executeUpdate();
                }

                JsonObject json = new JsonObject();
                if (updated > 0) {
                    json.addProperty("result", "success");
                    json.addProperty("subjectId", subjectId);
                    json.addProperty("week", week);
                    json.addProperty("makeupDate", makeupDate);
                } else {
                    json.addProperty("result", "fail");
                    json.addProperty("message", "No matching record found.");
                }

                out.print(json.toString());
                out.flush(); resp.flushBuffer();
                wrote = true;
            }

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
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        setJson(resp);
        resp.setStatus(405);
        try (PrintWriter out = resp.getWriter()) {
            out.write("{\"result\":\"fail\",\"message\":\"POST만 지원합니다.\"}");
            out.flush();
        }
    }
}

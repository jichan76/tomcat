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

@WebServlet("/AttendanceServlet")
public class AttendanceServlet extends HttpServlet {

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
            try { st.execute("ALTER SESSION SET parallel_min_time_threshold = AUTO"); } catch (SQLException ignore) {}
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

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        setJson(resp);

        boolean acquired = false;
        boolean wrote    = false;

        // 한 번만 writer 열기
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

            String studentId = trim(req.getParameter("student_id"));
            String bssid     = trim(req.getParameter("bssid"));
            String androidId = trim(req.getParameter("android_id"));

            if (studentId == null || bssid == null || androidId == null) {
                resp.setStatus(400);
                out.print("{\"result\":\"error\",\"message\":\"Missing parameters\"}");
                out.flush(); resp.flushBuffer();
                wrote = true;
                return;
            }

            bssid = bssid.toLowerCase();

            try (Connection conn = getConnection()) {

                // ★ 1) 오픈 세션 조회: week_number/session_date까지 가져오기
                final String FIND_OPEN =
                        "SELECT a.open_id, a.subject_id, a.open_datetime, a.week_number, a.session_date " +
                                "  FROM attendance_open_log a " +
                                "  JOIN enrollments e ON e.subject_id = a.subject_id " +
                                " WHERE e.student_id = ? " +
                                "   AND LOWER(a.bssid) = ? " +
                                "   AND a.open_datetime <= SYSTIMESTAMP " +
                                "   AND (a.close_datetime IS NULL OR a.close_datetime >= SYSTIMESTAMP) " +
                                " ORDER BY a.open_datetime DESC " +
                                " FETCH FIRST 1 ROWS ONLY";

                Integer openId = null;
                Integer subjectId = null;
                Timestamp openDt = null;
                Integer week = null;                // ★ 주차

                try (PreparedStatement ps = conn.prepareStatement(FIND_OPEN)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setString(1, studentId);
                    ps.setString(2, bssid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            openId    = rs.getInt("open_id");
                            subjectId = rs.getInt("subject_id");
                            openDt    = rs.getTimestamp("open_datetime");
                            // week_number 컬럼에서 바로 사용
                            week      = (Integer) rs.getObject("week_number");
                        }
                    }
                }

                if (openId == null) {
                    out.print("{\"result\":\"fail\",\"message\":\"현재 출석이 열려 있지 않거나 BSSID가 일치하지 않습니다.\"}");
                    out.flush(); resp.flushBuffer();
                    wrote = true;
                    return;
                }

                // ★ 2) 레거시 데이터 보정: week_number가 NULL인 경우에만 계산(클램프)
                if (week == null) {
                    final String WEEK_SQL_CLAMP =
                            "SELECT LEAST(16, GREATEST(1, FLOOR((TRUNC(CAST(? AS DATE)) - si.start_date)/7)+1)) AS week " +
                                    "  FROM semester_info si FETCH FIRST 1 ROWS ONLY";
                    try (PreparedStatement ps = conn.prepareStatement(WEEK_SQL_CLAMP)) {
                        ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                        ps.setTimestamp(1, openDt);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) week = rs.getInt("week");
                        }
                    }
                    if (week == null) { // 이 경우는 거의 없음
                        out.print("{\"result\":\"fail\",\"message\":\"주차 계산 실패\"}");
                        out.flush(); resp.flushBuffer();
                        wrote = true;
                        return;
                    }
                }

                // 3) 안드로이드 기기 검증
                final String ANDROID_SQL =
                        "SELECT android_id FROM users WHERE user_id = ? AND role = 'student'";
                String dbAndroid = null;
                try (PreparedStatement ps = conn.prepareStatement(ANDROID_SQL)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setString(1, studentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) dbAndroid = rs.getString(1);
                    }
                }
                if (dbAndroid != null && !dbAndroid.isEmpty() && !dbAndroid.equals(androidId)) {
                    out.print("{\"result\":\"fail\",\"message\":\"등록된 기기와 다릅니다.\"}");
                    out.flush(); resp.flushBuffer();
                    wrote = true;
                    return;
                }

                // 4) UPSERT (week_number=week 그대로 저장)
                final String UPDATE_SQL =
                        "UPDATE /*+ NOPARALLEL */ attendance_records " +
                                "   SET status='출석', attendance_datetime = SYSTIMESTAMP, " +
                                "       bssid=?, android_id=?, last_modified_at = SYSTIMESTAMP, week_number=? " +
                                " WHERE open_id=? AND student_id=? AND subject_id=?";
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(UPDATE_SQL)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setString(1, bssid);
                    ps.setString(2, androidId);
                    ps.setInt(3, week);
                    ps.setInt(4, openId);
                    ps.setString(5, studentId);
                    ps.setInt(6, subjectId);
                    updated = ps.executeUpdate();
                }

                if (updated == 0) {
                    final String INSERT_SQL =
                            "INSERT /*+ NOPARALLEL */ INTO attendance_records " +
                                    " (open_id, student_id, subject_id, attendance_datetime, status, bssid, android_id, last_modified_at, week_number) " +
                                    " VALUES (?,?,?,?, '출석', ?, ?, SYSTIMESTAMP, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                        ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                        ps.setInt(1, openId);
                        ps.setString(2, studentId);
                        ps.setInt(3, subjectId);
                        ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                        ps.setString(5, bssid);
                        ps.setString(6, androidId);
                        ps.setInt(7, week);
                        ps.executeUpdate();
                    }
                }

                // 성공 응답
                JsonObject ok = new JsonObject();
                ok.addProperty("result", "success");
                ok.addProperty("subjectId", subjectId);
                ok.addProperty("openId", openId);
                ok.addProperty("week", week);     // ★ 오픈 시 확정된 주차
                out.print(ok.toString());
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

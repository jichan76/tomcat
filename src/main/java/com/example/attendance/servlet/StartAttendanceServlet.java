package com.example.attendance.servlet;

import com.example.attendance.repository.InMemoryDB;
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
import java.util.logging.Logger;

@WebServlet("/startAttendance")
public class StartAttendanceServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(StartAttendanceServlet.class.getName());

    private static final Semaphore GUARD = new Semaphore(2, true);
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
        Connection conn = DriverManager.getConnection(url, p);

        // 병렬 DML/QUERY 비활성 (권한 없어도 try-catch로 무시)
        try (Statement st = conn.createStatement()) {
            try { st.execute("ALTER SESSION DISABLE PARALLEL DML"); } catch (Exception ignore) {}
            try { st.execute("ALTER SESSION DISABLE PARALLEL QUERY"); } catch (Exception ignore) {}
            try { st.execute("ALTER SESSION SET parallel_degree_policy = MANUAL"); } catch (Exception ignore) {}
            try { st.execute("ALTER SESSION SET parallel_min_time_threshold = AUTO"); } catch (Exception ignore) {}
            try { st.execute("ALTER SESSION SET parallel_degree_limit = 1"); } catch (Exception ignore) {}
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
        boolean ok = false;
        boolean wrote = false;

        // Writer를 한 번만 열어 모든 분기에서 동일 객체 사용
        PrintWriter out = resp.getWriter();

        try {
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) {
                resp.setStatus(503);
                out.write("{\"result\":\"busy\"}");
                out.flush(); resp.flushBuffer();
                wrote = true;
                return;
            }

            req.setCharacterEncoding("UTF-8");

            String subjectIdStr = req.getParameter("subjectId");
            String bssid        = req.getParameter("bssid");
            String weekStr      = req.getParameter("week");          // ★ 추가 (필수)
            String overrideDate = req.getParameter("overrideDate");  // ★ 추가 (선택, yyyy-MM-dd)

            JsonObject result = new JsonObject();

            // 파라미터 검증
            if (subjectIdStr == null || subjectIdStr.isEmpty()
                    || bssid == null || bssid.isEmpty()
                    || weekStr == null || weekStr.isEmpty()) {
                resp.setStatus(400);
                result.addProperty("result", "error");
                result.addProperty("message", "subjectId, bssid, week는 필수입니다.");
                out.print(result.toString());
                out.flush(); resp.flushBuffer();
                wrote = true;
                return;
            }

            int subjectId;
            int week;
            try {
                subjectId = Integer.parseInt(subjectIdStr);
                week = Integer.parseInt(weekStr);
            } catch (NumberFormatException nfe) {
                resp.setStatus(400);
                result.addProperty("result", "error");
                result.addProperty("message", "subjectId/week 형식이 올바르지 않습니다.");
                out.print(result.toString());
                out.flush(); resp.flushBuffer();
                wrote = true;
                return;
            }

            // BSSID 소문자 통일
            bssid = bssid.toLowerCase();

            // session_date 결정: overrideDate가 있으면 사용, 없으면 오늘(서버 날짜)
            java.sql.Date sessionDate;
            if (overrideDate != null && !overrideDate.trim().isEmpty()) {
                try {
                    sessionDate = java.sql.Date.valueOf(overrideDate.trim()); // yyyy-MM-dd
                } catch (IllegalArgumentException iae) {
                    resp.setStatus(400);
                    result.addProperty("result", "error");
                    result.addProperty("message", "overrideDate 형식은 yyyy-MM-dd 여야 합니다.");
                    out.print(result.toString());
                    out.flush(); resp.flushBuffer();
                    wrote = true;
                    return;
                }
            } else {
                sessionDate = new java.sql.Date(System.currentTimeMillis()); // 오늘(로컬 타임존 기준)
            }

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);

                // 0) 동일 과목의 기존 열린 세션 마감
                final String CLOSE_OLD =
                        "UPDATE /*+ NOPARALLEL */ attendance_open_log " +
                                "   SET close_datetime = SYSTIMESTAMP " +
                                " WHERE subject_id = ? AND close_datetime IS NULL";
                try (PreparedStatement ps = conn.prepareStatement(CLOSE_OLD)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);
                    ps.executeUpdate();
                }

                // 1) 새 세션 INSERT — week_number, session_date 확정 저장
                final String INS =
                        "INSERT /*+ NOPARALLEL */ INTO attendance_open_log " +
                                " (open_id, subject_id, bssid, open_datetime, close_datetime, week_number, session_date) " +
                                " VALUES (attendance_open_log_seq.NEXTVAL, ?, ?, SYSTIMESTAMP, NULL, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(INS)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);
                    ps.setString(2, bssid);
                    ps.setInt(3, week);
                    ps.setDate(4, sessionDate);
                    ps.executeUpdate();
                }

                // 2) 방금 연 open_id 조회 (가장 최근 row)
                Long openId = null;
                final String SEL_LAST =
                        "SELECT open_id FROM attendance_open_log " +
                                " WHERE subject_id = ? AND close_datetime IS NULL " +
                                " ORDER BY open_datetime DESC FETCH FIRST 1 ROWS ONLY";
                try (PreparedStatement ps = conn.prepareStatement(SEL_LAST)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            openId = rs.getLong(1);
                        }
                    }
                }

                // 3) subjects에 교수 BSSID 반영(선택)
                final String UPD_SUBJ =
                        "UPDATE /*+ NOPARALLEL */ subjects SET professor_bssid = ? WHERE subject_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(UPD_SUBJ)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setString(1, bssid);
                    ps.setInt(2, subjectId);
                    ps.executeUpdate();
                }

                conn.commit();

                // 인메모리 상태 반영(필요 시)
                InMemoryDB.ATTENDANCE.put(subjectId, new java.util.HashSet<>());

                // 응답
                result.addProperty("result", "started");
                if (openId != null) result.addProperty("openId", openId);
                result.addProperty("subjectId", subjectId);
                result.addProperty("bssid", bssid);
                result.addProperty("week", week);
                result.addProperty("sessionDate", sessionDate.toString()); // yyyy-MM-dd
                result.addProperty("startAt", java.time.OffsetDateTime.now().toString());
                out.print(result.toString());
                out.flush(); resp.flushBuffer();
                wrote = true;

            } catch (Exception e) {
                logger.severe("startAttendance DB error: " + e.getMessage());
                resp.setStatus(500);
                JsonObject err = new JsonObject();
                err.addProperty("result", "error");
                err.addProperty("message", "DB 오류: " + e.getMessage());
                out.print(err.toString());
                out.flush(); resp.flushBuffer();
                wrote = true;
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            resp.setStatus(503);
            out.write("{\"result\":\"busy\"}");
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
            if (ok) GUARD.release();
            if (!resp.isCommitted() && !wrote) {
                resp.setStatus(500);
                out.write("{\"result\":\"error\",\"message\":\"empty response\"}");
                out.flush(); resp.flushBuffer();
            }
        }
    }
}

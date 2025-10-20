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
import java.util.logging.Logger;

@WebServlet("/AttendanceServlet")
public class AttendanceServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(AttendanceServlet.class.getName());
    private static final int MAX_CONCURRENT = 16;
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;
    private static final Semaphore GUARD = new Semaphore(MAX_CONCURRENT, true);

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ATTEND");
        p.setProperty("password", "Atd_Usr#2025!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");

        // ✅ DB 연결
        Connection conn = DriverManager.getConnection(url, p);
        System.out.println("[DB INFO] ✅ DB 연결 성공!");  // 🔹 연결 직후 콘솔 확인용

        // ✅ 현재 DB 사용자 확인
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT USER FROM dual")) {
            if (rs.next()) {
                String currentUser = rs.getString(1);
                System.out.println("[DB INFO] 현재 DB 계정: " + currentUser); // 🔹 콘솔 강제 출력
                logger.info("[DB INFO] 현재 DB 계정: " + currentUser);
            }
        } catch (Exception ex) {
            System.out.println("[DB INFO] ⚠️ USER 조회 중 예외 발생: " + ex.getMessage());
            ex.printStackTrace();
        }

//        try (Statement st = conn.createStatement()) {
//            st.execute("ALTER SESSION SET \"_ORACLE_SCRIPT\"=TRUE");
//            st.execute("ALTER SESSION DISABLE PARALLEL DML");
//            st.execute("ALTER SESSION DISABLE PARALLEL QUERY");
//            st.execute("ALTER SESSION SET parallel_degree_policy = MANUAL");
//            st.execute("ALTER SESSION SET parallel_min_time_threshold = AUTO");
//            st.execute("ALTER SESSION SET parallel_degree_limit = 1");
//        } catch (Exception ex) {
//            System.out.println("[DB INFO] ⚠️ ALTER SESSION 중 오류: " + ex.getMessage());
//        }

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

                // [LOG ADDED]
                logger.info("[AttendanceServlet] 연결 성공 → studentId=" + studentId + ", bssid=" + bssid);

                // 1️⃣ 현재 열린 세션 확인
                final String FIND_OPEN =
                        "SELECT a.open_id, a.subject_id, a.open_datetime, a.week_number " +
                                "  FROM ADMIN.attendance_open_log a " +
                                "  JOIN ADMIN.enrollments e ON e.subject_id = a.subject_id " +
                                " WHERE e.student_id = ? " +
                                "   AND LOWER(a.bssid) = ? " +
                                "   AND a.open_datetime <= SYSTIMESTAMP " +
                                "   AND (a.close_datetime IS NULL OR a.close_datetime >= SYSTIMESTAMP) " +
                                " ORDER BY a.open_datetime DESC FETCH FIRST 1 ROWS ONLY";


                Integer openId = null;
                Integer subjectId = null;
                Timestamp openDt = null;
                Integer week = null;

                try (PreparedStatement ps = conn.prepareStatement(FIND_OPEN)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setString(1, studentId);
                    ps.setString(2, bssid);
                    // [LOG ADDED]
                    logger.info("[AttendanceServlet] 실행 중: FIND_OPEN 쿼리");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            openId    = rs.getInt("open_id");
                            subjectId = rs.getInt("subject_id");
                            openDt    = rs.getTimestamp("open_datetime");
                            week      = rs.getInt("week_number");
                        }
                    }
                } catch (SQLException se) {
                    logger.severe("[SQL ERROR: FIND_OPEN] " + se.getMessage());
                    throw se;
                }

                if (openId == null) {
                    out.print("{\"result\":\"fail\",\"message\":\"현재 출석이 열려 있지 않거나 BSSID가 일치하지 않습니다.\"}");
                    out.flush(); resp.flushBuffer();
                    wrote = true;
                    return;
                }

                // 2️⃣ 안드로이드 기기 검증
                final String ANDROID_SQL =
                        "SELECT android_id FROM ADMIN.users WHERE user_id = ? AND role = 'student'";
                String dbAndroid = null;
                try (PreparedStatement ps = conn.prepareStatement(ANDROID_SQL)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setString(1, studentId);
                    // [LOG ADDED]
                    logger.info("[AttendanceServlet] 실행 중: ANDROID_SQL 쿼리");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) dbAndroid = rs.getString(1);
                    }
                } catch (SQLException se) {
                    logger.severe("[SQL ERROR: ANDROID_SQL] " + se.getMessage());
                    throw se;
                }

                if (dbAndroid != null && !dbAndroid.isEmpty() && !dbAndroid.equals(androidId)) {
                    out.print("{\"result\":\"fail\",\"message\":\"등록된 기기와 다릅니다.\"}");
                    out.flush(); resp.flushBuffer();
                    wrote = true;
                    return;
                }

                final String MERGE_SQL =
                        "MERGE /*+ NOPARALLEL */ INTO ADMIN.attendance_records a " +
                                "USING (SELECT ? AS OPEN_ID, ? AS STUDENT_ID, ? AS SUBJECT_ID, ? AS WEEK_NUMBER, " +
                                "              SYSTIMESTAMP AS NOW_TS, ? AS BSSID, ? AS ANDROID_ID FROM dual) s " +
                                "ON (a.STUDENT_ID = s.STUDENT_ID AND a.SUBJECT_ID = s.SUBJECT_ID AND a.WEEK_NUMBER = s.WEEK_NUMBER) " +
                                "WHEN NOT MATCHED THEN " +
                                "  INSERT (OPEN_ID, STUDENT_ID, SUBJECT_ID, ATTENDANCE_DATETIME, STATUS, BSSID, ANDROID_ID, LAST_MODIFIED_AT, WEEK_NUMBER) " +
                                "  VALUES (s.OPEN_ID, s.STUDENT_ID, s.SUBJECT_ID, s.NOW_TS, '출석', s.BSSID, s.ANDROID_ID, SYSTIMESTAMP, s.WEEK_NUMBER) " +
                                "WHEN MATCHED THEN " +
                                "  UPDATE SET " +
                                "    a.STATUS = CASE " +
                                "                  WHEN a.STATUS = '결석' THEN '지각_조퇴' " +
                                "                  WHEN a.STATUS IS NULL THEN '출석' " +
                                "                  ELSE a.STATUS " +
                                "               END, " +
                                "    a.ATTENDANCE_DATETIME = SYSTIMESTAMP, " +
                                "    a.BSSID = s.BSSID, " +
                                "    a.ANDROID_ID = s.ANDROID_ID, " +
                                "    a.LAST_MODIFIED_AT = SYSTIMESTAMP";


                int affected = 0;
                try (PreparedStatement ps = conn.prepareStatement(MERGE_SQL)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, openId);
                    ps.setString(2, studentId);
                    ps.setInt(3, subjectId);
                    ps.setInt(4, week);
                    ps.setString(5, bssid);
                    ps.setString(6, androidId);

                    logger.info("[AttendanceServlet] 실행 중: MERGE_SQL 쿼리");
                    affected = ps.executeUpdate();
                } catch (SQLException se) {
                    logger.severe("[SQL ERROR: MERGE_SQL] " + se.getMessage());
                    throw se;
                }

                logger.info("[AttendanceServlet] MERGE 완료 → affected=" + affected +
                        ", studentId=" + studentId + ", subjectId=" + subjectId + ", week=" + week);

                // ✅ 성공 응답
                JsonObject ok = new JsonObject();
                ok.addProperty("result", "success");
                ok.addProperty("subjectId", subjectId);
                ok.addProperty("openId", openId);
                ok.addProperty("week", week);
                ok.addProperty("affected", affected);
                out.print(ok.toString());
                out.flush(); resp.flushBuffer();
                wrote = true;
            }

        } catch (Exception e) {
            logger.severe("[AttendanceServlet] 예외 발생: " + e.getClass().getName() + " | " + e.getMessage());
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

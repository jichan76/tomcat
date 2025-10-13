package com.example.attendance.servlet;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@WebServlet("/endAttendance")
public class EndAttendanceServlet extends HttpServlet {

    // ===== Concurrency & Timeouts =====
    private static final Semaphore GUARD = new Semaphore(2, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;

    // ===== SQL =====
    private static final String SQL_CLEAR_BSSID =
            "UPDATE /*+ NOPARALLEL */ subjects SET professor_bssid = NULL WHERE subject_id = ?";

    private static final String SQL_FIND_ACTIVE_OPEN =
            "SELECT /*+ NOPARALLEL */ open_id, open_datetime " +
                    "  FROM attendance_open_log " +
                    " WHERE subject_id = ? AND close_datetime IS NULL " +
                    " ORDER BY open_datetime DESC FETCH FIRST 1 ROWS ONLY";

    private static final String SQL_CLOSE_OPEN =
            "UPDATE /*+ NOPARALLEL */ attendance_open_log " +
                    "   SET close_datetime = SYSTIMESTAMP WHERE open_id = ?";

    private static final String SQL_GET_SEM_START =
            "SELECT /*+ NOPARALLEL */ start_date FROM semester_info FETCH FIRST 1 ROWS ONLY";

    private static final String SQL_INSERT_ABSENTS =
            "INSERT /*+ NOPARALLEL */ INTO attendance_records " +
                    " (open_id, student_id, subject_id, attendance_datetime, status, last_modified_at, week_number) " +
                    "SELECT ?, e.student_id, ?, SYSTIMESTAMP, '결석', SYSTIMESTAMP, ? " +
                    "  FROM enrollments e " +
                    " WHERE e.subject_id = ? " +
                    "   AND NOT EXISTS ( " +
                    "         SELECT 1 FROM attendance_records r " +
                    "          WHERE r.open_id = ? AND r.student_id = e.student_id " +
                    "   )";

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

        // 병렬 DML/QUERY 비활성화 (세션 단위)
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER SESSION DISABLE PARALLEL DML");
            st.execute("ALTER SESSION DISABLE PARALLEL QUERY");
            st.execute("ALTER SESSION SET parallel_degree_policy = MANUAL");
            // 선택적: 필요시 제한 더 강하게
            st.execute("ALTER SESSION SET parallel_min_time_threshold = AUTO");
            st.execute("ALTER SESSION SET parallel_degree_limit = 1");
        }
        return conn;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        boolean acquired = false;
        Connection conn = null;

        try {
            acquired = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                writeJson(resp, 503, "{\"result\":\"busy\"}");
                return;
            }

            req.setCharacterEncoding("UTF-8");
            resp.setContentType("application/json; charset=UTF-8");
            PrintWriter out = resp.getWriter();

            // ===== Params =====
            String subjectIdStr = req.getParameter("subjectId");
            String weekStr      = req.getParameter("week");
            if (subjectIdStr == null || subjectIdStr.isEmpty()) {
                out.write("{\"result\":\"error\",\"message\":\"subjectId 누락\"}");
                return;
            }

            int subjectId;
            try { subjectId = Integer.parseInt(subjectIdStr); }
            catch (NumberFormatException e) {
                out.write("{\"result\":\"error\",\"message\":\"subjectId 숫자 아님\"}");
                return;
            }

            Integer weekParam = null;
            if (weekStr != null && !weekStr.isEmpty()) {
                try { weekParam = Integer.parseInt(weekStr); }
                catch (NumberFormatException e) {
                    out.write("{\"result\":\"error\",\"message\":\"week 숫자 아님\"}");
                    return;
                }
            }

            // ===== DB =====
            conn = getConnection();
            conn.setAutoCommit(false);

            // ① 활성 open 조회
            Integer openId = null;
            Timestamp openDt = null;
            try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_ACTIVE_OPEN)) {
                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                ps.setInt(1, subjectId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        openId = rs.getInt(1);
                        openDt = rs.getTimestamp(2);
                    }
                }
            }

            int closed = 0;
            int cleared = 0;
            int insertedAbsents = 0;

            // ② 열린 세션 닫기
            if (openId != null) {
                try (PreparedStatement ps = conn.prepareStatement(SQL_CLOSE_OPEN)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, openId);
                    closed = ps.executeUpdate();
                }
            }

            // ③ 과목의 교수 BSSID NULL 처리 (항상 수행)
            try (PreparedStatement ps = conn.prepareStatement(SQL_CLEAR_BSSID)) {
                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                ps.setInt(1, subjectId);
                cleared = ps.executeUpdate();
            }

            // ④ 결석 채움 (open이 있었을 때만)
            if (openId != null) {
                int weekToUse;
                if (weekParam != null) {
                    weekToUse = weekParam;
                } else {
                    java.sql.Date semStart = null;
                    try (PreparedStatement ps = conn.prepareStatement(SQL_GET_SEM_START)) {
                        ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) semStart = rs.getDate(1);
                        }
                    }
                    if (semStart == null || openDt == null) {
                        weekToUse = 1;
                    } else {
                        long days = openDt.toLocalDateTime().toLocalDate().toEpochDay()
                                - semStart.toLocalDate().toEpochDay();
                        weekToUse = (int) Math.floor(days / 7.0) + 1;
                        if (weekToUse < 1) weekToUse = 1;
                        if (weekToUse > 16) weekToUse = 16; // 프로젝트 기준
                    }
                    // 필요 시: override 가능하면 여기서 조정
                }

                try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_ABSENTS)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    int i = 1;
                    ps.setInt(i++, openId);
                    ps.setInt(i++, subjectId);
                    ps.setInt(i++, (weekParam != null ? weekParam : 1)); // placeholder, 바로 아래에서 set 다시 함
                    ps.setInt(i++, subjectId);
                    ps.setInt(i++, openId);
                    // 위에서 placeholder로 넣은 week_number 교체:
                    // 주의: PreparedStatement는 인덱스 기반이라 set 순서 맞추기 위해 재바인딩
                }
            }

            conn.commit();

            out.write("{\"result\":\"success\"" +
                    ",\"closedOpen\":" + closed +
                    ",\"clearedBssid\":" + cleared +
                    ",\"markedAbsent\":" + insertedAbsents +
                    (openId == null ? ",\"note\":\"열린 출석 이벤트 없음(마감 대상 없음)\"" : "") +
                    "}");

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            writeJson(resp, 503, "{\"result\":\"busy\"}");
        } catch (Exception e) {
            // 안전 롤백
            try { if (conn != null) conn.rollback(); } catch (Exception ignore) {}
            String msg = e.getMessage() == null ? "" : e.getMessage().replace("\"", "\\\"");
            writeJson(resp, 500, "{\"result\":\"error\",\"message\":\"DB 오류: " + msg + "\"}");
        } finally {
            if (acquired) GUARD.release();
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    // ===== helpers =====
    private static void writeJson(HttpServletResponse resp, int code, String json) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write(json);
    }

    /** 학기 시작일 기반 주차 계산 (방어 포함) */
    private static int calcWeek(Connection conn, Timestamp openDt, String sqlGetSemStart) throws SQLException {
        if (openDt == null) return 1;
        java.sql.Date semStart = null;
        try (PreparedStatement ps = conn.prepareStatement(sqlGetSemStart)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) semStart = rs.getDate(1);
            }
        }
        if (semStart == null) return 1;
        long days = openDt.toLocalDateTime().toLocalDate().toEpochDay()
                - semStart.toLocalDate().toEpochDay();
        int week = (int) Math.floor(days / 7.0) + 1;
        if (week < 1) week = 1;
        if (week > 16) week = 16;
        return week;
    }
}

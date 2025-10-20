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
import java.util.logging.Logger;

@WebServlet("/endAttendance")
public class EndAttendanceServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(EndAttendanceServlet.class.getName());
    private static final Semaphore GUARD = new Semaphore(2, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS = 8000;

    private static final String SQL_CLEAR_BSSID =
            "UPDATE subjects SET professor_bssid = NULL WHERE subject_id = ?";

    private static final String SQL_FIND_ACTIVE_OPEN =
            "SELECT open_id, open_datetime FROM attendance_open_log " +
                    "WHERE subject_id = ? AND close_datetime IS NULL " +
                    "ORDER BY open_datetime DESC FETCH FIRST 1 ROWS ONLY";

    private static final String SQL_CLOSE_OPEN =
            "UPDATE attendance_open_log SET close_datetime = SYSTIMESTAMP WHERE open_id = ?";

    private static final String SQL_GET_SEM_START =
            "SELECT start_date FROM semester_info FETCH FIRST 1 ROWS ONLY";

    private static final String SQL_INSERT_ABSENTS =
            "MERGE INTO attendance_records r " +
                    "USING (SELECT e.student_id FROM enrollments e WHERE e.subject_id = ?) src " +
                    "ON (r.open_id = ? AND r.student_id = src.student_id) " +
                    "WHEN NOT MATCHED THEN " +
                    "  INSERT (open_id, student_id, subject_id, attendance_datetime, status, last_modified_at, week_number) " +
                    "  VALUES (?, src.student_id, ?, SYSTIMESTAMP, '결석', SYSTIMESTAMP, ?)";

    // ✅ 추가: 이미 존재하지만 미체크 상태인 학생을 결석으로 갱신
    private static final String SQL_UPDATE_UNCHECKED_TO_ABSENT =
            "UPDATE attendance_records " +
                    "SET status = '결석', last_modified_at = SYSTIMESTAMP " +
                    "WHERE open_id = ? AND (status IS NULL OR status = '미체크')";

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT", String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
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

            String subjectIdStr = req.getParameter("subjectId");
            if (subjectIdStr == null || subjectIdStr.isEmpty()) {
                out.write("{\"result\":\"error\",\"message\":\"subjectId 누락\"}");
                return;
            }

            int subjectId = Integer.parseInt(subjectIdStr);

            conn = getConnection();
            conn.setAutoCommit(false);

            // 🔹 1. 현재 열린 출석 세션 찾기
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

            // 🔹 2. 열린 세션 닫기
            if (openId != null) {
                try (PreparedStatement ps = conn.prepareStatement(SQL_CLOSE_OPEN)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, openId);
                    closed = ps.executeUpdate();
                }
            }

            // 🔹 3. 교수 BSSID 제거
            try (PreparedStatement ps = conn.prepareStatement(SQL_CLEAR_BSSID)) {
                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                ps.setInt(1, subjectId);
                cleared = ps.executeUpdate();
                logger.info("[EndAttendance] BSSID 제거 결과 → 업데이트된 행 수: " + cleared);
            }

            // 🔹 4. 결석자 추가 (openId 존재 시)
            if (openId != null) {
                int weekNumber = calcWeek(conn, openDt, SQL_GET_SEM_START);
                try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_ABSENTS)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);  // USING enrollments.subject_id = ?
                    ps.setInt(2, openId);     // ON (r.open_id = ? AND ...)
                    ps.setInt(3, openId);     // INSERT open_id
                    ps.setInt(4, subjectId);  // INSERT subject_id
                    ps.setInt(5, weekNumber); // INSERT week_number
                    insertedAbsents = ps.executeUpdate();
                }
                conn.commit();
                // ✅ 5. 미체크 → 결석 갱신
                try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_UNCHECKED_TO_ABSENT)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, openId);
                    int updated = ps.executeUpdate();
                    insertedAbsents += updated;
                    System.out.println("[EndAttendance] 미체크 → 결석 처리된 학생 수: " + updated);
                }
            }

            conn.commit();

            out.write("{\"result\":\"success\",\"closed\":" + closed +
                    ",\"cleared\":" + cleared +
                    ",\"insertedAbsents\":" + insertedAbsents + "}");

        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignore) {}
            e.printStackTrace();
            writeJson(resp, 500, "{\"result\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        } finally {
            if (acquired) GUARD.release();
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    private static void writeJson(HttpServletResponse resp, int code, String json) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write(json);
    }

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
        long days = openDt.toLocalDateTime().toLocalDate().toEpochDay() - semStart.toLocalDate().toEpochDay();
        int week = (int) Math.floor(days / 7.0) + 1;
        return Math.max(1, Math.min(week, 16));
    }
}

// server/src/main/java/com/example/attendance/servlet/FinalizeAttendanceServlet.java
package com.example.attendance.servlet;

import com.google.gson.JsonObject;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@WebServlet("/FinalizeAttendance")
public class FinalizeAttendanceServlet extends HttpServlet {

    // ===== Concurrency & Timeouts =====
    private static final Semaphore GUARD = new Semaphore(2, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;

    // ===== SQL (NOPARALLEL 힌트 적용) =====
    private static final String SQL_INSERT_MISSING_ABSENTS =
            "INSERT /*+ NOPARALLEL */ INTO ATTENDANCE_RECORDS " +
                    " (STUDENT_ID, SUBJECT_ID, ATTENDANCE_DATETIME, STATUS, WEEK_NUMBER) " +
                    "SELECT e.STUDENT_ID, ?, SYSTIMESTAMP, '결석', ? " +
                    "  FROM ENROLLMENTS e " +
                    " WHERE e.SUBJECT_ID = ? " +
                    "   AND NOT EXISTS ( " +
                    "         SELECT 1 FROM ATTENDANCE_RECORDS r " +
                    "          WHERE r.SUBJECT_ID  = e.SUBJECT_ID " +
                    "            AND r.STUDENT_ID  = e.STUDENT_ID " +
                    "            AND r.WEEK_NUMBER = ?)";

    // ATTENDANCE_CLOSURES 업서트: DISPLAY_DATE는 최초 1회만 세팅(존재하면 유지),
    // 이후 호출에서는 CLOSED_AT 갱신, BSSID가 비어 있으면 채움
    private static final String SQL_MERGE_CLOSURE =
            "MERGE /*+ NOPARALLEL */ INTO ATTENDANCE_CLOSURES c " +
                    "USING (SELECT ? AS SUBJECT_ID, ? AS WEEK_NUMBER, ? AS DISPLAY_DATE, ? AS BSSID FROM dual) s " +
                    "   ON (c.SUBJECT_ID = s.SUBJECT_ID AND c.WEEK_NUMBER = s.WEEK_NUMBER) " +
                    "WHEN NOT MATCHED THEN " +
                    "  INSERT (SUBJECT_ID, WEEK_NUMBER, DISPLAY_DATE, CLOSED_AT, BSSID) " +
                    "  VALUES (s.SUBJECT_ID, s.WEEK_NUMBER, NVL(s.DISPLAY_DATE, TRUNC(SYSDATE)), SYSTIMESTAMP, s.BSSID) " +
                    "WHEN MATCHED THEN " +
                    "  UPDATE SET " +
                    "    c.CLOSED_AT   = SYSTIMESTAMP, " +
                    "    c.DISPLAY_DATE = NVL(c.DISPLAY_DATE, NVL(s.DISPLAY_DATE, TRUNC(SYSDATE))), " +
                    "    c.BSSID        = NVL(c.BSSID, s.BSSID)";

    private static final String SQL_IS_OFF_SCHEDULE =
            "WITH base AS ( " +
                    "  SELECT NEXT_DAY(si.start_date - 1, " +
                    "    CASE UPPER(s.day_of_week) " +
                    "      WHEN 'MON' THEN 'MONDAY' WHEN 'TUE' THEN 'TUESDAY' WHEN 'WED' THEN 'WEDNESDAY' " +
                    "      WHEN 'THU' THEN 'THURSDAY' WHEN 'FRI' THEN 'FRIDAY' END) AS first_class_date " +
                    "    FROM subjects s " +
                    "    JOIN (SELECT /*+ NOPARALLEL */ start_date FROM semester_info " +
                    "           WHERE start_date <= CAST(SYSTIMESTAMP AS DATE) " +
                    "           ORDER BY start_date DESC FETCH FIRST 1 ROWS ONLY) si ON 1=1 " +
                    "   WHERE s.subject_id = ? " +
                    "), planned AS (SELECT first_class_date + 7 * (? - 1) AS planned_date FROM base) " +
                    "SELECT CASE WHEN ? IS NULL THEN " +
                    "              CASE WHEN TRUNC(SYSDATE) = (SELECT planned_date FROM planned) THEN 0 ELSE 1 END " +
                    "            ELSE " +
                    "              CASE WHEN ? = (SELECT planned_date FROM planned) THEN 0 ELSE 1 END " +
                    "       END AS off_sched " +
                    "FROM dual";

    private Connection getConnection() throws SQLException {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_READ_TIMEOUT_MS));

        Connection conn = DriverManager.getConnection(url, p);

        // ★ 병렬 DML/QUERY 비활성화 (세션 단위)
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER SESSION DISABLE PARALLEL DML");
            st.execute("ALTER SESSION DISABLE PARALLEL QUERY");
            st.execute("ALTER SESSION SET parallel_degree_policy = MANUAL");
            st.execute("ALTER SESSION SET parallel_min_time_threshold = AUTO");
            st.execute("ALTER SESSION SET parallel_degree_limit = 1");
        }
        return conn;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        boolean acquired = false;
        Connection conn = null;

        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");

        JsonObject json = new JsonObject();

        try (PrintWriter out = resp.getWriter()) {
            acquired = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                resp.setStatus(503);
                out.print("{\"result\":\"busy\"}");
                return;
            }

            String subjectIdStr = req.getParameter("subjectId");
            String weekStr      = req.getParameter("week");
            String overrideDate = req.getParameter("overrideDate"); // yyyy-MM-dd or null
            String bssid        = req.getParameter("bssid");        // 교수 세션 BSSID (nullable 허용)

            if (subjectIdStr == null || subjectIdStr.trim().isEmpty() ||
                    weekStr == null || weekStr.trim().isEmpty()) {
                resp.setStatus(400);
                json.addProperty("result", "error");
                json.addProperty("message", "missing subjectId or week");
                out.print(json.toString());
                return;
            }

            int subjectId = Integer.parseInt(subjectIdStr.trim());
            int week      = Integer.parseInt(weekStr.trim());

            java.sql.Date overrideSql = null;
            if (overrideDate != null && !overrideDate.trim().isEmpty()) {
                overrideSql = java.sql.Date.valueOf(overrideDate.trim()); // 형식 오류시 예외
            }

            conn = getConnection();
            conn.setAutoCommit(false);

            // 1) 미체크 인원 결석 채움 (해당 주차 기준)
            int insertedAbsents;
            try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_MISSING_ABSENTS)) {
                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                ps.setInt(1, subjectId);
                ps.setInt(2, week);
                ps.setInt(3, subjectId);
                ps.setInt(4, week);
                insertedAbsents = ps.executeUpdate();
            }

            // 2) ATTENDANCE_CLOSURES 업서트 (DISPLAY_DATE는 최초 1회만, BSSID 처음 값 유지)
            try (PreparedStatement ps = conn.prepareStatement(SQL_MERGE_CLOSURE)) {
                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                ps.setInt(1, subjectId);
                ps.setInt(2, week);
                if (overrideSql != null) ps.setDate(3, overrideSql); else ps.setNull(3, Types.DATE);
                if (bssid != null && !bssid.isEmpty()) ps.setString(4, bssid); else ps.setNull(4, Types.VARCHAR);
                ps.executeUpdate();
            }

            // 3) 보강일/예정일 불일치 여부
            boolean offSchedule;
            try (PreparedStatement ps = conn.prepareStatement(SQL_IS_OFF_SCHEDULE)) {
                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                ps.setInt(1, subjectId);
                ps.setInt(2, week);
                if (overrideSql != null) {
                    ps.setDate(3, overrideSql);
                    ps.setDate(4, overrideSql);
                } else {
                    ps.setNull(3, Types.DATE);
                    ps.setNull(4, Types.DATE);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    offSchedule = rs.next() && rs.getInt(1) == 1;
                }
            }

            conn.commit();

            json.addProperty("result", "success");
            json.addProperty("inserted_absents", insertedAbsents);
            if (offSchedule) json.addProperty("warning", "off_schedule");
            out.print(json.toString());

        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignore) {}
            resp.setStatus(500);
            String msg = e.getMessage() == null ? "" : e.getMessage().replace("\"", "\\\"");
            try (PrintWriter out = resp.getWriter()) {
                out.print("{\"result\":\"error\",\"message\":\"" + msg + "\"}");
            }
        } finally {
            if (acquired) GUARD.release();
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }
}

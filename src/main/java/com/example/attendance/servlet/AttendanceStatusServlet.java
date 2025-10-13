// src/main/java/com/example/attendance/servlet/AttendanceStatusServlet.java
package com.example.attendance.servlet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@WebServlet("/attendanceStatus")
public class AttendanceStatusServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(AttendanceStatusServlet.class.getName());

    // 동시성/타임아웃
    private static final Semaphore GUARD = new Semaphore(12, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;

    // 날짜 포맷 (YYYY-MM-DD)
    private static final SimpleDateFormat DF = new SimpleDateFormat("yyyy-MM-dd");

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

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        boolean ok = false;
        try {
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) {
                response.setStatus(503);
                response.getWriter().write("{\"result\":\"busy\"}");
                return;
            }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");

            // 파라미터
            String studentId = request.getParameter("studentId");
            String subjectIdStr = request.getParameter("subjectId");
            String weekStr   = request.getParameter("week");

            if (subjectIdStr == null || subjectIdStr.trim().isEmpty()) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"subjectId 누락\"}");
                return;
            }

            int subjectId;
            try {
                subjectId = Integer.parseInt(subjectIdStr.trim());
            } catch (NumberFormatException e) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"subjectId가 숫자가 아님\"}");
                return;
            }

            Integer week = null;
            if (weekStr != null && !weekStr.trim().isEmpty()) {
                try {
                    week = Integer.parseInt(weekStr.trim());
                } catch (NumberFormatException e) {
                    response.setStatus(400);
                    response.getWriter().write("{\"error\":\"week가 올바른 숫자가 아님\"}");
                    return;
                }
                if (week < 1 || week > 16) {
                    response.setStatus(400);
                    response.getWriter().write("{\"error\":\"week는 1~16 범위여야 함\"}");
                    return;
                }
            }

            try (Connection conn = getConnection()) {
                JsonObject root = new JsonObject();

                // (A) 특정 학생 요약(원래 있던 기능 유지)
                if (studentId != null && !studentId.trim().isEmpty()) {
                    addStudentSummary(conn, root, studentId.trim(), subjectId);
                }

                // (B) 과목+주차 요약: counts + totalEnrolled + 날짜(labelDate 포함)
                if (week != null) {
                    addWeekSummaryWithDates(conn, root, subjectId, week);
                }

                if (root.size() == 0) {
                    response.setStatus(400);
                    response.getWriter().write("{\"error\":\"studentId 또는 week 중 하나는 필요\"}");
                    return;
                }

                String json = new Gson().toJson(root);
                PrintWriter out = response.getWriter();
                out.print(json);
                out.flush();
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            response.setStatus(503);
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter()
                    .write("{\"error\":\"서버 오류: " + safeMsg(e.getMessage()) + "\"}");
        } finally {
            if (ok) GUARD.release();
        }
    }

    /** (A) 학생 관점 요약(주차별 최근 상태 + 합계) */
    private void addStudentSummary(Connection conn, JsonObject root, String studentId, int subjectId) throws Exception {
        final String WEEK_SELECT =
                "SELECT week_number, status FROM ( " +
                        "  SELECT week_number, status, " +
                        "         ROW_NUMBER() OVER (PARTITION BY week_number ORDER BY attendance_datetime DESC) rn " +
                        "    FROM attendance_records " +
                        "   WHERE student_id = ? AND subject_id = ? AND week_number BETWEEN 1 AND 16 " +
                        ") WHERE rn = 1";

        final String SUM_SELECT =
                "SELECT status, COUNT(*) cnt FROM ( " +
                        "  SELECT week_number, status, " +
                        "         ROW_NUMBER() OVER (PARTITION BY week_number ORDER BY attendance_datetime DESC) rn " +
                        "    FROM attendance_records " +
                        "   WHERE student_id = ? AND subject_id = ? AND week_number BETWEEN 1 AND 16 " +
                        ") WHERE rn = 1 GROUP BY status";

        String[] weekStatus = new String[16];

        try (PreparedStatement ps = conn.prepareStatement(WEEK_SELECT)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setString(1, studentId);
            ps.setInt(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int w = rs.getInt("week_number");
                    String st = rs.getString("status");
                    if (w >= 1 && w <= 16) weekStatus[w - 1] = st;
                }
            }
        }

        Map<String, Integer> summary = new HashMap<>();
        summary.put("출석", 0);
        summary.put("지각_조퇴", 0);
        summary.put("결석", 0);

        try (PreparedStatement ps = conn.prepareStatement(SUM_SELECT)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setString(1, studentId);
            ps.setInt(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString("status");
                    int cnt = rs.getInt("cnt");
                    if (summary.containsKey(status)) summary.put(status, cnt);
                }
            }
        }

        JsonArray weeksArr = new JsonArray();
        for (int i = 0; i < 16; i++) {
            JsonObject wk = new JsonObject();
            wk.addProperty("week", i + 1);
            if (weekStatus[i] != null) wk.addProperty("status", weekStatus[i]);
            else wk.add("status", null);
            weeksArr.add(wk);
        }
        root.add("weeks", weeksArr);

        JsonObject summaryObj = new JsonObject();
        summaryObj.addProperty("출석", summary.get("출석"));
        summaryObj.addProperty("지각_조퇴", summary.get("지각_조퇴"));
        summaryObj.addProperty("결석", summary.get("결석"));
        root.add("summary", summaryObj);
    }

    /** (B) 과목+주차 요약: counts, totalEnrolled, 날짜(labelDate 포함) */
    private void addWeekSummaryWithDates(Connection conn, JsonObject root, int subjectId, int week) throws Exception {

        // 1) 총원
        final String SQL_TOTAL = "SELECT COUNT(*) AS cnt FROM enrollments WHERE subject_id = ?";
        int totalEnrolled = 0;
        try (PreparedStatement ps = conn.prepareStatement(SQL_TOTAL)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setInt(1, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) totalEnrolled = rs.getInt("cnt");
            }
        }

        // 2) 주차별 최신 상태 카운트(학생당 최신 1건만 집계)
        final String SQL_COUNTS =
                "SELECT status, COUNT(*) cnt FROM ( " +
                        "  SELECT ar.student_id, ar.status, " +
                        "         ROW_NUMBER() OVER (PARTITION BY ar.student_id " +
                        "                            ORDER BY ar.attendance_datetime DESC) rn " +
                        "    FROM attendance_records ar " +
                        "   WHERE ar.subject_id = ? AND ar.week_number = ? " +
                        ") WHERE rn = 1 GROUP BY status";

        int present = 0, late = 0, absent = 0;
        try (PreparedStatement ps = conn.prepareStatement(SQL_COUNTS)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setInt(1, subjectId);
            ps.setInt(2, week);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String st = rs.getString("status");
                    int cnt   = rs.getInt("cnt");
                    if ("출석".equals(st))          present = cnt;
                    else if ("지각_조퇴".equals(st)) late    = cnt;
                    else if ("결석".equals(st))      absent  = cnt;
                }
            }
        }

        // 3) 실수업일(해당 주차 기록 중 최소 날짜) — 참고용
        final String SQL_CLASS_DATE =
                "SELECT MIN(CAST(attendance_datetime AS DATE)) AS d " +
                        "  FROM attendance_records " +
                        " WHERE subject_id = ? AND week_number = ?";
        java.sql.Date classDate = null;
        try (PreparedStatement ps = conn.prepareStatement(SQL_CLASS_DATE)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setInt(1, subjectId);
            ps.setInt(2, week);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) classDate = rs.getDate("d");
            }
        }

        // 4) 예정일(학기 시작 + 과목 요일 + 7*(주차-1))
        final String SQL_PLANNED =
                "WITH base AS ( " +
                        "  SELECT NEXT_DAY(si.start_date - 1, " +
                        "    CASE UPPER(s.day_of_week) " +
                        "      WHEN 'MON' THEN 'MONDAY' WHEN 'TUE' THEN 'TUESDAY' " +
                        "      WHEN 'WED' THEN 'WEDNESDAY' WHEN 'THU' THEN 'THURSDAY' " +
                        "      WHEN 'FRI' THEN 'FRIDAY' WHEN 'SAT' THEN 'SATURDAY' " +
                        "      WHEN 'SUN' THEN 'SUNDAY' END) AS first_class_date " +
                        "  FROM subjects s " +
                        "  JOIN ( SELECT start_date " +
                        "           FROM semester_info " +
                        "          WHERE start_date <= CAST(SYSTIMESTAMP AS DATE) " +
                        "          ORDER BY start_date DESC FETCH FIRST 1 ROWS ONLY " +
                        "       ) si ON 1=1 " +
                        "  WHERE s.subject_id = ? " +
                        ") SELECT first_class_date + 7 * (? - 1) AS d FROM base";

        java.sql.Date plannedDate = null;
        try (PreparedStatement ps = conn.prepareStatement(SQL_PLANNED)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setInt(1, subjectId);
            ps.setInt(2, week);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) plannedDate = rs.getDate("d");
            }
        }

        // 5) 표시용 날짜 우선순위용: display_date, closed_at
        final String SQL_DATES =
                "SELECT " +
                        " (SELECT display_date FROM attendance_closures " +
                        "   WHERE subject_id=? AND week_number=?) AS display_date, " +
                        " (SELECT closed_at FROM attendance_closures " +
                        "   WHERE subject_id=? AND week_number=?) AS closed_at " +
                        " FROM dual";

        java.sql.Date displayDate = null, closedAt = null;
        try (PreparedStatement ps = conn.prepareStatement(SQL_DATES)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setInt(1, subjectId); ps.setInt(2, week);
            ps.setInt(3, subjectId); ps.setInt(4, week);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    displayDate = rs.getDate("display_date");
                    closedAt    = rs.getDate("closed_at");
                }
            }
        }

        // 6) 미체크 = 총원 - (해당 주차 기록 존재 학생 수)
        final String SQL_RECORDED =
                "SELECT COUNT(DISTINCT student_id) AS cnt " +
                        "  FROM attendance_records WHERE subject_id = ? AND week_number = ?";
        int recordedDistinct = 0;
        try (PreparedStatement ps = conn.prepareStatement(SQL_RECORDED)) {
            ps.setQueryTimeout(SQL_TIMEOUT_SEC);
            ps.setInt(1, subjectId);
            ps.setInt(2, week);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) recordedDistinct = rs.getInt("cnt");
            }
        }
        int unchecked = Math.max(0, totalEnrolled - recordedDistinct);

        // 7) 응답 JSON 구성
        JsonObject wk = new JsonObject();
        wk.addProperty("week", week);

        JsonObject counts = new JsonObject();
        counts.addProperty("출석", present);
        counts.addProperty("지각_조퇴", late);
        counts.addProperty("결석", absent);
        counts.addProperty("미체크", unchecked);
        wk.add("counts", counts);

        wk.addProperty("totalEnrolled", totalEnrolled);

        if (classDate   != null) wk.addProperty("classDate",   DF.format(classDate));
        if (plannedDate != null) wk.addProperty("plannedDate", DF.format(plannedDate));

        // ★ 라벨 날짜(클라이언트는 이 값을 우선 사용)
        String labelDate = null;
        if (displayDate != null)      labelDate = DF.format(displayDate);
        else if (closedAt != null)    labelDate = DF.format(closedAt);
        else if (plannedDate != null) labelDate = DF.format(plannedDate);
        if (labelDate != null) wk.addProperty("labelDate", labelDate);

        root.add("weekSummary", wk);
    }

    private static String safeMsg(String m) {
        if (m == null) return "";
        return m.replace("\"", "\\\"");
    }
}

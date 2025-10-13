package com.example.attendance.servlet;

import com.example.attendance.model.ManualAttendanceResponse;
import com.example.attendance.model.StudentAttendance;
import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.Properties;               // [ADDED]
import java.util.concurrent.Semaphore;     // [ADDED]
import java.util.concurrent.TimeUnit;      // [ADDED]

@WebServlet("/getManualAttendanceList")
public class GetManualAttendanceListServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(GetManualAttendanceListServlet.class.getName());

    // [ADDED]
    private static final Semaphore GUARD = new Semaphore(12, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;

    private Connection getConnection() throws Exception { // [CHANGED]
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

        boolean ok = false; // [ADDED]
        try { // [ADDED]
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) { response.setStatus(503); response.getWriter().write("{\"result\":\"busy\"}"); return; }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");

            String subjectIdStr = request.getParameter("subjectId");
            String weekStr      = request.getParameter("week");
            String q            = request.getParameter("q");
            String statusFilter = request.getParameter("status");

            if (subjectIdStr == null || weekStr == null) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"subjectId, week 파라미터 필요\"}");
                return;
            }

            int subjectId, week;
            try {
                subjectId = Integer.parseInt(subjectIdStr);
                week = Integer.parseInt(weekStr);
            } catch (NumberFormatException e) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"subjectId/week 는 숫자여야 함\"}");
                return;
            }
            if (week < 1 || week > 16) {
                response.setStatus(400);
                response.getWriter().write("{\"error\":\"week 는 1~16 범위\"}");
                return;
            }
            if (statusFilter == null || statusFilter.trim().isEmpty()) statusFilter = "all";
            statusFilter = statusFilter.trim();

            final String BASE_SQL =
                    "WITH latest AS ( " +
                            "  SELECT ar.student_id, ar.status, ar.attendance_datetime, " +
                            "         ROW_NUMBER() OVER (PARTITION BY ar.student_id ORDER BY ar.attendance_datetime DESC) rn " +
                            "    FROM attendance_records ar " +
                            "   WHERE ar.subject_id = ? AND ar.week_number = ? " +
                            ") " +
                            "SELECT u.user_id AS student_id, u.name, l.status " +
                            "  FROM enrollments e " +
                            "  JOIN users u ON u.user_id = e.student_id " +
                            "  LEFT JOIN latest l ON l.student_id = e.student_id AND l.rn = 1 " +
                            " WHERE e.subject_id = ? AND u.role = 'student' ";

            StringBuilder sql = new StringBuilder(BASE_SQL);
            if (q != null && !q.trim().isEmpty()) {
                sql.append(" AND (LOWER(u.name) LIKE ? OR u.user_id LIKE ?) ");
            }
            switch (statusFilter) {
                case "출석":
                case "결석":
                    sql.append(" AND l.status = ? ");
                    break;
                case "지각/조퇴":
                    sql.append(" AND l.status = '지각_조퇴' ");
                    break;
                case "미체크":
                    sql.append(" AND l.status IS NULL ");
                    break;
                default:
            }
            sql.append(" ORDER BY u.name ASC ");

            List<StudentAttendance> list = new ArrayList<>();

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {

                pstmt.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]

                int idx = 1;
                pstmt.setInt(idx++, subjectId);
                pstmt.setInt(idx++, week);
                pstmt.setInt(idx++, subjectId);

                if (q != null && !q.trim().isEmpty()) {
                    String likeLower = "%" + q.trim().toLowerCase() + "%";
                    String likeRaw   = "%" + q.trim() + "%";
                    pstmt.setString(idx++, likeLower);
                    pstmt.setString(idx++, likeRaw);
                }
                if ("출석".equals(statusFilter) || "결석".equals(statusFilter)) {
                    pstmt.setString(idx++, statusFilter);
                }

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String studentId = rs.getString("student_id");
                        String name      = rs.getString("name");
                        String statusDb  = rs.getString("status");
                        String displayStatus;
                        if (statusDb == null) displayStatus = "미체크";
                        else if ("지각_조퇴".equals(statusDb)) displayStatus = "지각/조퇴";
                        else displayStatus = statusDb;
                        list.add(new StudentAttendance(studentId, name, displayStatus));
                    }
                }

            } catch (Exception e) {
                logger.severe("getManualAttendanceList error: " + e.getMessage());
                response.setStatus(500);
                response.getWriter().write("{\"error\":\"서버 오류: " + e.getMessage().replace("\"","\\\"") + "\"}");
                return;
            }

            ManualAttendanceResponse respObj = new ManualAttendanceResponse(list);
            String json = new Gson().toJson(respObj);
            response.getWriter().write(json);

        } catch (InterruptedException ie) { // [ADDED]
            Thread.currentThread().interrupt(); response.setStatus(503);
        } finally { // [ADDED]
            if (ok) GUARD.release();
        }
    }
}

package com.example.attendance.servlet;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.logging.Logger;

@WebServlet("/endAttendance")
public class EndAttendanceServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(StartAttendanceServlet.class.getName());

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        PrintWriter out = resp.getWriter();

        String subjectIdStr = req.getParameter("subjectId");
        String weekNumberStr = req.getParameter("weekNumber");

        if (subjectIdStr == null || weekNumberStr == null ||
                subjectIdStr.isEmpty() || weekNumberStr.isEmpty()) {
            out.write("{\"result\":\"error\", \"message\":\"필수 파라미터 누락\"}");
            return;
        }

        int subjectId, weekNumber;
        try {
            subjectId = Integer.parseInt(subjectIdStr);
            weekNumber = Integer.parseInt(weekNumberStr);
        } catch (NumberFormatException e) {
            out.write("{\"result\":\"error\", \"message\":\"숫자 형식 오류\"}");
            return;
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            // 1. 과목 BSSID 초기화
            String updateSubjectSql = "UPDATE subjects SET professor_bssid = NULL WHERE subject_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSubjectSql)) {
                ps.setInt(1, subjectId);
                ps.executeUpdate();
            }
            // 2. 결석 처리 (출석하지 않은 학생만)
            String sql =
                    "INSERT INTO attendance_records (" +
                            "student_id, subject_id, attendance_datetime, status, " +
                            "bssid, week_number) " +
                            "SELECT e.student_id, ?, (SYSTIMESTAMP AT TIME ZONE 'Asia/Seoul'), '결석', " +
                            "NULL, ? " +
                            "FROM enrollments e " +
                            "WHERE e.subject_id = ? " +
                            "AND NOT EXISTS ( " +
                            "  SELECT 1 FROM attendance_records ar " +
                            "  WHERE ar.student_id = e.student_id " +
                            "  AND ar.subject_id = ? " +
                            "  AND TRUNC(ar.attendance_datetime) = TRUNC(SYSTIMESTAMP AT TIME ZONE 'Asia/Seoul') " +
                            ")";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, subjectId);
                ps.setInt(2, weekNumber);
                ps.setInt(3, subjectId);
                ps.setInt(4, subjectId);
                ps.executeUpdate();
            }
            conn.commit();
            out.write("{\"result\":\"success\"}");

        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(500);
            out.write("{\"result\":\"error\", \"message\":\"DB 오류: " + e.getMessage() + "\"}");
        }
    }
}
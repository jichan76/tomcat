package com.example.attendance.servlet;

import java.io.*;
import java.sql.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import com.google.gson.JsonObject;

@WebServlet("/AttendanceServlet")
public class AttendanceServlet extends HttpServlet {

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter out = response.getWriter();
        JsonObject jsonResponse = new JsonObject();

        String studentId = request.getParameter("student_id");
        String bssid = request.getParameter("bssid");
        String androidId = request.getParameter("android_id");

        if (studentId == null || bssid == null || androidId == null) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "Missing parameters");
            out.print(jsonResponse.toString());
            return;
        }

        try (Connection conn = getConnection()) {
            // 교수 BSSID와 일치하는 수강 과목 찾기 및 출석 오픈 로그 검증
//            String sql = "SELECT COUNT(*) FROM attendance_open_log " +
//                    " WHERE subject_id = (" +
//                    " SELECT e.subject_id FROM enrollments e " +
//                    " WHERE e.student_id = ? AND ROWNUM = 1" +
//                    " ) " + " AND bssid = ? " + " AND open_datetime <= SYSTIMESTAMP " +
//                    " AND (close_datetime IS NULL OR close_datetime >= SYSTIMESTAMP)"; // 오라클용 LIMIT 1
            String selectOpenLogSql = "SELECT subject_id FROM attendance_open_log " +
                    " WHERE subject_id = (SELECT e.subject_id FROM enrollments e WHERE e.student_id = ? AND ROWNUM = 1) " +
                    "   AND bssid = ? " +
                    "   AND open_datetime <= SYSTIMESTAMP " +
                    "   AND (close_datetime IS NULL OR close_datetime >= SYSTIMESTAMP)"; // 오라클용 LIMIT 1
            String actualSubjectId = null; // 실제 출석이 열린 과목의 ID를 저장할 변수

            try (PreparedStatement ps = conn.prepareStatement(selectOpenLogSql)) {
                ps.setString(1, studentId);
                ps.setString(2, bssid);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    actualSubjectId = rs.getString("subject_id"); // 조회된 subject_id 저장
                } else {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "현재 출석이 열려 있지 않습니다.");
                    out.print(jsonResponse.toString());
                    return;
                }
            }
//                rs.next();
//                if (rs.getInt(1) == 0) {
//                    jsonResponse.addProperty("result", "fail");
//                    jsonResponse.addProperty("message", "현재 출석이 열려 있지 않습니다.");
//                    out.print(jsonResponse.toString());
//                    return;
//                }
//            }
            // 2) 출석 기록 삽입
//            String insertSql =
//                    "INSERT INTO attendance_records " +
//                            "(student_id, subject_id, attendance_datetime, status, bssid, android_id) " +
//                            "VALUES (?, " +
//                            "   (SELECT e.subject_id FROM enrollments e WHERE e.student_id = ? AND ROWNUM = 1)," +
//                            "    SYSTIMESTAMP, '출석', ?, ?)";
            String insertSql =
                    "INSERT INTO attendance_records " +
                            "(student_id, subject_id, attendance_datetime, status, bssid, android_id) " +
                            "VALUES (?, ?, SYSTIMESTAMP, '출석', ?, ?)"; // subject_id를 변수로 대체
            try (PreparedStatement ps2 = conn.prepareStatement(insertSql)) {
                ps2.setString(1, studentId);
                ps2.setString(2, actualSubjectId); // 여기서 조회된 actualSubjectId 사용
                ps2.setString(3, bssid);
                ps2.setString(4, androidId);
                ps2.executeUpdate();
            }

            jsonResponse.addProperty("result", "success");
        } catch (Exception e) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", e.getMessage());
            e.printStackTrace();
        }
        out.print(jsonResponse.toString());
    }
}